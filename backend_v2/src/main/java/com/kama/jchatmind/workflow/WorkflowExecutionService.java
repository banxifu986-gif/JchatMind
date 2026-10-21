package com.kama.jchatmind.workflow;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.AgentMapper;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.model.entity.Agent;
import com.kama.jchatmind.model.response.GetChatSessionResponse;
import com.kama.jchatmind.service.AgentKnowledgeBaseBindingService;
import com.kama.jchatmind.service.ChatSessionFacadeService;
import com.kama.jchatmind.service.KnowledgeBaseAccessService;
import com.kama.jchatmind.service.RagService;
import com.kama.jchatmind.webhook.WebhookProperties;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class WorkflowExecutionService {

    private static final String KNOWLEDGE_TOOL = "KnowledgeTool";
    private static final int RETRIEVAL_LIMIT = 10;
    private static final int MAX_REQUEST_CHARACTERS = 16_384;

    private final AgentMapper agentMapper;
    private final ChatSessionFacadeService chatSessionFacadeService;
    private final AgentKnowledgeBaseBindingService bindingService;
    private final KnowledgeBaseAccessService knowledgeBaseAccessService;
    private final RagService ragService;
    private final WorkflowPlanner planner;
    private final WorkflowPlanExecutor executor;
    private final WorkflowExecutionAuditRecorder auditRecorder;
    private final WorkflowSingleAgentFallbackAnswerService fallbackAnswerService;
    private final WebhookProperties webhookProperties;
    private final ObjectMapper objectMapper;

    public WorkflowExecutionService(
            AgentMapper agentMapper,
            ChatSessionFacadeService chatSessionFacadeService,
            AgentKnowledgeBaseBindingService bindingService,
            KnowledgeBaseAccessService knowledgeBaseAccessService,
            RagService ragService,
            WorkflowPlanner planner,
            WorkflowPlanExecutor executor,
            WorkflowExecutionAuditRecorder auditRecorder,
            WorkflowSingleAgentFallbackAnswerService fallbackAnswerService,
            WebhookProperties webhookProperties,
            ObjectMapper objectMapper
    ) {
        this.agentMapper = agentMapper;
        this.chatSessionFacadeService = chatSessionFacadeService;
        this.bindingService = bindingService;
        this.knowledgeBaseAccessService = knowledgeBaseAccessService;
        this.ragService = ragService;
        this.planner = planner;
        this.executor = executor;
        this.auditRecorder = auditRecorder;
        this.fallbackAnswerService = fallbackAnswerService;
        this.webhookProperties = webhookProperties;
        this.objectMapper = objectMapper;
    }

    public WorkflowExecutionResponse execute(Long ownerId, WorkflowExecutionCommand command) {
        if (ownerId == null || ownerId <= 0 || command == null
                || !StringUtils.hasText(command.agentId())
                || !StringUtils.hasText(command.sessionId())
                || !StringUtils.hasText(command.sourceId())
                || !StringUtils.hasText(command.request())
                || command.request().length() > MAX_REQUEST_CHARACTERS) {
            throw new BizException("工作流请求无效");
        }
        long startedAt = System.nanoTime();
        String traceId = UUID.randomUUID().toString();
        String userId = String.valueOf(ownerId);
        GetChatSessionResponse sessionResponse = chatSessionFacadeService.getChatSession(
                command.sessionId(),
                userId
        );
        if (sessionResponse == null || sessionResponse.getChatSession() == null
                || !command.agentId().equals(sessionResponse.getChatSession().getAgentId())) {
            throw new BizException("无权访问会话");
        }
        Agent agent = agentMapper.selectById(command.agentId());
        if (agent == null || !userId.equals(agent.getUserId())) {
            throw new BizException("无权访问 Agent");
        }

        WebhookProperties.Source source;
        try {
            source = webhookProperties.requireSource(command.sourceId());
        } catch (IllegalStateException exception) {
            throw new BizException("无权访问 Webhook 来源");
        }
        if (!ownerId.equals(source.getOwnerId())) {
            throw new BizException("无权访问 Webhook 来源");
        }

        List<String> configuredTools = parseAllowedTools(agent.getAllowedTools());
        if (!configuredTools.contains(KNOWLEDGE_TOOL)) {
            throw new BizException("Agent 未授权知识检索工具");
        }
        List<String> boundKbIds = bindingService.getBoundKnowledgeBaseIds(agent.getId());
        List<String> agentKbIds = knowledgeBaseAccessService.requireAccessibleKnowledgeBaseIds(
                boundKbIds,
                userId
        );
        List<String> allowedKbIds = agentKbIds.stream()
                .filter(source.getAllowedKbIds()::contains)
                .toList();
        if (allowedKbIds.isEmpty()) {
            throw new BizException("Webhook 来源未授权 Agent 知识库");
        }
        List<RagRetrievalResult> results = allowedKbIds.isEmpty()
                ? List.of()
                : ragService.retrieve(allowedKbIds, command.request(), RETRIEVAL_LIMIT);
        List<WorkflowEvidence> evidence = toEvidence(results);
        Map<String, RagRetrievalResult> resultByChunkId = new LinkedHashMap<>();
        if (results != null) {
            results.stream()
                    .filter(result -> result != null && StringUtils.hasText(result.getChunkId()))
                    .forEach(result -> resultByChunkId.putIfAbsent(result.getChunkId(), result));
        }

        WorkflowPlanPlanningResult planning = planner.plan(
                agent.getModel(),
                command.request(),
                List.of(KNOWLEDGE_TOOL),
                evidence
        );
        if (planning == null || !planning.accepted() || planning.plan() == null) {
            List<String> reasons = planning == null || planning.violations() == null
                    ? List.of("工作流计划不可用")
                    : planning.violations();
            return fallback(traceId, ownerId, command, agent.getModel(), evidence, reasons, startedAt);
        }

        WorkflowExecutionResult execution;
        try {
            execution = executor.executeForWebhookSource(
                    userId,
                    agent.getId(),
                    command.sessionId(),
                    command.sourceId(),
                    planning.plan(),
                    List.of(KNOWLEDGE_TOOL),
                    step -> executeEvidenceStep(step, resultByChunkId)
            );
        } catch (RuntimeException exception) {
            execution = WorkflowExecutionResult.halted(List.of(new WorkflowStepExecution(
                    "workflow-execution",
                    WorkflowStepExecutionStatus.FAILED,
                    "工作流执行失败"
            )));
        }
        if (execution == null || (!execution.completed() && execution.steps().isEmpty()
                && execution.violations() != null && !execution.violations().isEmpty())) {
            List<String> reasons = execution == null || execution.violations() == null
                    ? List.of("工作流执行不可用")
                    : execution.violations();
            return fallback(traceId, ownerId, command, agent.getModel(), evidence, reasons, startedAt);
        }

        long durationMs = elapsedMillis(startedAt);
        auditRecorder.record(traceId, ownerId, command.request(), planning.plan(), execution, durationMs);
        return new WorkflowExecutionResponse(
                traceId,
                WorkflowOrchestrationMode.WORKFLOW.name(),
                execution,
                null,
                List.of()
        );
    }

    private String executeEvidenceStep(WorkflowStep step, Map<String, RagRetrievalResult> results) {
        if (!KNOWLEDGE_TOOL.equals(step.toolName())) {
            throw new BizException("工作流工具未授权");
        }
        return step.evidence().stream()
                .map(WorkflowEvidence::chunkId)
                .distinct()
                .map(results::get)
                .filter(result -> result != null)
                .map(RagRetrievalResult::getContent)
                .filter(StringUtils::hasText)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
    }

    private WorkflowExecutionResponse fallback(
            String traceId,
            Long ownerId,
            WorkflowExecutionCommand command,
            String modelKey,
            List<WorkflowEvidence> evidence,
            List<String> reasons,
            long startedAt
    ) {
        String answer = null;
        try {
            answer = fallbackAnswerService.answer(modelKey, command.request(), evidence);
        } catch (RuntimeException ignored) {
            answer = null;
        }
        long durationMs = elapsedMillis(startedAt);
        auditRecorder.recordFallback(traceId, ownerId, command.request(), answer, durationMs);
        return new WorkflowExecutionResponse(
                traceId,
                WorkflowOrchestrationMode.SINGLE_AGENT_FALLBACK.name(),
                null,
                answer,
                reasons == null ? List.of() : List.copyOf(reasons)
        );
    }

    private List<String> parseAllowedTools(String rawTools) {
        if (!StringUtils.hasText(rawTools)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(rawTools, new TypeReference<>() {
            });
        } catch (Exception exception) {
            throw new BizException("Agent 工具配置无效");
        }
    }

    private List<WorkflowEvidence> toEvidence(List<RagRetrievalResult> results) {
        if (results == null) {
            return List.of();
        }
        return results.stream()
                .filter(result -> result != null
                        && StringUtils.hasText(result.getChunkId())
                        && StringUtils.hasText(result.getContent()))
                .map(result -> new WorkflowEvidence(
                        result.getChunkId(),
                        result.getChunkId(),
                        result.getContent()
                ))
                .toList();
    }

    private long elapsedMillis(long startedAt) {
        return Math.max(0, (System.nanoTime() - startedAt) / 1_000_000);
    }

    public record WorkflowExecutionCommand(String agentId, String sessionId, String request, String sourceId) {
    }

    public record WorkflowExecutionResponse(
            String traceId,
            String mode,
            WorkflowExecutionResult workflowExecution,
            String assistantResponse,
            List<String> fallbackReasons
    ) {
    }
}
