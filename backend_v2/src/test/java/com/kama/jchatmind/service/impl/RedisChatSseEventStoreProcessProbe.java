package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.message.SseMessage;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public final class RedisChatSseEventStoreProcessProbe {

    public static void main(String[] args) throws Exception {
        String action = args[0];
        int port = Integer.parseInt(args[1]);
        String sessionId = args[2];
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", port)
        );
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        ObjectMapper objectMapper = new ObjectMapper();
        RedisChatSseEventStore eventStore = new RedisChatSseEventStore(redisTemplate, objectMapper);

        try {
            SseServiceImpl sseService = new SseServiceImpl(objectMapper, eventStore);
            if ("append".equals(action)) {
                sseService.send(sessionId, SseMessage.builder()
                        .type(SseMessage.Type.AI_THINKING)
                        .build());
                System.out.println("APPENDED:" + eventStore.latestSequence(sessionId));
                return;
            }
            if ("replay".equals(action)) {
                RecordingEmitter emitter = new RecordingEmitter();
                if (!sseService.replayToEmitter(sessionId, emitter, 0L)) {
                    throw new IllegalStateException("Redis SSE replay was rejected");
                }
                System.out.println(String.join("\n", emitter.events()));
                return;
            }
            throw new IllegalArgumentException("Unknown process probe action");
        } finally {
            connectionFactory.destroy();
        }
    }

    private static final class RecordingEmitter extends SseEmitter {

        private final List<String> events = new ArrayList<>();

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            events.add(builder.build().stream()
                    .map(part -> part.getData().toString())
                    .collect(Collectors.joining()));
        }

        private List<String> events() {
            return List.copyOf(events);
        }
    }
}
