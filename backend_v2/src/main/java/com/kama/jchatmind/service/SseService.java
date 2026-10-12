package com.kama.jchatmind.service;

import com.kama.jchatmind.message.SseMessage;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface SseService {
    SseEmitter connect(String chatSessionId, Long lastEventId);

    void send(String chatSessionId, SseMessage message);
}
