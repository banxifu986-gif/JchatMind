package com.kama.jchatmind.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.ChatClientRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowPlannerTest {

    private static final String VALID_PLAN = """
            {"maxSteps":2,"timeoutSeconds":10,"steps":[{"id":"read-cache-design","toolName":"KnowledgeTool","factKey":"cache","claim":"缓存使用 Redis","evidence":[{"chunkId":"chunk-1","factKey":"cache","statement":"Redis 是缓存层"}]}]}
            """;

    @Test
    void shouldGenerateAPlanUsingOnlyAllowedToolsAndProvidedEvidence() {
        ChatClient chatClient = chatClientReturning(VALID_PLAN);
        WorkflowPlanner planner = planner(chatClient);
        WorkflowEvidence evidence = new WorkflowEvidence("chunk-1", "cache", "Redis 是缓存层");

        WorkflowPlanPlanningResult result = planner.plan(
                "test-model",
                "缓存用什么技术？",
                List.of("KnowledgeTool"),
                List.of(evidence)
        );

        assertThat(result.accepted()).isTrue();
        assertThat(result.plan().steps()).containsExactly(new WorkflowStep(
                "read-cache-design",
                "KnowledgeTool",
                "cache",
                "缓存使用 Redis",
                List.of(evidence)
        ));
        verify(chatClient.prompt().user(anyString()));
    }

    @Test
    void shouldRejectEvidenceThatWasNotProvidedToThePlanner() {
        ChatClient chatClient = chatClientReturning(VALID_PLAN.replace("chunk-1", "chunk-other"));
        WorkflowPlanner planner = planner(chatClient);

        WorkflowPlanPlanningResult result = planner.plan(
                "test-model",
                "缓存用什么技术？",
                List.of("KnowledgeTool"),
                List.of(new WorkflowEvidence("chunk-1", "cache", "Redis 是缓存层"))
        );

        assertThat(result.accepted()).isFalse();
        assertThat(result.plan()).isNull();
        assertThat(result.violations()).contains("工作流计划引用了当前证据集之外的证据");
    }

    @Test
    void shouldRejectModelOutputWithFieldsOutsideTheWorkflowPlanSchema() {
        ChatClient chatClient = chatClientReturning(VALID_PLAN.replace("\"maxSteps\":2", "\"authorized\":true,\"maxSteps\":2"));

        WorkflowPlanPlanningResult result = planner(chatClient).plan(
                "test-model",
                "缓存用什么技术？",
                List.of("KnowledgeTool"),
                List.of(new WorkflowEvidence("chunk-1", "cache", "Redis 是缓存层"))
        );

        assertThat(result.accepted()).isFalse();
        assertThat(result.violations()).contains("工作流计划输出不符合 JSON schema");
    }

    @Test
    void shouldRejectFractionalStepBudgetInsteadOfTruncatingIt() {
        ChatClient chatClient = chatClientReturning(VALID_PLAN.replace("\"maxSteps\":2", "\"maxSteps\":1.5"));

        WorkflowPlanPlanningResult result = planner(chatClient).plan(
                "test-model",
                "缓存用什么技术？",
                List.of("KnowledgeTool"),
                List.of(new WorkflowEvidence("chunk-1", "cache", "Redis 是缓存层"))
        );

        assertThat(result.accepted()).isFalse();
        assertThat(result.violations()).contains("工作流计划输出不符合 JSON schema");
    }

    @Test
    void shouldRejectMissingPlannerModelWithoutCallingAChatClient() {
        WorkflowPlanner planner = new WorkflowPlanner(
                new ChatClientRegistry(Map.of()),
                new ObjectMapper(),
                new WorkflowPlanVerifier()
        );

        WorkflowPlanPlanningResult result = planner.plan(
                "missing-model",
                "缓存用什么技术？",
                List.of("KnowledgeTool"),
                List.of(new WorkflowEvidence("chunk-1", "cache", "Redis 是缓存层"))
        );

        assertThat(result.accepted()).isFalse();
        assertThat(result.violations()).contains("工作流规划模型不可用");
    }

    private WorkflowPlanner planner(ChatClient chatClient) {
        return new WorkflowPlanner(
                new ChatClientRegistry(Map.of("test-model", chatClient)),
                new ObjectMapper(),
                new WorkflowPlanVerifier()
        );
    }

    private ChatClient chatClientReturning(String response) {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec request = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec call = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(request);
        when(request.system(anyString())).thenReturn(request);
        when(request.user(anyString())).thenReturn(request);
        when(request.call()).thenReturn(call);
        when(call.content()).thenReturn(response);
        return chatClient;
    }
}
