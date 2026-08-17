package com.kama.jchatmind.agent;

import com.kama.jchatmind.agent.harness.HarnessRunner;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.message.SseMessage;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.model.vo.ChatMessageVO;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.SseService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class JChatMindStreamingSseTest {

    @Test
    void shouldIgnoreEmptyResponseFramesAndEmitEveryAssistantTextChunkBeforePersistingFullMessage() {
        ChatClient chatClient = mock(ChatClient.class);
        ChatClient.ChatClientRequestSpec requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callResponseSpec = mock(ChatClient.CallResponseSpec.class);
        ChatClient.StreamResponseSpec streamResponseSpec = mock(ChatClient.StreamResponseSpec.class);
        SseService sseService = mock(SseService.class);
        ChatMessageFacadeService chatMessageFacadeService = mock(ChatMessageFacadeService.class);
        ChatMessageConverter chatMessageConverter = mock(ChatMessageConverter.class);
        CreateChatMessageResponse savedMessage = mock(CreateChatMessageResponse.class);

        when(chatClient.prompt(any(Prompt.class))).thenReturn(requestSpec);
        when(requestSpec.system(any(String.class))).thenReturn(requestSpec);
        when(requestSpec.toolCallbacks(any(ToolCallback[].class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.chatClientResponse()).thenReturn(new ChatClientResponse(
                chatResponse("完整回答"),
                Map.of()
        ));
        when(requestSpec.stream()).thenReturn(streamResponseSpec);
        when(streamResponseSpec.chatResponse()).thenReturn(Flux.just(
                new ChatResponse(List.of()),
                chatResponse("第一段"),
                chatResponse("第二段")
        ));
        when(chatMessageFacadeService.createChatMessage(any(ChatMessageDTO.class), eq("user-1")))
                .thenReturn(savedMessage);
        when(savedMessage.getChatMessageId()).thenReturn("assistant-message-1");
        when(chatMessageConverter.toVO(any(ChatMessageDTO.class))).thenReturn(mock(ChatMessageVO.class));

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
                chatMessageFacadeService,
                chatMessageConverter,
                mock(HarnessRunner.class)
        );

        agent.run();

        ArgumentCaptor<SseMessage> sseMessages = ArgumentCaptor.forClass(SseMessage.class);
        verify(sseService, org.mockito.Mockito.atLeastOnce()).send(eq("session-1"), sseMessages.capture());
        assertThat(sseMessages.getAllValues())
                .filteredOn(message -> message.getType() == SseMessage.Type.AI_CONTENT_DELTA)
                .extracting(message -> message.getPayload().getContentDelta())
                .containsExactly("第一段", "第二段");

        ArgumentCaptor<ChatMessageDTO> persistedMessages = ArgumentCaptor.forClass(ChatMessageDTO.class);
        verify(chatMessageFacadeService).createChatMessage(persistedMessages.capture(), eq("user-1"));
        assertThat(persistedMessages.getValue().getContent()).isEqualTo("第一段第二段");
    }

    private ChatResponse chatResponse(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }
}
