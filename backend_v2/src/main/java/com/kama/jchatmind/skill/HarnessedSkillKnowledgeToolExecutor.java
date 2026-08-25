package com.kama.jchatmind.skill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.harness.HarnessContext;
import com.kama.jchatmind.agent.harness.HarnessDecision;
import com.kama.jchatmind.agent.harness.HarnessResult;
import com.kama.jchatmind.agent.harness.HarnessRunner;
import com.kama.jchatmind.agent.harness.interceptor.HarnessInterceptorChain;
import com.kama.jchatmind.agent.harness.proxy.HarnessExecutionContextHolder;
import com.kama.jchatmind.agent.harness.proxy.HarnessToolCallbackProxy;
import com.kama.jchatmind.agent.tools.KnowledgeTools;
import com.kama.jchatmind.model.dto.KnowledgeBaseDTO;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.DefaultToolMetadata;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
class HarnessedSkillKnowledgeToolExecutor implements SkillKnowledgeToolExecutor {

    private static final String SKILL_AGENT_ID = "builtin-skill:technical-decision-comparison";
    private static final String TOOL_NAME = "KnowledgeTool";

    private final KnowledgeTools knowledgeTools;
    private final HarnessRunner harnessRunner;
    private final HarnessInterceptorChain harnessInterceptorChain;
    private final ObjectMapper objectMapper;

    HarnessedSkillKnowledgeToolExecutor(
            KnowledgeTools knowledgeTools,
            HarnessRunner harnessRunner,
            HarnessInterceptorChain harnessInterceptorChain,
            ObjectMapper objectMapper
    ) {
        this.knowledgeTools = knowledgeTools;
        this.harnessRunner = harnessRunner;
        this.harnessInterceptorChain = harnessInterceptorChain;
        this.objectMapper = objectMapper;
    }

    @Override
    public SkillKnowledgeToolResult execute(
            String userId,
            String sessionId,
            String question,
            List<String> knowledgeBaseIds
    ) {
        String toolInput = serializeInput(question, knowledgeBaseIds);
        String toolCallId = UUID.randomUUID().toString();
        HarnessResult harnessResult = harnessRunner.beforeExecution(
                userId,
                SKILL_AGENT_ID,
                sessionId,
                1,
                List.of(new AssistantMessage.ToolCall(toolCallId, "function", TOOL_NAME, toolInput))
        );
        harnessRunner.awaitApprovals(harnessResult);
        HarnessDecision decision = harnessResult.getDecision(toolCallId);
        HarnessContext context = harnessResult.getContext(toolCallId);
        if (decision == null || decision.getStatus() != HarnessDecision.Status.ALLOW) {
            if (context != null && decision != null) {
                harnessRunner.recordSyntheticOutcome(context, decision);
            }
            return SkillKnowledgeToolResult.blocked(decision == null ? "知识库工具未获准执行" : decision.getMessage());
        }
        if (context == null) {
            return SkillKnowledgeToolResult.blocked("知识库工具执行上下文缺失");
        }

        try {
            List<KnowledgeBaseDTO> knowledgeBases = knowledgeBaseIds.stream()
                    .map(id -> KnowledgeBaseDTO.builder().id(id).name(id).build())
                    .toList();
            SkillKnowledgeToolCallback callback = new SkillKnowledgeToolCallback(
                    knowledgeTools.fork(userId, sessionId, knowledgeBases),
                    question,
                    knowledgeBaseIds
            );
            HarnessToolCallbackProxy proxy = new HarnessToolCallbackProxy(callback, harnessInterceptorChain);
            HarnessExecutionContextHolder.bind(
                    List.of(context),
                    new HarnessExecutionContextHolder.BatchMetadata(sessionId, SKILL_AGENT_ID, userId, 1)
            );
            try {
                proxy.call(toolInput);
            } finally {
                HarnessExecutionContextHolder.clear();
            }
            return SkillKnowledgeToolResult.executed(callback.results());
        } catch (RuntimeException exception) {
            return SkillKnowledgeToolResult.blocked("知识库检索暂不可用");
        }
    }

    private String serializeInput(String question, List<String> knowledgeBaseIds) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("query", question);
        input.put("kbIds", knowledgeBaseIds);
        try {
            return objectMapper.writeValueAsString(input);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法生成 KnowledgeTool 输入", exception);
        }
    }

    private static class SkillKnowledgeToolCallback implements ToolCallback {

        private final KnowledgeTools knowledgeTools;
        private final String question;
        private final List<String> knowledgeBaseIds;
        private List<RagRetrievalResult> results = List.of();

        private SkillKnowledgeToolCallback(
                KnowledgeTools knowledgeTools,
                String question,
                List<String> knowledgeBaseIds
        ) {
            this.knowledgeTools = knowledgeTools;
            this.question = question;
            this.knowledgeBaseIds = knowledgeBaseIds;
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder()
                    .name(TOOL_NAME)
                    .description("内置 Skill 的受控知识库检索")
                    .inputSchema("{}")
                    .build();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return DefaultToolMetadata.builder().returnDirect(false).build();
        }

        @Override
        public String call(String toolInput) {
            results = knowledgeTools.retrieveKnowledge(question, knowledgeBaseIds);
            return "retrieved=" + results.size();
        }

        private List<RagRetrievalResult> results() {
            return results;
        }
    }
}
