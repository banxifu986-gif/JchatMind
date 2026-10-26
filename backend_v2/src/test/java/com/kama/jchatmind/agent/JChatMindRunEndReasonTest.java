package com.kama.jchatmind.agent;

import com.kama.jchatmind.intent.IntentDecisionMetadata;
import com.kama.jchatmind.intent.IntentRouter;
import com.kama.jchatmind.message.SseMessage;
import com.kama.jchatmind.service.SseService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class JChatMindRunEndReasonTest {

    @Test
    void shouldRejectRepeatedToolSignatureWithinOneRun() {
        JChatMind agent = new JChatMind();
        List<AssistantMessage.ToolCall> calls = List.of(
                new AssistantMessage.ToolCall("call-1", "function", "KnowledgeTool", "{\"query\":\"release\"}"),
                new AssistantMessage.ToolCall("call-2", "function", "KnowledgeTool", "{\"query\":\"release\"}")
        );

        ReflectionTestUtils.invokeMethod(agent, "registerToolCallSignatures", calls);
        Set<String> duplicateIds = ReflectionTestUtils.invokeMethod(agent, "registerToolCallSignatures", calls);

        assertThat(duplicateIds).containsExactlyInAnyOrder("call-1", "call-2");
    }

    @Test
    void shouldKeepMultipleToolResponsesMatchedToCallOrder() {
        JChatMind agent = new JChatMind();
        List<AssistantMessage.ToolCall> calls = List.of(
                new AssistantMessage.ToolCall("call-1", "function", "KnowledgeTool", "{}"),
                new AssistantMessage.ToolCall("call-2", "function", "weather", "{}")
        );
        List<ToolResponseMessage.ToolResponse> responses = List.of(
                new ToolResponseMessage.ToolResponse("call-2", "weather", "sunny"),
                new ToolResponseMessage.ToolResponse("call-1", "KnowledgeTool", "release policy")
        );

        List<ToolResponseMessage.ToolResponse> sorted = ReflectionTestUtils.invokeMethod(
                agent, "sortResponses", calls, responses
        );

        assertThat(sorted).extracting(ToolResponseMessage.ToolResponse::id)
                .containsExactly("call-1", "call-2");
    }

    @Test
    void shouldRecordClarificationInsteadOfSuccessfulCompletion() {
        JChatMind agent = new JChatMind();
        ReflectionTestUtils.setField(agent, "currentIntentDecisionMetadata", IntentDecisionMetadata.from(
                "trace-1",
                "decision-1",
                new IntentRouter().decide("帮我处理一下这个"),
                List.of()
        ));
        ReflectionTestUtils.invokeMethod(agent, "finishWithoutToolCalls");

        assertThat(ReflectionTestUtils.getField(agent, "runEndReason"))
                .isEqualTo("CLARIFICATION_REQUIRED");
    }

    @Test
    void shouldRecordToolFailureWhenRunEndsAfterFailedResult() {
        JChatMind agent = new JChatMind();
        ReflectionTestUtils.setField(agent, "toolFailureObserved", true);

        ReflectionTestUtils.invokeMethod(agent, "finishWithoutToolCalls");

        assertThat(ReflectionTestUtils.getField(agent, "runEndReason")).isEqualTo("TOOL_FAILURE");
    }

    @Test
    void shouldCompleteWhenFinalBudgetTurnConsumesLastToolResult() {
        JChatMind agent = new JChatMind();
        ReflectionTestUtils.setField(agent, "sseService", recordingSseService(new ArrayList<>()));

        ReflectionTestUtils.invokeMethod(agent, "handleFinalBudgetDecision", false);

        assertThat(ReflectionTestUtils.getField(agent, "agentState")).isEqualTo(AgentState.FINISHED);
        assertThat(ReflectionTestUtils.getField(agent, "runEndReason")).isNull();
    }

    @Test
    void shouldExhaustBudgetWhenFinalBudgetTurnRequestsAnotherTool() {
        JChatMind agent = new JChatMind();
        List<SseMessage> events = new ArrayList<>();
        ReflectionTestUtils.setField(agent, "sseService", recordingSseService(events));

        ReflectionTestUtils.invokeMethod(agent, "handleFinalBudgetDecision", true);

        assertThat(ReflectionTestUtils.getField(agent, "agentState")).isEqualTo(AgentState.FINISHED);
        assertThat(ReflectionTestUtils.getField(agent, "runEndReason")).isEqualTo("BUDGET_EXHAUSTED");
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.getType()).isEqualTo(SseMessage.Type.AI_ERROR);
            assertThat(event.getPayload().getStatusText()).contains("尚未确认完成");
            assertThat(event.getPayload().getDone()).isFalse();
        });
    }

    @Test
    void shouldKeepHarnessRejectionDistinctFromToolFailure() {
        JChatMind agent = new JChatMind();

        String outcome = ReflectionTestUtils.invokeMethod(
                agent,
                "classifyToolOutcome",
                "[TOOL_DUPLICATE] duplicate request"
        );

        assertThat(outcome).isEqualTo("REJECTED");
    }

    private SseService recordingSseService(List<SseMessage> events) {
        return new SseService() {
            @Override
            public SseEmitter connect(String chatSessionId, Long lastEventId) {
                return null;
            }

            @Override
            public void send(String chatSessionId, SseMessage message) {
                events.add(message);
            }
        };
    }
}
