package com.kama.jchatmind.event.listener;

import com.kama.jchatmind.agent.JChatMind;
import com.kama.jchatmind.agent.JChatMindFactory;
import com.kama.jchatmind.event.ChatEvent;
import com.kama.jchatmind.service.MemoryExtractionResult;
import com.kama.jchatmind.service.UserMemoryFacadeService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatEventListenerTest {

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
                failureRegistry
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
                failureRegistry
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
                failureRegistry
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
                failureRegistry
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
                new MemoryExtractionFailureRegistry()
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
                new MemoryExtractionFailureRegistry()
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
    void shouldRunPublishedChatEventThroughSessionCoordinator() {
        JChatMindFactory jChatMindFactory = mock(JChatMindFactory.class);
        UserMemoryFacadeService userMemoryFacadeService = mock(UserMemoryFacadeService.class);
        ChatSessionExecutionCoordinator coordinator = mock(ChatSessionExecutionCoordinator.class);
        JChatMind agent = mock(JChatMind.class);
        ChatEventListener listener = new ChatEventListener(
                jChatMindFactory,
                userMemoryFacadeService,
                coordinator,
                new MemoryExtractionFailureRegistry()
        );
        ChatEvent event = new ChatEvent("7", "agent-1", "session-1", "请总结这段内容");
        when(jChatMindFactory.create("7", "agent-1", "session-1")).thenReturn(agent);
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(1)).run();
            return null;
        }).when(coordinator).execute(eq("session-1"), any(Runnable.class));

        listener.handle(event);

        InOrder inOrder = inOrder(coordinator, agent, userMemoryFacadeService);
        inOrder.verify(coordinator).execute(eq("session-1"), any(Runnable.class));
        inOrder.verify(agent).run();
        inOrder.verify(userMemoryFacadeService).extractMemoryCandidates("7", "session-1");
    }
}
