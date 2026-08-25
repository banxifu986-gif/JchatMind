package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.converter.ChatSessionConverter;
import com.kama.jchatmind.event.ChatSessionDeletedEvent;
import com.kama.jchatmind.event.listener.ChatSessionExecutionCoordinator;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.ChatSessionMapper;
import com.kama.jchatmind.model.entity.ChatSession;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ChatSessionFacadeServiceImplTest {

    @Test
    void shouldPublishSessionDeletionEventAfterOwnedSessionIsDeleted() {
        ChatSessionMapper chatSessionMapper = mock(ChatSessionMapper.class);
        when(chatSessionMapper.selectByIdAndUserId("session-1", "7"))
                .thenReturn(ChatSession.builder().id("session-1").userId("7").build());
        when(chatSessionMapper.deleteById("session-1")).thenReturn(1);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        ChatSessionExecutionCoordinator coordinator = coordinatorThatRunsTasks();
        ChatSessionFacadeServiceImpl service = service(chatSessionMapper, eventPublisher, coordinator);

        service.deleteChatSession("session-1");

        verify(coordinator).execute(org.mockito.ArgumentMatchers.eq("session-1"), org.mockito.ArgumentMatchers.any());
        ArgumentCaptor<ChatSessionDeletedEvent> eventCaptor = ArgumentCaptor.forClass(ChatSessionDeletedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().sessionId()).isEqualTo("session-1");
    }

    @Test
    void shouldNotPublishSessionDeletionEventWhenDeleteFails() {
        ChatSessionMapper chatSessionMapper = mock(ChatSessionMapper.class);
        when(chatSessionMapper.selectByIdAndUserId("session-1", "7"))
                .thenReturn(ChatSession.builder().id("session-1").userId("7").build());
        when(chatSessionMapper.deleteById("session-1")).thenReturn(0);
        ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
        ChatSessionFacadeServiceImpl service = service(chatSessionMapper, eventPublisher, coordinatorThatRunsTasks());

        assertThatThrownBy(() -> service.deleteChatSession("session-1"))
                .isInstanceOf(BizException.class)
                .hasMessage("删除聊天会话失败");

        verifyNoInteractions(eventPublisher);
    }

    private ChatSessionFacadeServiceImpl service(
            ChatSessionMapper chatSessionMapper,
            ApplicationEventPublisher eventPublisher,
            ChatSessionExecutionCoordinator coordinator
    ) {
        RequestScopeData requestScopeData = new RequestScopeData();
        requestScopeData.setUserId(7L);
        return new ChatSessionFacadeServiceImpl(
                chatSessionMapper,
                mock(ChatSessionConverter.class),
                requestScopeData,
                coordinator,
                eventPublisher
        );
    }

    private ChatSessionExecutionCoordinator coordinatorThatRunsTasks() {
        ChatSessionExecutionCoordinator coordinator = mock(ChatSessionExecutionCoordinator.class);
        doAnswer(invocation -> {
            invocation.getArgument(1, Runnable.class).run();
            return null;
        }).when(coordinator).execute(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
        return coordinator;
    }
}
