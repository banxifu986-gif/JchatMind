package com.kama.jchatmind.event.listener;

import com.kama.jchatmind.agent.JChatMind;
import com.kama.jchatmind.agent.JChatMindFactory;
import com.kama.jchatmind.event.ChatEvent;
import com.kama.jchatmind.service.MemoryExtractionResult;
import com.kama.jchatmind.service.UserMemoryFacadeService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.core.task.TaskRejectedException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatEventListenerTest {

    private static final Executor DIRECT_EXECUTOR = Runnable::run;

    @Test
    void shouldRecordMemoryExtractionFailureWithoutInterruptingAgentProcessing() {
        JChatMindFactory jChatMindFactory = mock(JChatMindFactory.class);
        UserMemoryFacadeService userMemoryFacadeService = mock(UserMemoryFacadeService.class);
        JChatMind agent = mock(JChatMind.class);
        MemoryExtractionFailureRegistry failureRegistry = new MemoryExtractionFailureRegistry();
        ChatEventListener listener = new ChatEventListener(
                jChatMindFactory,
                userMemoryFacadeService,
                new ChatSessionExecutionCoordinator(),
                failureRegistry,
                DIRECT_EXECUTOR
        );
        ChatEvent event = new ChatEvent("7", "agent-1", "session-1", "请总结这段内容");
        when(jChatMindFactory.create("7", "agent-1", "session-1")).thenReturn(agent);
        when(userMemoryFacadeService.extractMemoryCandidates("7", "session-1"))
                .thenThrow(new IllegalStateException("internal memory content"));

        assertThatCode(() -> listener.handle(event)).doesNotThrowAnyException();

        verify(agent).run();
        assertThat(failureRegistry.getFailure("7", "session-1"))
                .hasValueSatisfying(failure -> {
                    assertThat(failure.errorType()).isEqualTo(IllegalStateException.class.getName());
                    assertThat(failure.failureCount()).isEqualTo(1);
                });
    }

    @Test
    void shouldPreserveAgentFailureWhenMemoryExtractionAlsoFails() {
        JChatMindFactory jChatMindFactory = mock(JChatMindFactory.class);
        UserMemoryFacadeService userMemoryFacadeService = mock(UserMemoryFacadeService.class);
        JChatMind agent = mock(JChatMind.class);
        MemoryExtractionFailureRegistry failureRegistry = new MemoryExtractionFailureRegistry();
        ChatEventListener listener = new ChatEventListener(
                jChatMindFactory,
                userMemoryFacadeService,
                new ChatSessionExecutionCoordinator(),
                failureRegistry,
                DIRECT_EXECUTOR
        );
        ChatEvent event = new ChatEvent("7", "agent-1", "session-1", "请总结这段内容");
        when(jChatMindFactory.create("7", "agent-1", "session-1")).thenReturn(agent);
        doThrow(new IllegalStateException("model unavailable")).when(agent).run();
        when(userMemoryFacadeService.extractMemoryCandidates("7", "session-1"))
                .thenThrow(new IllegalArgumentException("memory unavailable"));

        assertThatThrownBy(() -> listener.handle(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("model unavailable");

        assertThat(failureRegistry.getFailure("7", "session-1"))
                .hasValueSatisfying(failure -> assertThat(failure.errorType())
                        .isEqualTo(IllegalArgumentException.class.getName()));
    }

    @Test
    void shouldClearMemoryExtractionFailureOnlyAfterActualExtraction() {
        JChatMindFactory jChatMindFactory = mock(JChatMindFactory.class);
        UserMemoryFacadeService userMemoryFacadeService = mock(UserMemoryFacadeService.class);
        JChatMind agent = mock(JChatMind.class);
        MemoryExtractionFailureRegistry failureRegistry = new MemoryExtractionFailureRegistry();
        ChatEventListener listener = new ChatEventListener(
                jChatMindFactory,
                userMemoryFacadeService,
                new ChatSessionExecutionCoordinator(),
                failureRegistry,
                DIRECT_EXECUTOR
        );
        ChatEvent event = new ChatEvent("7", "agent-1", "session-1", "请总结这段内容");
        when(jChatMindFactory.create("7", "agent-1", "session-1")).thenReturn(agent);
        when(userMemoryFacadeService.extractMemoryCandidates("7", "session-1"))
                .thenThrow(new IllegalStateException("first failure"))
                .thenReturn(MemoryExtractionResult.EXTRACTED);

        listener.handle(event);
        listener.handle(event);

        assertThat(failureRegistry.getFailure("7", "session-1")).isEmpty();
    }

    @Test
    void shouldKeepMemoryExtractionFailureWhenLaterEventIsSkipped() {
        JChatMindFactory jChatMindFactory = mock(JChatMindFactory.class);
        UserMemoryFacadeService userMemoryFacadeService = mock(UserMemoryFacadeService.class);
        JChatMind agent = mock(JChatMind.class);
        MemoryExtractionFailureRegistry failureRegistry = new MemoryExtractionFailureRegistry();
        ChatEventListener listener = new ChatEventListener(
                jChatMindFactory,
                userMemoryFacadeService,
                new ChatSessionExecutionCoordinator(),
                failureRegistry,
                DIRECT_EXECUTOR
        );
        ChatEvent event = new ChatEvent("7", "agent-1", "session-1", "请总结这段内容");
        when(jChatMindFactory.create("7", "agent-1", "session-1")).thenReturn(agent);
        when(userMemoryFacadeService.extractMemoryCandidates("7", "session-1"))
                .thenThrow(new IllegalStateException("first failure"))
                .thenReturn(MemoryExtractionResult.SKIPPED);

        listener.handle(event);
        listener.handle(event);

        assertThat(failureRegistry.getFailure("7", "session-1"))
                .hasValueSatisfying(failure -> assertThat(failure.failureCount()).isEqualTo(1));
    }

    @Test
    void shouldRunAgentThenExtractMemoryCandidatesForPublishedChatEvent() {
        JChatMindFactory jChatMindFactory = mock(JChatMindFactory.class);
        UserMemoryFacadeService userMemoryFacadeService = mock(UserMemoryFacadeService.class);
        JChatMind agent = mock(JChatMind.class);
        ChatEventListener listener = new ChatEventListener(
                jChatMindFactory,
                userMemoryFacadeService,
                new ChatSessionExecutionCoordinator(),
                new MemoryExtractionFailureRegistry(),
                DIRECT_EXECUTOR
        );
        ChatEvent event = new ChatEvent("7", "agent-1", "session-1", "请总结这段内容");
        when(jChatMindFactory.create("7", "agent-1", "session-1")).thenReturn(agent);

        listener.handle(event);

        InOrder inOrder = inOrder(agent, userMemoryFacadeService);
        inOrder.verify(agent).run();
        inOrder.verify(userMemoryFacadeService).extractMemoryCandidates("7", "session-1");
    }

    @Test
    void shouldStillExtractMemoryCandidatesWhenAgentRunFails() {
        JChatMindFactory jChatMindFactory = mock(JChatMindFactory.class);
        UserMemoryFacadeService userMemoryFacadeService = mock(UserMemoryFacadeService.class);
        JChatMind agent = mock(JChatMind.class);
        ChatEventListener listener = new ChatEventListener(
                jChatMindFactory,
                userMemoryFacadeService,
                new ChatSessionExecutionCoordinator(),
                new MemoryExtractionFailureRegistry(),
                DIRECT_EXECUTOR
        );
        ChatEvent event = new ChatEvent("7", "agent-1", "session-1", "请总结这段内容");
        when(jChatMindFactory.create("7", "agent-1", "session-1")).thenReturn(agent);
        doThrow(new IllegalStateException("model unavailable")).when(agent).run();

        assertThatThrownBy(() -> listener.handle(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("model unavailable");

        verify(userMemoryFacadeService).extractMemoryCandidates("7", "session-1");
    }

    @Test
    void shouldPreserveEventPublicationOrderWhenExecutorStartsLaterEventFirst() throws Exception {
        JChatMindFactory jChatMindFactory = mock(JChatMindFactory.class);
        UserMemoryFacadeService userMemoryFacadeService = mock(UserMemoryFacadeService.class);
        ChatSessionExecutionCoordinator coordinator = new ChatSessionExecutionCoordinator();
        JChatMind firstAgent = mock(JChatMind.class);
        JChatMind secondAgent = mock(JChatMind.class);
        List<Runnable> queuedTasks = new ArrayList<>();
        List<String> executionOrder = new ArrayList<>();
        Executor queuedExecutor = queuedTasks::add;
        ChatEventListener listener = new ChatEventListener(
                jChatMindFactory,
                userMemoryFacadeService,
                coordinator,
                new MemoryExtractionFailureRegistry(),
                queuedExecutor
        );
        ChatEvent firstEvent = new ChatEvent("7", "agent-1", "session-1", "第一条消息");
        ChatEvent secondEvent = new ChatEvent("7", "agent-2", "session-1", "第二条消息");
        doAnswer(invocation -> {
            String agentId = invocation.getArgument(1);
            executionOrder.add("create-" + agentId);
            return "agent-1".equals(agentId) ? firstAgent : secondAgent;
        }).when(jChatMindFactory).create(anyString(), anyString(), anyString());
        doAnswer(invocation -> {
            executionOrder.add("run-agent-1");
            return null;
        }).when(firstAgent).run();
        doAnswer(invocation -> {
            executionOrder.add("run-agent-2");
            return null;
        }).when(secondAgent).run();
        doAnswer(invocation -> {
            executionOrder.add("extract");
            return MemoryExtractionResult.SKIPPED;
        }).when(userMemoryFacadeService).extractMemoryCandidates("7", "session-1");

        listener.handle(firstEvent);
        listener.handle(secondEvent);
        assertThat(queuedTasks).hasSize(2);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch laterTaskStarted = new CountDownLatch(1);
        Future<?> laterTask = executor.submit(() -> {
            laterTaskStarted.countDown();
            queuedTasks.get(1).run();
        });
        try {
            assertThat(laterTaskStarted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> laterTask.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);

            queuedTasks.get(0).run();
            laterTask.get(1, TimeUnit.SECONDS);

            assertThat(executionOrder).containsExactly(
                    "create-agent-1", "run-agent-1", "extract",
                    "create-agent-2", "run-agent-2", "extract"
            );
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldCancelReservationWhenAgentExecutorRejectsAnEvent() {
        JChatMindFactory jChatMindFactory = mock(JChatMindFactory.class);
        UserMemoryFacadeService userMemoryFacadeService = mock(UserMemoryFacadeService.class);
        ChatSessionExecutionCoordinator coordinator = new ChatSessionExecutionCoordinator();
        JChatMind agent = mock(JChatMind.class);
        List<Runnable> acceptedTasks = new ArrayList<>();
        AtomicInteger submissions = new AtomicInteger();
        Executor executor = task -> {
            if (submissions.getAndIncrement() < 2) {
                throw new TaskRejectedException("agent queue is full");
            }
            acceptedTasks.add(task);
        };
        ChatEventListener listener = new ChatEventListener(
                jChatMindFactory,
                userMemoryFacadeService,
                coordinator,
                new MemoryExtractionFailureRegistry(),
                executor
        );
        when(jChatMindFactory.create("7", "agent-2", "session-1")).thenReturn(agent);
        when(userMemoryFacadeService.extractMemoryCandidates("7", "session-1"))
                .thenReturn(MemoryExtractionResult.SKIPPED);

        assertThatThrownBy(() -> listener.handle(
                new ChatEvent("7", "agent-1", "session-1", "被拒绝的消息")
        )).isInstanceOf(TaskRejectedException.class);
        assertThat(listener.getRejectedTaskCount()).isEqualTo(2);
        assertThat(acceptedTasks).isEmpty();

        listener.handle(new ChatEvent("7", "agent-2", "session-1", "后续消息"));
        acceptedTasks.get(0).run();

        verify(agent).run();
        verify(jChatMindFactory).create("7", "agent-2", "session-1");
        assertThat(listener.getRejectedTaskCount()).isEqualTo(2);
    }

    @Test
    void shouldRetryOnceWhenAgentExecutorRejectionIsTransient() {
        JChatMindFactory jChatMindFactory = mock(JChatMindFactory.class);
        UserMemoryFacadeService userMemoryFacadeService = mock(UserMemoryFacadeService.class);
        List<Runnable> acceptedTasks = new ArrayList<>();
        AtomicInteger submissions = new AtomicInteger();
        Executor executor = task -> {
            if (submissions.getAndIncrement() == 0) {
                throw new TaskRejectedException("transient queue saturation");
            }
            acceptedTasks.add(task);
        };
        JChatMind agent = mock(JChatMind.class);
        ChatEventListener listener = new ChatEventListener(
                jChatMindFactory,
                userMemoryFacadeService,
                new ChatSessionExecutionCoordinator(),
                new MemoryExtractionFailureRegistry(),
                executor
        );
        when(jChatMindFactory.create("7", "agent-1", "session-1")).thenReturn(agent);
        when(userMemoryFacadeService.extractMemoryCandidates("7", "session-1"))
                .thenReturn(MemoryExtractionResult.SKIPPED);

        listener.handle(new ChatEvent("7", "agent-1", "session-1", "临时拒绝后重试"));
        assertThat(listener.getRejectedTaskCount()).isEqualTo(1);
        assertThat(acceptedTasks).hasSize(1);

        acceptedTasks.get(0).run();

        verify(agent).run();
    }
}
