package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.message.SseMessage;
import com.kama.jchatmind.service.SseService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
@AllArgsConstructor
@Slf4j
public class SseServiceImpl implements SseService {

    private final ConcurrentMap<String, SseEmitter> clients = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    @Override
    public SseEmitter connect(String chatSessionId) {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        clients.put(chatSessionId, emitter);

        try {
            emitter.send(SseEmitter.event()
                    .name("init")
                    .data("connected")
            );
        } catch (IOException e) {
            clients.remove(chatSessionId);
            log.warn("Failed to initialize SSE connection for session {}", chatSessionId, e);
        }

        emitter.onCompletion(() -> clients.remove(chatSessionId));
        emitter.onTimeout(() -> clients.remove(chatSessionId));
        emitter.onError((error) -> clients.remove(chatSessionId));

        return emitter;
    }

    @Override
    public void send(String chatSessionId, SseMessage message) {
        SseEmitter emitter = clients.get(chatSessionId);
        if (emitter == null) {
            log.debug("Skip SSE message because no client is connected for session {}", chatSessionId);
            return;
        }

        try {
            String sseMessageStr = objectMapper.writeValueAsString(message);
            emitter.send(SseEmitter.event()
                    .name("message")
                    .data(sseMessageStr)
            );
        } catch (IOException e) {
            clients.remove(chatSessionId);
            log.warn("Failed to send SSE message for session {}", chatSessionId, e);
        }
    }
}
