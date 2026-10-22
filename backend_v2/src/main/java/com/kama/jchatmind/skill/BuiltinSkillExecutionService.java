package com.kama.jchatmind.skill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.AgentMapper;
import com.kama.jchatmind.model.entity.Agent;
import com.kama.jchatmind.model.response.GetChatSessionResponse;
import com.kama.jchatmind.service.AgentKnowledgeBaseBindingService;
import com.kama.jchatmind.service.ChatSessionFacadeService;
import com.kama.jchatmind.service.KnowledgeBaseAccessService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@Service
public class BuiltinSkillExecutionService {

    private static final String KNOWLEDGE_TOOL = "KnowledgeTool";

    private final ChatSessionFacadeService chatSessionFacadeService;
    private final AgentMapper agentMapper;
    private final AgentKnowledgeBaseBindingService agentKnowledgeBaseBindingService;
    private final KnowledgeBaseAccessService knowledgeBaseAccessService;
    private final BuiltinSkillExecutor builtinSkillExecutor;
    private final ObjectMapper objectMapper;

    public BuiltinSkillExecutionService(
            ChatSessionFacadeService chatSessionFacadeService,
            AgentMapper agentMapper,
            AgentKnowledgeBaseBindingService agentKnowledgeBaseBindingService,
            KnowledgeBaseAccessService knowledgeBaseAccessService,
            BuiltinSkillExecutor builtinSkillExecutor,
            ObjectMapper objectMapper
    ) {
        this.chatSessionFacadeService = chatSessionFacadeService;
        this.agentMapper = agentMapper;
        this.agentKnowledgeBaseBindingService = agentKnowledgeBaseBindingService;
        this.knowledgeBaseAccessService = knowledgeBaseAccessService;
        this.builtinSkillExecutor = builtinSkillExecutor;
        this.objectMapper = objectMapper;
    }

    public ExecutionResponse execute(
            Long ownerId,
            String agentId,
            String sessionId,
            String skillId,
            Map<String, Object> input
    ) {
        PreparedSkillExecution prepared = prepare(ownerId, agentId, sessionId, skillId, input);
        return execute(prepared);
    }

    ExecutionResponse execute(PreparedSkillExecution prepared) {
        try {
            SkillExecutionResult result = builtinSkillExecutor.execute(
                    prepared.userId(),
                    prepared.sessionId(),
                    prepared.invocation()
            );
            return new ExecutionResponse(
                    result.output(), result.validation().valid(), result.validation().violations()
            );
        } catch (IllegalArgumentException exception) {
            throw new BizException(exception.getMessage());
        }
    }

    PreparedSkillExecution prepare(
            Long ownerId,
            String agentId,
            String sessionId,
            String skillId,
            Map<String, Object> input
    ) {
        if (ownerId == null || ownerId <= 0 || !StringUtils.hasText(agentId)
                || !StringUtils.hasText(sessionId) || !StringUtils.hasText(skillId) || input == null) {
            throw new BizException("内置 Skill 请求无效");
        }
        String userId = String.valueOf(ownerId);
        GetChatSessionResponse sessionResponse = chatSessionFacadeService.getChatSession(sessionId, userId);
        if (sessionResponse == null || sessionResponse.getChatSession() == null
                || !agentId.equals(sessionResponse.getChatSession().getAgentId())) {
            throw new BizException("无权访问会话");
        }

        Agent agent = agentMapper.selectById(agentId);
        if (agent == null || !userId.equals(agent.getUserId())) {
            throw new BizException("无权访问 Agent");
        }
        if (!allowsKnowledgeTool(agent.getAllowedTools())) {
            throw new BizException("Agent 未授权知识检索工具");
        }

        List<String> boundKnowledgeBaseIds = agentKnowledgeBaseBindingService.getBoundKnowledgeBaseIds(agentId);
        List<String> authorizedKnowledgeBaseIds = knowledgeBaseAccessService.requireAccessibleKnowledgeBaseIds(
                boundKnowledgeBaseIds,
                userId
        );
        if (authorizedKnowledgeBaseIds.isEmpty()) {
            throw new BizException("Agent 未绑定可访问的知识库");
        }

        try {
            return new PreparedSkillExecution(
                    userId,
                    agentId,
                    sessionId,
                    skillId,
                    builtinSkillExecutor.prepare(
                            skillId,
                            input,
                            authorizedKnowledgeBaseIds
                    )
            );
        } catch (IllegalArgumentException exception) {
            throw new BizException(exception.getMessage());
        }
    }

    record PreparedSkillExecution(
            String userId,
            String agentId,
            String sessionId,
            String skillId,
            PreparedSkillInvocation invocation
    ) {
    }

    private boolean allowsKnowledgeTool(String allowedToolsJson) {
        if (!StringUtils.hasText(allowedToolsJson)) {
            return false;
        }
        try {
            JsonNode allowedTools = objectMapper.readTree(allowedToolsJson);
            if (!allowedTools.isArray()) {
                return false;
            }
            for (JsonNode tool : allowedTools) {
                if (tool.isTextual() && KNOWLEDGE_TOOL.equals(tool.asText())) {
                    return true;
                }
            }
            return false;
        } catch (JsonProcessingException exception) {
            return false;
        }
    }

    public record ExecutionResponse(
            Map<String, Object> output,
            boolean valid,
            List<String> violations
    ) {
        public ExecutionResponse {
            output = Map.copyOf(output);
            violations = List.copyOf(violations);
        }
    }
}
