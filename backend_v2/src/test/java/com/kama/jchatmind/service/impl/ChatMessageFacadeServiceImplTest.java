package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.event.ChatEvent;
import com.kama.jchatmind.mapper.ChatMessageMapper;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.entity.ChatMessage;
import com.kama.jchatmind.model.request.CreateChatMessageRequest;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.model.response.GetChatMessagesResponse;
import com.kama.jchatmind.model.vo.ChatMessageVO;
import com.kama.jchatmind.service.ChatSessionFacadeService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatMessageFacadeServiceImplTest {

    @Test
    void shouldPersistOwnedUserMessageAndPublishMatchingChatEvent() throws Exception {
        ChatMessageMapper chatMessageMapper = mock(ChatMessageMapper.class);
        ChatMessageConverter chatMessageConverter = mock(ChatMessageConverter.class);
        ChatSessionFacadeService chatSessionFacadeService = mock(ChatSessionFacadeService.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        RequestScopeData requestScopeData = requestScopeData(7L);
        ChatMessageFacadeServiceImpl service = new ChatMessageFacadeServiceImpl(
                chatMessageMapper,
                chatMessageConverter,
                chatSessionFacadeService,
                publisher,
                requestScopeData
        );
        CreateChatMessageRequest request = CreateChatMessageRequest.builder()
                .agentId("agent-1")
                .sessionId("session-1")
                .role(ChatMessageDTO.RoleType.USER)
                .content("请总结这段内容")
                .build();
        ChatMessageDTO messageDTO = ChatMessageDTO.builder()
                .sessionId("session-1")
                .role(ChatMessageDTO.RoleType.USER)
                .content("请总结这段内容")
                .build();
        ChatMessage persistedMessage = ChatMessage.builder()
                .id("message-1")
                .sessionId("session-1")
                .role("user")
                .content("请总结这段内容")
                .build();
        when(chatMessageConverter.toDTO(request)).thenReturn(messageDTO);
        when(chatMessageConverter.toEntity(messageDTO)).thenReturn(persistedMessage);
        when(chatMessageMapper.insert(persistedMessage)).thenReturn(1);

        CreateChatMessageResponse response = service.createChatMessage(request);

        assertThat(response.getChatMessageId()).isEqualTo("message-1");
        verify(chatSessionFacadeService).getChatSession("session-1");
        verify(chatMessageMapper).insert(persistedMessage);
        ArgumentCaptor<ChatEvent> eventCaptor = ArgumentCaptor.forClass(ChatEvent.class);
        verify(publisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue())
                .extracting(ChatEvent::getUserId, ChatEvent::getAgentId, ChatEvent::getSessionId, ChatEvent::getUserInput)
                .containsExactly("7", "agent-1", "session-1", "请总结这段内容");
    }

    @Test
    void shouldReturnHistoryInMapperOrderAfterCheckingSessionOwnership() throws Exception {
        ChatMessageMapper chatMessageMapper = mock(ChatMessageMapper.class);
        ChatMessageConverter chatMessageConverter = mock(ChatMessageConverter.class);
        ChatSessionFacadeService chatSessionFacadeService = mock(ChatSessionFacadeService.class);
        ChatMessageFacadeServiceImpl service = new ChatMessageFacadeServiceImpl(
                chatMessageMapper,
                chatMessageConverter,
                chatSessionFacadeService,
                mock(ApplicationEventPublisher.class),
                requestScopeData(7L)
        );
        ChatMessage firstMessage = ChatMessage.builder().id("message-1").sessionId("session-1").build();
        ChatMessage secondMessage = ChatMessage.builder().id("message-2").sessionId("session-1").build();
        ChatMessageVO firstVO = ChatMessageVO.builder().id("message-1").content("用户提问").build();
        ChatMessageVO secondVO = ChatMessageVO.builder().id("message-2").content("Agent 回答").build();
        when(chatMessageMapper.selectBySessionId("session-1")).thenReturn(List.of(firstMessage, secondMessage));
        when(chatMessageConverter.toVO(firstMessage)).thenReturn(firstVO);
        when(chatMessageConverter.toVO(secondMessage)).thenReturn(secondVO);

        GetChatMessagesResponse response = service.getChatMessagesBySessionId("session-1");

        verify(chatSessionFacadeService).getChatSession("session-1");
        assertThat(response.getChatMessages()).containsExactly(firstVO, secondVO);
    }

    private RequestScopeData requestScopeData(long userId) {
        RequestScopeData requestScopeData = new RequestScopeData();
        requestScopeData.setUserId(userId);
        return requestScopeData;
    }
}
