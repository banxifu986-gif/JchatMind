package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;

import java.io.IOException;

public class RedisChatSseEventSubscriber implements MessageListener {

    private final ObjectMapper objectMapper;
    private final SseServiceImpl sseService;

    public RedisChatSseEventSubscriber(ObjectMapper objectMapper, SseServiceImpl sseService) {
        this.objectMapper = objectMapper;
        this.sseService = sseService;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            RedisChatSseEventStore.Event event = objectMapper.readValue(
                    message.getBody(),
                    RedisChatSseEventStore.Event.class
            );
            sseService.acceptEvent(event);
        } catch (IOException exception) {
            throw new IllegalStateException("Redis chat SSE event is invalid", exception);
        }
    }
}
