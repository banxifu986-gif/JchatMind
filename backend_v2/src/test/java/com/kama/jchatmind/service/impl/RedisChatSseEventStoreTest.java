package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.RedisConfig;
import com.kama.jchatmind.message.SseMessage;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.File;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.UUID;
import java.util.stream.Collectors;

import org.mockito.ArgumentCaptor;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RedisChatSseEventStoreTest {

    @Test
    void shouldAppendOrderedEventsAndReplayOnlyTheBoundedHistory() {
        assumeTrue(Boolean.getBoolean("g5.sse.redis.l2"));
        int port = Integer.getInteger("g5.sse.redis.port", 0);
        assumeTrue(port >= 49152 && port <= 65535);

        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", port)
        );
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        RedisChatSseEventStore eventStore = new RedisChatSseEventStore(redisTemplate, new ObjectMapper());
        String sessionId = "g5-sse-l2-" + UUID.randomUUID();

        try {
            RedisChatSseEventStore.Event first = eventStore.append(sessionId, "{\"type\":\"AI_THINKING\"}");
            RedisChatSseEventStore.Event second = eventStore.append(sessionId, "{\"type\":\"AI_EXECUTING\"}");

            assertThat(first.sequence()).isEqualTo(1);
            assertThat(second.sequence()).isEqualTo(2);
            assertThat(eventStore.readAfter(sessionId, first.sequence()))
                    .extracting(RedisChatSseEventStore.Event::sequence)
                    .containsExactly(2L);

            for (int i = 0; i < 70; i++) {
                eventStore.append(sessionId, "{\"type\":\"AI_CONTENT_DELTA\",\"index\":" + i + "}");
            }

            assertThat(eventStore.latestSequence(sessionId)).isEqualTo(72);
            String encodedSessionId = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(sessionId.getBytes(StandardCharsets.UTF_8));
            String keyPrefix = "jchatmind:chat-sse:{" + encodedSessionId + "}";
            assertThat(redisTemplate.getExpire(keyPrefix + ":sequence"))
                    .isBetween(1L, (long) RedisChatSseEventStore.HISTORY_RETENTION_SECONDS);
            assertThat(redisTemplate.getExpire(keyPrefix + ":history"))
                    .isBetween(1L, (long) RedisChatSseEventStore.HISTORY_RETENTION_SECONDS);
            RedisChatSseEventStore.Snapshot snapshot = eventStore.readSnapshot(sessionId);
            assertThat(snapshot.latestSequence()).isEqualTo(72);
            assertThat(snapshot.events())
                    .hasSize(RedisChatSseEventStore.MAX_HISTORY_EVENTS)
                    .extracting(RedisChatSseEventStore.Event::sequence)
                    .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(9, 72).boxed().toList());
            assertThat(eventStore.readAfter(sessionId, 0))
                    .hasSize(RedisChatSseEventStore.MAX_HISTORY_EVENTS)
                    .extracting(RedisChatSseEventStore.Event::sequence)
                    .containsExactlyElementsOf(java.util.stream.LongStream.rangeClosed(9, 72).boxed().toList());
            assertThat(eventStore.readAfter(sessionId, 72)).isEmpty();
        } finally {
            eventStore.delete(sessionId);
            connectionFactory.destroy();
        }
    }

    @Test
    void shouldReplayStoredSseEventsAfterApplicationContextReplacement() throws IOException {
        assumeTrue(Boolean.getBoolean("g5.sse.redis.l2"));
        int port = Integer.getInteger("g5.sse.redis.port", 0);
        assumeTrue(port >= 49152 && port <= 65535);

        LettuceConnectionFactory firstFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", port)
        );
        LettuceConnectionFactory replacementFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", port)
        );
        firstFactory.afterPropertiesSet();
        replacementFactory.afterPropertiesSet();
        String sessionId = "g5-sse-replay-l2-" + UUID.randomUUID();

        try {
            try (AnnotationConfigApplicationContext firstContext = openSseContext(firstFactory)) {
                firstContext.getBean(SseServiceImpl.class).send(sessionId, SseMessage.builder()
                        .type(SseMessage.Type.AI_THINKING)
                        .build());
            }

            try (AnnotationConfigApplicationContext replacementContext = openSseContext(replacementFactory)) {
                RedisChatSseEventStore eventStore = replacementContext.getBean(RedisChatSseEventStore.class);
                try {
                    SseServiceImpl replacementInstance = replacementContext.getBean(SseServiceImpl.class);
                    SseEmitter emitter = mock(SseEmitter.class);
                    assertThat(replacementInstance.replayToEmitter(sessionId, emitter, 0)).isTrue();

                    ArgumentCaptor<SseEmitter.SseEventBuilder> sentEvent =
                            ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
                    verify(emitter).send(sentEvent.capture());
                    String eventText = sentEvent.getValue().build().stream()
                            .map(part -> part.getData().toString())
                            .collect(Collectors.joining());
                    assertThat(eventText).contains("id:1\n", "AI_THINKING");
                } finally {
                    eventStore.delete(sessionId);
                }
            }
        } finally {
            firstFactory.destroy();
            replacementFactory.destroy();
        }
    }

    @Test
    void shouldReplayStoredSseEventsAfterJvmRestart() throws Exception {
        assumeTrue(Boolean.getBoolean("g5.sse.redis.l2"));
        int port = Integer.getInteger("g5.sse.redis.port", 0);
        assumeTrue(port >= 49152 && port <= 65535);

        LettuceConnectionFactory cleanupFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", port)
        );
        cleanupFactory.afterPropertiesSet();
        StringRedisTemplate cleanupTemplate = new StringRedisTemplate(cleanupFactory);
        cleanupTemplate.afterPropertiesSet();
        RedisChatSseEventStore cleanupStore = new RedisChatSseEventStore(cleanupTemplate, new ObjectMapper());
        String sessionId = "g5-sse-process-restart-l2-" + UUID.randomUUID();

        try {
            ProcessResult writer = runProcessProbe("append", port, sessionId);
            assertThat(writer.exitCode()).withFailMessage(writer.output()).isZero();
            assertThat(writer.output()).contains("APPENDED:1");

            ProcessResult reader = runProcessProbe("replay", port, sessionId);
            assertThat(reader.exitCode()).withFailMessage(reader.output()).isZero();
            assertThat(reader.output()).contains("id:1\n", "AI_THINKING");
        } finally {
            cleanupStore.delete(sessionId);
            cleanupFactory.destroy();
        }
    }

    @Test
    void shouldSignalReplayGapWhenRedisHistoryWindowIsExceeded() throws IOException {
        assumeTrue(Boolean.getBoolean("g5.sse.redis.l2"));
        int port = Integer.getInteger("g5.sse.redis.port", 0);
        assumeTrue(port >= 49152 && port <= 65535);

        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", port)
        );
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();
        ObjectMapper objectMapper = new ObjectMapper();
        RedisChatSseEventStore eventStore = new RedisChatSseEventStore(redisTemplate, objectMapper);
        String sessionId = "g5-sse-gap-l2-" + UUID.randomUUID();

        try {
            SseServiceImpl sender = new SseServiceImpl(objectMapper, eventStore);
            for (int i = 0; i < 70; i++) {
                sender.send(sessionId, SseMessage.builder()
                        .type(SseMessage.Type.AI_THINKING)
                        .build());
            }

            SseServiceImpl replacement = new SseServiceImpl(objectMapper, eventStore);
            SseEmitter emitter = mock(SseEmitter.class);
            assertThat(replacement.replayToEmitter(sessionId, emitter, 0)).isTrue();

            ArgumentCaptor<SseEmitter.SseEventBuilder> sentEvent =
                    ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
            verify(emitter).send(sentEvent.capture());
            String eventText = sentEvent.getValue().build().stream()
                    .map(part -> part.getData().toString())
                    .collect(Collectors.joining());
            assertThat(eventText).contains("id:70\n", "event:replay-gap\n", "history_window_exceeded");
        } finally {
            eventStore.delete(sessionId);
            connectionFactory.destroy();
        }
    }

    @Test
    void shouldBroadcastRedisEventsToAnotherServiceInstance() throws Exception {
        assumeTrue(Boolean.getBoolean("g5.sse.redis.l2"));
        int port = Integer.getInteger("g5.sse.redis.port", 0);
        assumeTrue(port >= 49152 && port <= 65535);

        LettuceConnectionFactory senderFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", port)
        );
        LettuceConnectionFactory receiverFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", port)
        );
        senderFactory.afterPropertiesSet();
        receiverFactory.afterPropertiesSet();
        String sessionId = "g5-sse-pubsub-l2-" + UUID.randomUUID();

        try (AnnotationConfigApplicationContext senderContext = openSseContext(senderFactory);
             AnnotationConfigApplicationContext receiverContext = openSseContext(receiverFactory)) {
            RedisChatSseEventStore eventStore = senderContext.getBean(RedisChatSseEventStore.class);
            SseServiceImpl sender = senderContext.getBean(SseServiceImpl.class);
            SseServiceImpl receiver = receiverContext.getBean(SseServiceImpl.class);
            awaitSubscriptions(senderContext);
            awaitSubscriptions(receiverContext);

            SseEmitter emitter = mock(SseEmitter.class);
            SseServiceImpl.SessionState sessionState = new SseServiceImpl.SessionState();
            sessionState.clients.add(emitter);
            sessions(receiver).put(sessionId, sessionState);
            CountDownLatch received = new CountDownLatch(1);
            doAnswer(invocation -> {
                received.countDown();
                return null;
            }).when(emitter).send(any(SseEmitter.SseEventBuilder.class));

            sender.send(sessionId, SseMessage.builder()
                    .type(SseMessage.Type.AI_THINKING)
                    .build());

            assertThat(received.await(2, TimeUnit.SECONDS)).isTrue();
            ArgumentCaptor<SseEmitter.SseEventBuilder> sentEvent =
                    ArgumentCaptor.forClass(SseEmitter.SseEventBuilder.class);
            verify(emitter).send(sentEvent.capture());
            String eventText = sentEvent.getValue().build().stream()
                    .map(part -> part.getData().toString())
                    .collect(Collectors.joining());
            assertThat(eventText).contains("id:1\n", "AI_THINKING");
            eventStore.delete(sessionId);
        } finally {
            senderFactory.destroy();
            receiverFactory.destroy();
        }
    }

    private static AnnotationConfigApplicationContext openSseContext(RedisConnectionFactory connectionFactory) {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean(RedisConnectionFactory.class, () -> connectionFactory);
        context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        context.register(RedisConfig.class, RedisChatSseEventStore.class, SseServiceImpl.class);
        context.refresh();
        return context;
    }

    private static ProcessResult runProcessProbe(String action, int port, String sessionId) throws Exception {
        String classPath = System.getProperty("java.class.path")
                + File.pathSeparator + classPathEntry(RedisChatSseEventStoreProcessProbe.class)
                + File.pathSeparator + classPathEntry(RedisChatSseEventStore.class);
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        Process process = new ProcessBuilder(
                javaExecutable,
                "-cp",
                classPath,
                RedisChatSseEventStoreProcessProbe.class.getName(),
                action,
                Integer.toString(port),
                sessionId
        ).redirectErrorStream(true).start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Redis SSE process probe did not finish within 10 seconds");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProcessResult(process.exitValue(), output);
    }

    private static String classPathEntry(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
    }

    private static void awaitSubscriptions(AnnotationConfigApplicationContext context) throws InterruptedException {
        for (RedisMessageListenerContainer container
                : context.getBeansOfType(RedisMessageListenerContainer.class).values()) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!container.isListening() && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(container.isListening()).isTrue();
        }
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentMap<String, SseServiceImpl.SessionState> sessions(SseServiceImpl service) {
        return (ConcurrentMap<String, SseServiceImpl.SessionState>) ReflectionTestUtils.getField(service, "sessions");
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
