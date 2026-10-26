package com.kama.jchatmind.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class JChatMindToolTimeoutTest {

    @Test
    void shouldStopWhenToolExecutorHasNoCapacity() throws Exception {
        assertTerminalException(new java.util.concurrent.RejectedExecutionException(), "TOOL_EXECUTOR_BUSY");
    }

    @Test
    void shouldTreatExecutorShutdownCancellationAsUnconfirmed() throws Exception {
        assertTerminalException(new java.util.concurrent.ExecutionException(
                new java.util.concurrent.CancellationException()), "TOOL_OUTCOME_UNKNOWN");
    }

    private void assertTerminalException(Exception exception, String reason) throws Exception {
        AgentToolExecutor executor = mock(AgentToolExecutor.class);
        doThrow(exception).when(executor).execute(any(), any(), any(), any(), any(), any());
        JChatMind agent = new JChatMind();
        ReflectionTestUtils.setField(agent, "toolExecutor", executor);
        List<AssistantMessage.ToolCall> calls = List.of(
                new AssistantMessage.ToolCall("call-1", "function", "sendEmail", "{}")
        );
        ReflectionTestUtils.setField(agent, "lastChatResponse", new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content("").toolCalls(calls).build()
        ))));
        ReflectionTestUtils.invokeMethod(agent, "executeWithTimeout", new Prompt("test"), calls, List.of());
        assertThat(ReflectionTestUtils.getField(agent, "runEndReason")).isEqualTo(reason);
        assertThat(ReflectionTestUtils.getField(agent, "agentState")).isEqualTo(AgentState.FINISHED);
    }

    @Test
    void shouldInterruptTimedOutTaskAndMarkOutcomeUnconfirmed() throws Exception {
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ToolCallingManager manager = mock(ToolCallingManager.class);
        doAnswer(invocation -> {
            try {
                release.await();
            } catch (InterruptedException exception) {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }
            return null;
        }).when(manager).executeToolCalls(any(), any());
        AgentToolExecutor executor = new AgentToolExecutor();
        JChatMind agent = new JChatMind();
        ReflectionTestUtils.setField(agent, "toolExecutor", executor);
        ReflectionTestUtils.setField(agent, "toolCallingManager", manager);
        ReflectionTestUtils.setField(agent, "toolTimeoutSeconds", 1);
        List<AssistantMessage.ToolCall> calls = List.of(
                new AssistantMessage.ToolCall("call-1", "function", "sendEmail", "{}")
        );
        ReflectionTestUtils.setField(agent, "lastChatResponse", new ChatResponse(List.of(new Generation(
                AssistantMessage.builder().content("").toolCalls(calls).build()
        ))));
        try {
            ToolResponseMessage result = ReflectionTestUtils.invokeMethod(
                    agent, "executeWithTimeout", new Prompt("test"), calls, List.of()
            );
            assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(result.getResponses()).singleElement().satisfies(response ->
                    assertThat(response.responseData()).contains("结果未确认"));
            assertThat(ReflectionTestUtils.getField(agent, "runEndReason")).isEqualTo("TOOL_OUTCOME_UNKNOWN");
            assertThat(ReflectionTestUtils.getField(agent, "agentState")).isEqualTo(AgentState.FINISHED);
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }
}
