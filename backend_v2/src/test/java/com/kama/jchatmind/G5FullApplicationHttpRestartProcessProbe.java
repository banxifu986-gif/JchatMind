package com.kama.jchatmind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.auth.JwtUtil;
import com.kama.jchatmind.message.SseMessage;
import com.kama.jchatmind.service.impl.RedisChatSseEventStore;
import com.kama.jchatmind.service.impl.SseServiceImpl;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class G5FullApplicationHttpRestartProcessProbe {

    private G5FullApplicationHttpRestartProcessProbe() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("A session ID and expected sequence are required");
        }
        String sessionId = args[0];
        long expectedSequence = Long.parseLong(args[1]);

        try (ConfigurableApplicationContext context =
                     G5FullApplicationHttpPreflightL2Test.startSecondFullApplication()) {
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            ObjectMapper objectMapper = context.getBean(ObjectMapper.class);
            String token = context.getBean(JwtUtil.class).generateToken(7L);
            verifyOwnedHistory(client, objectMapper, port, sessionId, token);

            RedisChatSseEventStore eventStore = context.getBean(RedisChatSseEventStore.class);
            if (eventStore.latestSequence(sessionId) != expectedSequence) {
                throw new AssertionError("Full application process read an unexpected Redis sequence");
            }

            HttpResponse<InputStream> connection = connect(
                    client,
                    port,
                    sessionId,
                    token,
                    expectedSequence - 1
            );
            if (connection.statusCode() != 200) {
                throw new AssertionError("Full application process rejected an owner SSE connection");
            }
            try (InputStream stream = connection.body()) {
                String replayedEvent = G5FullApplicationHttpPreflightL2Test.readFirstEvent(stream);
                if (!replayedEvent.contains("id:" + expectedSequence)
                        || !replayedEvent.contains("event:message")) {
                    throw new AssertionError("Full application process did not replay persisted SSE history");
                }
                CompletableFuture<String> nextEvent = CompletableFuture.supplyAsync(
                        () -> G5FullApplicationHttpPreflightL2Test.readFirstEvent(stream)
                );
                context.getBean(SseServiceImpl.class).send(sessionId, SseMessage.builder()
                        .type(SseMessage.Type.AI_THINKING)
                        .payload(SseMessage.Payload.builder()
                                .statusText("process-restart-" + expectedSequence)
                                .build())
                        .build());
                String event = nextEvent.get(5, TimeUnit.SECONDS);
                long nextSequence = expectedSequence + 1;
                if (!event.contains("id:" + nextSequence)
                        || !event.contains("process-restart-" + expectedSequence)
                        || eventStore.latestSequence(sessionId) != nextSequence) {
                    throw new AssertionError("Full application process did not continue Redis sequence");
                }
                G5FullApplicationHttpPreflightL2Test.completeSessionEmitters(
                        context.getBean(SseServiceImpl.class), sessionId
                );
                System.out.println("G5_FULL_APP_HTTP_RESTART_PASS:" + expectedSequence + ":" + nextSequence);
            }
        }
    }

    private static void verifyOwnedHistory(
            HttpClient client,
            ObjectMapper objectMapper,
            int port,
            String sessionId,
            String token
    ) throws Exception {
        HttpResponse<String> history = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                                + "/api/chat-messages/session/" + sessionId))
                        .timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer " + token)
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        JsonNode body = objectMapper.readTree(history.body());
        JsonNode messages = body.path("data").path("chatMessages");
        if (history.statusCode() != 200 || body.path("code").asInt() != 200
                || messages.size() != 70
                || !"history-0".equals(messages.get(0).path("content").asText())
                || !"history-69".equals(messages.get(69).path("content").asText())) {
            throw new AssertionError("Full application process did not serve the persisted owner history");
        }
    }

    private static HttpResponse<InputStream> connect(
            HttpClient client,
            int port,
            String sessionId,
            String token,
            long lastEventId
    ) throws Exception {
        return client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                                + "/sse/connect/" + sessionId))
                        .timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer " + token)
                        .header("Last-Event-ID", Long.toString(lastEventId))
                        .header("Accept", "text/event-stream")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream()
        );
    }
}
