package com.kama.jchatmind.agent;

import com.kama.jchatmind.agent.harness.interceptor.HarnessInterceptorChain;
import com.kama.jchatmind.agent.harness.proxy.HarnessExecutionContextHolder;
import com.kama.jchatmind.agent.harness.proxy.HarnessToolCallbackProxy;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentToolExecutorTest {

    @Test
    void shouldPreserveCancellationWhenInterruptedToolThrowsAnotherException() throws Exception {
        AgentToolExecutor executor = new AgentToolExecutor();
        var caller = java.util.concurrent.Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ToolCallingManager manager = mock(ToolCallingManager.class);
        doAnswer(invocation -> {
            started.countDown();
            try {
                release.await();
                return null;
            } catch (InterruptedException exception) {
                throw new IllegalStateException("interrupted transport", exception);
            }
        }).when(manager).executeToolCalls(any(), any());
        try {
            var result = caller.submit(() -> executor.execute(manager, new Prompt("test"), null, List.of(),
                    new HarnessExecutionContextHolder.BatchMetadata("session", "agent", "user", 1),
                    Duration.ofSeconds(5)));
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();
            assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(CancellationException.class);
        } finally {
            release.countDown();
            executor.shutdown();
            caller.shutdownNow();
        }
    }

    @Test
    void shouldRejectExcessWorkWithoutInvokingToolManager() throws Exception {
        AgentToolExecutor executor = new AgentToolExecutor();
        var callers = java.util.concurrent.Executors.newFixedThreadPool(4);
        CountDownLatch started = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger executions = new AtomicInteger();
        ToolCallingManager manager = mock(ToolCallingManager.class);
        var response = org.springframework.ai.chat.messages.ToolResponseMessage.builder()
                .responses(List.of()).build();
        var result = mock(org.springframework.ai.model.tool.ToolExecutionResult.class);
        when(result.conversationHistory()).thenReturn(List.of(response));
        doAnswer(invocation -> {
            executions.incrementAndGet();
            started.countDown();
            release.await();
            return result;
        }).when(manager).executeToolCalls(any(), any());
        java.util.concurrent.Callable<org.springframework.ai.chat.messages.ToolResponseMessage> call =
                () -> executor.execute(manager, new Prompt("test"), null, List.of(),
                        new HarnessExecutionContextHolder.BatchMetadata("session", "agent", "user", 1),
                        Duration.ofSeconds(5));
        try {
            var running = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 4; i++) {
                running.add(callers.submit(call));
            }
            assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(call::call).isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
            assertThat(executions).hasValue(4);
            release.countDown();
            for (var task : running) {
                assertThat(task.get(3, TimeUnit.SECONDS)).isSameAs(response);
            }
        } finally {
            release.countDown();
            callers.shutdownNow();
            executor.shutdown();
        }
    }

    @Test
    void shouldCancelActiveBatchWhenExecutorShutsDown() throws Exception {
        AgentToolExecutor executor = new AgentToolExecutor();
        var caller = java.util.concurrent.Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger laterCalls = new AtomicInteger();
        var chain = new HarnessInterceptorChain(List.of());
        ToolCallback slow = new HarnessToolCallbackProxy(callback("slow", () -> {
            started.countDown();
            while (release.getCount() != 0) {
                try {
                    release.await();
                } catch (InterruptedException ignored) {
                }
            }
        }), chain);
        ToolCallback later = new HarnessToolCallbackProxy(callback("later", laterCalls::incrementAndGet), chain);
        ToolCallingManager manager = mock(ToolCallingManager.class);
        doAnswer(invocation -> {
            try {
                slow.call("{}");
            } catch (CancellationException ignored) {
            }
            later.call("{}");
            return null;
        }).when(manager).executeToolCalls(any(), any());
        try {
            var result = caller.submit(() -> executor.execute(manager, new Prompt("test"), null, List.of(),
                    new HarnessExecutionContextHolder.BatchMetadata("session", "agent", "user", 1),
                    Duration.ofSeconds(5)));
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            executor.shutdown();
            release.countDown();
            assertThatThrownBy(() -> result.get(2, TimeUnit.SECONDS))
                    .hasRootCauseInstanceOf(CancellationException.class);
            assertThat(laterCalls).hasValue(0);
        } finally {
            release.countDown();
            executor.shutdown();
            caller.shutdownNow();
        }
    }

    @Test
    void shouldBlockRemainingCallbacksEvenWhenRunningToolIgnoresInterrupt() throws Exception {
        AgentToolExecutor executor = new AgentToolExecutor();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicInteger laterCalls = new AtomicInteger();
        AtomicInteger cancellations = new AtomicInteger();
        HarnessInterceptorChain chain = new HarnessInterceptorChain(List.of());
        ToolCallback slow = new HarnessToolCallbackProxy(callback("slow", () -> {
            boolean released = false;
            while (!released) {
                try {
                    release.await();
                    released = true;
                } catch (InterruptedException ignored) {
                    // Reproduce a library that swallows interruption.
                }
            }
        }), chain);
        ToolCallback later = new HarnessToolCallbackProxy(callback("later", laterCalls::incrementAndGet), chain);
        ToolCallingManager manager = mock(ToolCallingManager.class);
        doAnswer(invocation -> {
            try {
                for (ToolCallback callback : List.of(slow, later)) {
                    try {
                        callback.call("{}");
                    } catch (CancellationException exception) {
                        cancellations.incrementAndGet();
                    }
                }
                return null;
            } finally {
                finished.countDown();
            }
        }).when(manager).executeToolCalls(any(), any());
        try {
            assertThatThrownBy(() -> executor.execute(manager, new Prompt("test"), null, List.of(),
                    new HarnessExecutionContextHolder.BatchMetadata("session", "agent", "user", 1),
                    Duration.ofSeconds(1))).isInstanceOf(TimeoutException.class);
            release.countDown();
            assertThat(finished.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(laterCalls).hasValue(0);
            assertThat(cancellations).hasValue(2);
        } finally {
            release.countDown();
            executor.shutdown();
        }
    }

    private ToolCallback callback(String name, Runnable action) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder().name(name).description(name).inputSchema("{}").build();
            }

            @Override
            public String call(String input) {
                action.run();
                return "done";
            }
        };
    }
}
