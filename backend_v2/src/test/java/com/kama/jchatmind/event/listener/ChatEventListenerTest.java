package com.kama.jchatmind.event.listener;

import com.kama.jchatmind.agent.JChatMind;
import com.kama.jchatmind.agent.JChatMindFactory;
import com.kama.jchatmind.event.ChatEvent;
import com.kama.jchatmind.service.UserMemoryFacadeService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatEventListenerTest {

    @Test
    void shouldRunAgentThenExtractMemoryCandidatesForPublishedChatEvent() {
        JChatMindFactory jChatMindFactory = mock(JChatMindFactory.class);
        UserMemoryFacadeService userMemoryFacadeService = mock(UserMemoryFacadeService.class);
        JChatMind agent = mock(JChatMind.class);
        ChatEventListener listener = new ChatEventListener(jChatMindFactory, userMemoryFacadeService);
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
        ChatEventListener listener = new ChatEventListener(jChatMindFactory, userMemoryFacadeService);
        ChatEvent event = new ChatEvent("7", "agent-1", "session-1", "请总结这段内容");
        when(jChatMindFactory.create("7", "agent-1", "session-1")).thenReturn(agent);
        doThrow(new IllegalStateException("model unavailable")).when(agent).run();

        assertThatThrownBy(() -> listener.handle(event))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("model unavailable");

        verify(userMemoryFacadeService).extractMemoryCandidates("7", "session-1");
    }
}
