package com.kama.jchatmind.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.ChatClientRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

@Component
public class WorkflowPlanner {

    private static final int MAX_INPUT_CHARACTERS = 16_384;
    private static final int MAX_OUTPUT_CHARACTERS = 16_384;
    private static final Set<String> PLAN_FIELDS = Set.of("maxSteps", "timeoutSeconds", "steps");
    private static final Set<String> STEP_FIELDS = Set.of("id", "toolName", "factKey", "claim", "evidence");
    private static final Set<String> EVIDENCE_FIELDS = Set.of("chunkId", "factKey", "statement");
    private static final String SYSTEM_PROMPT = """
            你是 JChatMind 的工作流 Planner，只输出 JSON，不执行工具。
            计划只允许使用输入中的 allowedTools，证据只能逐字选择输入中的 availableEvidence。
            不得增加授权、审批、用户身份、知识库范围或其他控制字段。
            输出 schema：{"maxSteps":1,"timeoutSeconds":10,"steps":[{"id":"step-1","toolName":"KnowledgeTool","factKey":"事实键","claim":"声明","evidence":[{"chunkId":"chunk-id","factKey":"事实键","statement":"证据原文"}]}]}。
            maxSteps 必须为 1 到 20，timeoutSeconds 必须为 1 到 30；每个声明都必须有输入中完全匹配的证据。
            不要输出 Markdown、解释文本或思维过程。
            """;

    private final ChatClientRegistry chatClientRegistry;
    private final ObjectMapper objectMapper;
    private final WorkflowPlanVerifier workflowPlanVerifier;

    public WorkflowPlanner(
            ChatClientRegistry chatClientRegistry,
            ObjectMapper objectMapper,
            WorkflowPlanVerifier workflowPlanVerifier
    ) {
        this.chatClientRegistry = chatClientRegistry;
        this.objectMapper = objectMapper;
        this.workflowPlanVerifier = workflowPlanVerifier;
    }

    public WorkflowPlanPlanningResult plan(
            String modelKey,
            String userRequest,
            List<String> allowedTools,
            List<WorkflowEvidence> availableEvidence
    ) {
        ChatClient chatClient = StringUtils.hasText(modelKey) ? chatClientRegistry.get(modelKey) : null;
        if (chatClient == null) {
            return WorkflowPlanPlanningResult.rejected("工作流规划模型不可用");
        }
        if (!StringUtils.hasText(userRequest)) {
            return WorkflowPlanPlanningResult.rejected("工作流规划请求不能为空");
        }
        List<String> tools = allowedTools == null ? List.of() : List.copyOf(allowedTools);
        List<WorkflowEvidence> evidence = availableEvidence == null ? List.of() : List.copyOf(availableEvidence);
        if (tools.isEmpty() || evidence.stream().anyMatch(item -> item == null)) {
            return WorkflowPlanPlanningResult.rejected("工作流规划输入范围无效");
        }

        String request;
        try {
            request = objectMapper.writeValueAsString(new PlanningRequest(userRequest, tools, evidence));
        } catch (JsonProcessingException exception) {
            return WorkflowPlanPlanningResult.rejected("工作流规划输入无法序列化");
        }
        if (request.length() > MAX_INPUT_CHARACTERS) {
            return WorkflowPlanPlanningResult.rejected("工作流规划输入超出长度限制");
        }

        String response;
        try {
            response = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user(request)
                    .call()
                    .content();
        } catch (RuntimeException exception) {
            return WorkflowPlanPlanningResult.rejected("工作流计划生成失败");
        }
        if (!StringUtils.hasText(response) || response.length() > MAX_OUTPUT_CHARACTERS) {
            return WorkflowPlanPlanningResult.rejected("工作流计划输出为空或超出长度限制");
        }

        WorkflowPlan plan;
        try {
            plan = parsePlan(response);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return WorkflowPlanPlanningResult.rejected("工作流计划输出不符合 JSON schema");
        }
        if (plan == null || plan.steps().isEmpty()) {
            return WorkflowPlanPlanningResult.rejected("工作流计划输出不符合 JSON schema");
        }

        Set<WorkflowEvidence> allowedEvidence = new HashSet<>(evidence);
        if (plan.steps().stream()
                .flatMap(step -> step.evidence().stream())
                .anyMatch(item -> !allowedEvidence.contains(item))) {
            return WorkflowPlanPlanningResult.rejected("工作流计划引用了当前证据集之外的证据");
        }

        WorkflowVerificationResult verification = workflowPlanVerifier.verify(plan, tools);
        if (!verification.accepted()) {
            return WorkflowPlanPlanningResult.rejected(verification.violations());
        }
        return WorkflowPlanPlanningResult.accepted(plan);
    }

    private WorkflowPlan parsePlan(String response) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(response);
        if (!hasOnlyFields(root, PLAN_FIELDS)
                || !root.path("maxSteps").isIntegralNumber()
                || !root.path("maxSteps").canConvertToInt()
                || !root.path("timeoutSeconds").isIntegralNumber()
                || !root.path("timeoutSeconds").canConvertToInt()
                || !root.path("steps").isArray()) {
            return null;
        }

        List<WorkflowStep> steps = new ArrayList<>();
        for (JsonNode step : root.path("steps")) {
            if (!hasOnlyFields(step, STEP_FIELDS) || !step.path("evidence").isArray()) {
                return null;
            }
            List<WorkflowEvidence> evidence = new ArrayList<>();
            for (JsonNode item : step.path("evidence")) {
                if (!hasOnlyFields(item, EVIDENCE_FIELDS)) {
                    return null;
                }
                evidence.add(new WorkflowEvidence(
                        requiredText(item, "chunkId"),
                        requiredText(item, "factKey"),
                        requiredText(item, "statement")
                ));
            }
            steps.add(new WorkflowStep(
                    requiredText(step, "id"),
                    requiredText(step, "toolName"),
                    requiredText(step, "factKey"),
                    requiredText(step, "claim"),
                    List.copyOf(evidence)
            ));
        }
        return new WorkflowPlan(
                root.path("maxSteps").asInt(),
                root.path("timeoutSeconds").asInt(),
                List.copyOf(steps)
        );
    }

    private boolean hasOnlyFields(JsonNode node, Set<String> allowedFields) {
        if (!node.isObject()) {
            return false;
        }
        Iterator<String> fields = node.fieldNames();
        while (fields.hasNext()) {
            if (!allowedFields.contains(fields.next())) {
                return false;
            }
        }
        return true;
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isTextual()) {
            throw new IllegalArgumentException("Workflow plan text field is invalid");
        }
        return value.asText();
    }

    private record PlanningRequest(
            String userRequest,
            List<String> allowedTools,
            List<WorkflowEvidence> availableEvidence
    ) {
    }
}

record WorkflowPlanPlanningResult(boolean accepted, WorkflowPlan plan, List<String> violations) {

    static WorkflowPlanPlanningResult accepted(WorkflowPlan plan) {
        return new WorkflowPlanPlanningResult(true, plan, List.of());
    }

    static WorkflowPlanPlanningResult rejected(String violation) {
        return new WorkflowPlanPlanningResult(false, null, List.of(violation));
    }

    static WorkflowPlanPlanningResult rejected(List<String> violations) {
        return new WorkflowPlanPlanningResult(false, null, List.copyOf(violations));
    }
}
