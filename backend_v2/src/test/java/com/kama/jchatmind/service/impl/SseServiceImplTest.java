package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.message.SseMessage;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ConcurrentMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

class SseServiceImplTest {

    @Test
    void shouldIgnoreSendWhenClientIsNotConnected() {
        SseServiceImpl service = new SseServiceImpl(new ObjectMapper());

        assertDoesNotThrow(() -> service.send("session-1", buildMessage()));
    }

    @Test
    void shouldRemoveClientWhenSendingFails() throws IOException {
        SseServiceImpl service = new SseServiceImpl(new ObjectMapper());
        SseEmitter emitter = mock(SseEmitter.class);
        doThrow(new IOException("connection closed"))
                .when(emitter)
                .send(any(SseEmitter.SseEventBuilder.class));

        @SuppressWarnings("unchecked")
        ConcurrentMap<String, SseEmitter> clients =
                (ConcurrentMap<String, SseEmitter>) ReflectionTestUtils.getField(service, "clients");
        clients.put("session-1", emitter);

        assertDoesNotThrow(() -> service.send("session-1", buildMessage()));
        assertFalse(clients.containsKey("session-1"));
    }

    private static SseMessage buildMessage() {
        return SseMessage.builder()
                .type(SseMessage.Type.AI_THINKING)
                .payload(SseMessage.Payload.builder()
                        .statusText("thinking")
                        .done(false)
                        .stepNumber(1)
                        .build())
                .build();
    }
}
