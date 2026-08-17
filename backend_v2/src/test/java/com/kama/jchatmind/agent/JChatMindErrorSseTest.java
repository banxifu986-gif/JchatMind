package com.kama.jchatmind.agent;

import com.kama.jchatmind.agent.harness.HarnessRunner;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.message.SseMessage;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.SseService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JChatMindErrorSseTest {

    @Test
    void shouldSendErrorStatusWhenChatClientFails() {
        ChatClient chatClient = mock(ChatClient.class);
        SseService sseService = mock(SseService.class);
        when(chatClient.prompt(any(Prompt.class)))
                .thenThrow(new IllegalStateException("model unavailable"));

        JChatMind agent = new JChatMind(
                "user-1",
                "agent-1",
                "test agent",
                "",
                "",
                chatClient,
                10,
                List.of(),
                List.of(),
                List.of(),
                "session-1",
                sseService,
                mock(ChatMessageFacadeService.class),
                mock(ChatMessageConverter.class),
                mock(HarnessRunner.class)
        );

        assertThatThrownBy(agent::run)
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Error running agent");

        ArgumentCaptor<SseMessage> messages = ArgumentCaptor.forClass(SseMessage.class);
        verify(sseService, times(3)).send(eq("session-1"), messages.capture());
        assertThat(messages.getAllValues())
                .extracting(SseMessage::getType)
                .containsExactly(
                        SseMessage.Type.AI_PLANNING,
                        SseMessage.Type.AI_THINKING,
                        SseMessage.Type.AI_ERROR
                );
    }
}
