package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.agent.AgentExecutionRegistry;
import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.ChatSessionMapper;
import com.kama.jchatmind.model.entity.ChatSession;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * spec 场景 4 验收（服务层）：非本人会话 stop → 403 语义；不存在 → 400；
 * 无运行中 agent → 幂等成功。
 */
class ChatSessionStopExecutionTest {

    @Test
    void shouldReturn403WhenSessionNotOwnedByCurrentUser() {
        ChatSessionMapper mapper = mock(ChatSessionMapper.class);
        AgentExecutionRegistry registry = mock(AgentExecutionRegistry.class);
        RequestScopeData requestScopeData = mock(RequestScopeData.class);
        when(requestScopeData.getUserId()).thenReturn(7L);
        ChatSession foreignSession = ChatSession.builder().userId("99").build();
        when(mapper.selectById("session-1")).thenReturn(foreignSession);

        ChatSessionFacadeServiceImpl service = new ChatSessionFacadeServiceImpl(
                mapper, mock(com.kama.jchatmind.converter.ChatSessionConverter.class),
                requestScopeData, mock(com.kama.jchatmind.event.listener.ChatSessionExecutionCoordinator.class),
                mock(org.springframework.context.ApplicationEventPublisher.class),
                registry);

        BizException exception = assertThrows(BizException.class, () -> service.stopExecution("session-1"));

        assertEquals(403, exception.getCode());
        verify(registry, never()).stop(anyString());
    }

    @Test
    void shouldReturn400WhenSessionNotExists() {
        ChatSessionMapper mapper = mock(ChatSessionMapper.class);
        AgentExecutionRegistry registry = mock(AgentExecutionRegistry.class);
        RequestScopeData requestScopeData = mock(RequestScopeData.class);
        when(requestScopeData.getUserId()).thenReturn(7L);
        when(mapper.selectById("missing")).thenReturn(null);

        ChatSessionFacadeServiceImpl service = new ChatSessionFacadeServiceImpl(
                mapper, mock(com.kama.jchatmind.converter.ChatSessionConverter.class),
                requestScopeData, mock(com.kama.jchatmind.event.listener.ChatSessionExecutionCoordinator.class),
                mock(org.springframework.context.ApplicationEventPublisher.class),
                registry);

        BizException exception = assertThrows(BizException.class, () -> service.stopExecution("missing"));

        assertEquals(400, exception.getCode());
    }

    @Test
    void shouldBeIdempotentWhenNoRunningAgent() {
        ChatSessionMapper mapper = mock(ChatSessionMapper.class);
        AgentExecutionRegistry registry = mock(AgentExecutionRegistry.class);
        RequestScopeData requestScopeData = mock(RequestScopeData.class);
        when(requestScopeData.getUserId()).thenReturn(7L);
        ChatSession ownedSession = ChatSession.builder().userId("7").build();
        when(mapper.selectById("session-1")).thenReturn(ownedSession);
        when(registry.stop("session-1")).thenReturn(false);

        ChatSessionFacadeServiceImpl service = new ChatSessionFacadeServiceImpl(
                mapper, mock(com.kama.jchatmind.converter.ChatSessionConverter.class),
                requestScopeData, mock(com.kama.jchatmind.event.listener.ChatSessionExecutionCoordinator.class),
                mock(org.springframework.context.ApplicationEventPublisher.class),
                registry);

        assertDoesNotThrow(() -> service.stopExecution("session-1"));
        verify(registry).stop("session-1");
    }
}
