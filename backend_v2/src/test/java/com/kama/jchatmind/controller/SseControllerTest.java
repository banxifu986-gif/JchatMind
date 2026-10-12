package com.kama.jchatmind.controller;

import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.service.ChatSessionFacadeService;
import com.kama.jchatmind.service.SseService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SseControllerTest {

    @Test
    void shouldRejectConnectionWithoutAuthenticatedUser() {
        RequestScopeData requestScopeData = new RequestScopeData();
        ChatSessionFacadeService chatSessionFacadeService = mock(ChatSessionFacadeService.class);
        SseService sseService = mock(SseService.class);
        SseController controller = new SseController(
                requestScopeData,
                chatSessionFacadeService,
                sseService
        );

        var response = controller.connect("session-1", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(chatSessionFacadeService, sseService);
    }

    @Test
    void shouldRejectConnectionWhenSessionDoesNotBelongToUser() {
        RequestScopeData requestScopeData = new RequestScopeData();
        requestScopeData.setLogin(true);
        requestScopeData.setUserId(7L);
        ChatSessionFacadeService chatSessionFacadeService = mock(ChatSessionFacadeService.class);
        SseService sseService = mock(SseService.class);
        when(chatSessionFacadeService.getChatSession("session-1", "7"))
                .thenThrow(new BizException("聊天会话不存在"));
        SseController controller = new SseController(
                requestScopeData,
                chatSessionFacadeService,
                sseService
        );

        var response = controller.connect("session-1", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        verify(sseService, never()).connect("session-1", null);
    }

    @Test
    void shouldConnectAfterOwnerValidation() {
        RequestScopeData requestScopeData = new RequestScopeData();
        requestScopeData.setLogin(true);
        requestScopeData.setUserId(7L);
        ChatSessionFacadeService chatSessionFacadeService = mock(ChatSessionFacadeService.class);
        SseService sseService = mock(SseService.class);
        SseEmitter emitter = new SseEmitter();
        when(chatSessionFacadeService.getChatSession("session-1", "7")).thenReturn(null);
        when(sseService.connect("session-1", 17L)).thenReturn(emitter);
        SseController controller = new SseController(
                requestScopeData,
                chatSessionFacadeService,
                sseService
        );

        var response = controller.connect("session-1", 17L);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isSameAs(emitter);
        verify(chatSessionFacadeService).getChatSession("session-1", "7");
        verify(sseService).connect("session-1", 17L);
    }
}
