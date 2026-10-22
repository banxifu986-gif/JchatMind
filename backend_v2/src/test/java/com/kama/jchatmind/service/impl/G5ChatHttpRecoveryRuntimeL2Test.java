package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.auth.JwtUtil;
import com.kama.jchatmind.message.SseMessage;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

public class G5ChatHttpRecoveryRuntimeL2Test {

    private static final int HISTORY_SIZE = 70;
    private static final long OWNER_ID = 7L;
    private static final long OTHER_ID = 8L;

    @Test
    void shouldRecoverReplayGapAndFullHistoryAfterIsolatedBootContextRestart() throws Exception {
        assumeTrue(Boolean.getBoolean("g5.chat.http.l2"));
        int postgresPort = requiredPort("g5.chat.http.pg.port");
        requiredPort("g5.chat.http.redis.port");

        String sessionId = UUID.randomUUID().toString();
        String jwtSecret = "g5-chat-http-test-" + UUID.randomUUID() + UUID.randomUUID();
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl("jdbc:postgresql://127.0.0.1:" + postgresPort + "/g5_chat_http_l2");
        dataSource.setUser("g5_http");

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            try (ResultSet identity = statement.executeQuery("SELECT current_database(), current_user")) {
                assertThat(identity.next()).isTrue();
                assertThat(identity.getString(1)).isEqualTo("g5_chat_http_l2");
                assertThat(identity.getString(2)).isEqualTo("g5_http");
            }
            try {
                createHistory(statement, connection, sessionId);
                String ownerToken;
                String otherToken;
                try (ConfigurableApplicationContext first = startApplication(jwtSecret)) {
                    JwtUtil jwtUtil = first.getBean(JwtUtil.class);
                    ownerToken = jwtUtil.generateToken(OWNER_ID);
                    otherToken = jwtUtil.generateToken(OTHER_ID);
                    SseServiceImpl sender = first.getBean(SseServiceImpl.class);
                    for (int index = 0; index < HISTORY_SIZE; index++) {
                        sender.send(sessionId, SseMessage.builder()
                                .type(SseMessage.Type.AI_THINKING)
                                .build());
                    }
                    assertThat(first.getBean(RedisChatSseEventStore.class).latestSequence(sessionId))
                            .isEqualTo(HISTORY_SIZE);
                }

                try (ConfigurableApplicationContext second = startApplication(jwtSecret)) {
                    try {
                        int httpPort = ((ServletWebServerApplicationContext) second).getWebServer().getPort();
                        HttpClient httpClient = HttpClient.newBuilder()
                                .connectTimeout(Duration.ofSeconds(5))
                                .build();
                        assertOtherOwnerCannotConnect(httpClient, httpPort, sessionId, otherToken);
                        boolean browserL3 = Boolean.getBoolean("g5.chat.browser.l3");
                        boolean redisRestartL2 = Boolean.getBoolean("g5.chat.http.redis.restart.l2");
                        boolean redisSigkillL2 = Boolean.getBoolean("g5.chat.http.redis.sigkill.l2");
                        boolean redisNetworkL2 = Boolean.getBoolean("g5.chat.http.redis.network.l2");
                        assertThat(browserL3 && (redisRestartL2 || redisNetworkL2)).isFalse();
                        assertThat(redisSigkillL2 && !redisRestartL2).isFalse();
                        assertThat(redisNetworkL2 && redisRestartL2).isFalse();
                        if (!browserL3) {
                            assertReplayGap(httpClient, httpPort, sessionId, ownerToken, 0, 70);
                        }
                        assertCompleteHistory(httpClient, second.getBean(ObjectMapper.class),
                                httpPort, sessionId, ownerToken);
                        if (redisRestartL2 || redisNetworkL2) {
                            assertRedisFailureRecovery(httpClient, httpPort, sessionId, ownerToken,
                                    second.getBean(RedisChatSseEventStore.class),
                                    second.getBean(ObjectMapper.class), jwtSecret);
                        }
                        if (browserL3) {
                            assertBrowserRecovery(httpPort, sessionId, ownerToken,
                                    second.getBean(SseServiceImpl.class),
                                    second.getBean(RedisChatSseEventStore.class), false, false,
                                    null, null, null, null, null, false, false);
                        }
                    } finally {
                        completeSessionEmitters(second.getBean(SseServiceImpl.class), sessionId);
                        second.getBean(RedisChatSseEventStore.class).delete(sessionId);
                    }
                }
            } finally {
                statement.execute("DROP TABLE IF EXISTS chat_message, chat_session CASCADE");
            }
        }
    }

    private static ConfigurableApplicationContext startApplication(String jwtSecret) {
        return new SpringApplicationBuilder(G5ChatHttpRecoveryTestConfig.class)
                .web(WebApplicationType.SERVLET)
                .properties(
                        "spring.config.name=g5-chat-http-isolated",
                        "spring.ai.model.chat=none",
                        "spring.ai.model.embedding=none",
                        "spring.ai.model.image=none",
                        "spring.ai.mcp.server.enabled=false",
                        "spring.autoconfigure.exclude="
                                + "org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,"
                                + "org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration,"
                                + "org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration",
                        "server.address=127.0.0.1",
                        "server.port=0",
                        "jwt.secret=" + jwtSecret,
                        "jwt.expiration=3600"
                )
                .run();
    }

    private static void createHistory(Statement statement, Connection connection, String sessionId) throws Exception {
        statement.execute("CREATE TABLE chat_session ("
                + "id UUID PRIMARY KEY, user_id TEXT NOT NULL, agent_id UUID, title TEXT, "
                + "metadata JSONB, created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL)");
        statement.execute("CREATE TABLE chat_message ("
                + "id UUID PRIMARY KEY, session_id UUID NOT NULL REFERENCES chat_session(id), "
                + "role VARCHAR(32) NOT NULL, content TEXT NOT NULL, metadata JSONB, "
                + "created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL)");
        statement.execute("CREATE TABLE agent_execution_turn_message ("
                + "chat_message_id UUID PRIMARY KEY, session_id UUID NOT NULL, sequence_no BIGINT NOT NULL)");

        Timestamp createdAt = Timestamp.valueOf(LocalDateTime.of(2026, 10, 13, 0, 0));
        try (PreparedStatement session = connection.prepareStatement(
                "INSERT INTO chat_session VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            session.setObject(1, UUID.fromString(sessionId));
            session.setString(2, Long.toString(OWNER_ID));
            session.setObject(3, UUID.randomUUID());
            session.setString(4, "G5 HTTP recovery");
            session.setNull(5, Types.OTHER);
            session.setTimestamp(6, createdAt);
            session.setTimestamp(7, createdAt);
            session.executeUpdate();
        }

        try (PreparedStatement message = connection.prepareStatement(
                "INSERT INTO chat_message VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            for (int index = 0; index < HISTORY_SIZE; index++) {
                Timestamp messageTime = Timestamp.valueOf(createdAt.toLocalDateTime().plusSeconds(index));
                message.setObject(1, UUID.randomUUID());
                message.setObject(2, UUID.fromString(sessionId));
                message.setString(3, index % 2 == 0 ? "user" : "assistant");
                message.setString(4, "history-" + index);
                message.setNull(5, Types.OTHER);
                message.setTimestamp(6, messageTime);
                message.setTimestamp(7, messageTime);
                message.addBatch();
            }
            message.executeBatch();
        }
    }

    private static void assertOtherOwnerCannotConnect(
            HttpClient client,
            int port,
            String sessionId,
            String token
    ) throws Exception {
        HttpResponse<String> response = client.send(
                request(port, "/sse/connect/" + sessionId, token)
                        .header("Last-Event-ID", "0")
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).doesNotContain(sessionId);
    }

    private static void assertReplayGap(
            HttpClient client,
            int port,
            String sessionId,
            String token,
            long cursor,
            long expectedId
    )
            throws Exception {
        HttpResponse<InputStream> response = client.send(
                request(port, "/sse/connect/" + sessionId, token)
                        .header("Last-Event-ID", Long.toString(cursor))
                        .header("Accept", "text/event-stream")
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream()
        );
        assertThat(response.statusCode()).isEqualTo(200);
        try (InputStream stream = response.body()) {
            String firstEvent = CompletableFuture.supplyAsync(() -> readFirstEvent(stream))
                    .get(5, TimeUnit.SECONDS);
            assertThat(firstEvent).contains("id:" + expectedId,
                    "event:replay-gap", "data:history_window_exceeded");
        }
    }

    private static String readFirstEvent(InputStream stream) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            StringBuilder event = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty() && event.length() > 0) {
                    return event.toString();
                }
                event.append(line).append('\n');
            }
            throw new AssertionError("SSE connection closed before replay-gap");
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read SSE replay-gap", exception);
        }
    }

    private static void assertCompleteHistory(
            HttpClient client,
            ObjectMapper objectMapper,
            int port,
            String sessionId,
            String token
    ) throws Exception {
        HttpResponse<String> response = client.send(
                request(port, "/api/chat-messages/session/" + sessionId, token).build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode messages = objectMapper.readTree(response.body()).path("data").path("chatMessages");
        assertThat(messages.size()).isEqualTo(HISTORY_SIZE);
        assertThat(messages.get(0).path("content").asText()).isEqualTo("history-0");
        assertThat(messages.get(HISTORY_SIZE - 1).path("content").asText()).isEqualTo("history-69");
    }

    private static void assertRedisFailureRecovery(
            HttpClient client,
            int httpPort,
            String sessionId,
            String ownerToken,
            RedisChatSseEventStore eventStore,
            ObjectMapper objectMapper,
            String jwtSecret
    ) throws Exception {
        String containerName = System.getProperty("g5.chat.http.redis.container");
        assertThat(containerName).matches("jchatmind-g5-http-redis-[0-9a-f]{8}");
        ProcessResult port = runDocker("port", containerName, "6379/tcp");
        assertThat(port.exitCode()).withFailMessage(port.output()).isZero();
        assertThat(port.output().trim())
                .isEqualTo("127.0.0.1:" + requiredPort("g5.chat.http.redis.port"));
        assertThat(eventStore.latestSequence(sessionId)).isEqualTo(HISTORY_SIZE);

        HttpResponse<InputStream> liveResponse = client.send(
                request(httpPort, "/sse/connect/" + sessionId, ownerToken)
                        .header("Accept", "text/event-stream")
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream()
        );
        assertThat(liveResponse.statusCode()).isEqualTo(200);
        try (ConfigurableApplicationContext senderContext = startApplication(jwtSecret);
             InputStream liveStream = liveResponse.body()) {
            CompletableFuture<String> liveEvent = CompletableFuture.supplyAsync(
                    () -> readFirstMessageEvent(liveStream));
            if (Boolean.getBoolean("g5.chat.http.redis.network.l2")) {
                ProcessResult network = runDocker("inspect", "--format",
                        "{{range $network, $settings := .NetworkSettings.Networks}}{{$network}}{{end}}",
                        containerName);
                assertThat(network.exitCode()).withFailMessage(network.output()).isZero();
                assertThat(network.output().trim()).isEqualTo("bridge");
                ProcessResult disconnect = runDocker("network", "disconnect", "bridge", containerName);
                assertThat(disconnect.exitCode()).withFailMessage(disconnect.output()).isZero();
                ProcessResult detached = runDocker("inspect", "--format",
                        "{{range $network, $settings := .NetworkSettings.Networks}}{{$network}}{{end}}",
                        containerName);
                assertThat(detached.exitCode()).withFailMessage(detached.output()).isZero();
                assertThat(detached.output().trim()).isEmpty();
                ProcessResult reconnect = runDocker("network", "connect", "bridge", containerName);
                assertThat(reconnect.exitCode()).withFailMessage(reconnect.output()).isZero();
            } else if (Boolean.getBoolean("g5.chat.http.redis.sigkill.l2")) {
                ProcessResult kill = runDocker("kill", "--signal", "KILL", containerName);
                assertThat(kill.exitCode()).withFailMessage(kill.output()).isZero();
                ProcessResult start = runDocker("start", containerName);
                assertThat(start.exitCode()).withFailMessage(start.output()).isZero();
            } else {
                ProcessResult restart = runDocker("restart", "--timeout", "5", containerName);
                assertThat(restart.exitCode()).withFailMessage(restart.output()).isZero();
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            boolean recovered = false;
            while (System.nanoTime() < deadline) {
                try {
                    recovered = eventStore.latestSequence(sessionId) == HISTORY_SIZE;
                    if (recovered) {
                        break;
                    }
                } catch (RuntimeException exception) {
                    recovered = false;
                }
                Thread.sleep(100);
            }
            assertThat(recovered).as("Redis sequence after isolated failure recovery").isTrue();

            assertReplayGap(client, httpPort, sessionId, ownerToken, 0, HISTORY_SIZE);
            SseServiceImpl remoteSender = senderContext.getBean(SseServiceImpl.class);
            String delivered = null;
            for (int attempt = 0; attempt < 20 && delivered == null; attempt++) {
                remoteSender.send(sessionId, SseMessage.builder()
                        .type(SseMessage.Type.AI_THINKING)
                        .payload(SseMessage.Payload.builder()
                                .statusText("cross-instance-after-restart-" + attempt)
                                .build())
                        .build());
                try {
                    delivered = liveEvent.get(500, TimeUnit.MILLISECONDS);
                } catch (TimeoutException exception) {
                    // Wait for the Redis Pub/Sub listener to resubscribe.
                }
            }
            assertThat(delivered)
                    .contains("id:71", "event:message", "cross-instance-after-restart");
            assertThat(eventStore.latestSequence(sessionId)).isGreaterThanOrEqualTo(HISTORY_SIZE + 1);
            HttpResponse<InputStream> response = client.send(
                    request(httpPort, "/sse/connect/" + sessionId, ownerToken)
                            .header("Last-Event-ID", Long.toString(HISTORY_SIZE))
                            .header("Accept", "text/event-stream")
                            .build(),
                    HttpResponse.BodyHandlers.ofInputStream()
            );
            assertThat(response.statusCode()).isEqualTo(200);
            try (InputStream stream = response.body()) {
                String firstEvent = CompletableFuture.supplyAsync(() -> readFirstEvent(stream))
                        .get(5, TimeUnit.SECONDS);
                assertThat(firstEvent).contains("id:71", "event:message", "cross-instance-after-restart");
            }
            assertCompleteHistory(client, objectMapper, httpPort, sessionId, ownerToken);
        }
    }

    private static String readFirstMessageEvent(InputStream stream) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            StringBuilder event = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (event.toString().contains("event:message\n")) {
                        return event.toString();
                    }
                    event.setLength(0);
                } else {
                    event.append(line).append('\n');
                }
            }
            throw new AssertionError("SSE connection closed before the post-restart message");
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read post-restart SSE message", exception);
        }
    }

    private static ProcessResult runDocker(String... arguments) throws Exception {
        String[] command = new String[arguments.length + 1];
        command[0] = "docker";
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Docker command timed out");
        }
        return new ProcessResult(process.exitValue(),
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }

    private record ProcessResult(int exitCode, String output) {
    }

    private static HttpRequest.Builder request(int port, String path, String token) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token)
                .GET();
    }

    private static void completeSessionEmitters(SseServiceImpl service, String sessionId) {
        SseServiceImpl.SessionState state = sessionState(service, sessionId);
        if (state == null) {
            return;
        }
        List<SseEmitter> emitters;
        synchronized (state.lock) {
            emitters = List.copyOf(state.clients);
        }
        emitters.forEach(SseEmitter::complete);
    }

    public static void assertBrowserRecovery(
            int httpPort,
            String sessionId,
            String ownerToken,
            SseServiceImpl sseService,
            RedisChatSseEventStore eventStore,
            boolean directSse,
            boolean directHistory,
            String expectedKbId,
            String browserLoginPassword,
            String otherKbId,
            String otherBrowserLoginPassword,
            String ownerMessageContent,
            boolean restartRedisDuringBrowserL3,
            boolean sigkillRedisDuringBrowserL3
    )
            throws Exception {
        Path uiRoot = Path.of("..", "ui").toAbsolutePath().normalize();
        Path targetRoot = Path.of("target").toAbsolutePath().normalize();
        Files.createDirectories(targetRoot);
        int uiPort = availablePort();
        String uiBaseUrl = "http://127.0.0.1:" + uiPort;
        Path viteLog = targetRoot.resolve("g5-chat-vite.log");
        Path browserLog = targetRoot.resolve("g5-chat-browser.log");
        Path cursorReadyFile = targetRoot.resolve("g5-browser-cursor-" + sessionId + ".flag");
        Files.deleteIfExists(cursorReadyFile);

        ProcessBuilder viteBuilder = new ProcessBuilder("node", "tests/g5-vite-server.mjs")
                .directory(uiRoot.toFile())
                .redirectErrorStream(true)
                .redirectOutput(viteLog.toFile());
        viteBuilder.environment().put("G5_UI_PORT", Integer.toString(uiPort));
        viteBuilder.environment().put("G5_CHAT_HTTP_URL", "http://127.0.0.1:" + httpPort);
        viteBuilder.environment().put("VITE_API_BASE_URL", directHistory
                ? "http://127.0.0.1:" + httpPort + "/api" : "/mock-api");
        viteBuilder.environment().put("VITE_SSE_BASE_URL", directSse
                ? "http://127.0.0.1:" + httpPort + "/sse" : "/mock-sse");
        Process vite = viteBuilder.start();
        try {
            awaitUi(vite, uiBaseUrl, viteLog);
            ExecutorService worker = Executors.newSingleThreadExecutor();
            Future<?> gapTask = worker.submit(() -> {
                try {
                    prepareBrowserGap(sseService, eventStore, sessionId, cursorReadyFile,
                            restartRedisDuringBrowserL3, sigkillRedisDuringBrowserL3);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while preparing the browser replay gap", exception);
                } catch (Exception exception) {
                    throw new IllegalStateException("Unable to prepare the browser replay gap", exception);
                }
            });
            try {
                ProcessBuilder browserBuilder = new ProcessBuilder(
                        "node", "node_modules/playwright/cli.js", "test",
                        "tests/chat-sse-real-backend-recovery.spec.ts", "--project=edge", "--reporter=list"
                ).directory(uiRoot.toFile())
                        .redirectErrorStream(true)
                        .redirectOutput(browserLog.toFile());
                browserBuilder.environment().put("G1_UI_BASE_URL", uiBaseUrl);
                browserBuilder.environment().put("G5_CHAT_SESSION_ID", sessionId);
                browserBuilder.environment().put("G5_CHAT_HTTP_URL", "http://127.0.0.1:" + httpPort);
                browserBuilder.environment().put("G5_BROWSER_DIRECT_SSE", Boolean.toString(directSse));
                browserBuilder.environment().put("G5_BROWSER_DIRECT_HISTORY", Boolean.toString(directHistory));
                browserBuilder.environment().put("G5_BROWSER_REAL_SESSIONS", Boolean.toString(directHistory));
                browserBuilder.environment().put("G5_BROWSER_REAL_WHOAMI", Boolean.toString(directHistory));
                browserBuilder.environment().put("G5_BROWSER_REAL_AGENTS", Boolean.toString(directHistory));
                browserBuilder.environment().put("G5_BROWSER_REAL_KBS", Boolean.toString(directHistory));
                browserBuilder.environment().put("G5_BROWSER_REAL_PENDING", Boolean.toString(directHistory));
                browserBuilder.environment().put("G5_BROWSER_REAL_TOOLS", Boolean.toString(directHistory));
                if (expectedKbId != null) {
                    browserBuilder.environment().put("G5_EXPECTED_KB_ID", expectedKbId);
                }
                browserBuilder.environment().put("G5_CHAT_OWNER_TOKEN", ownerToken);
                if (browserLoginPassword != null) {
                    browserBuilder.environment().put("G5_BROWSER_LOGIN_PASSWORD", browserLoginPassword);
                }
                if (otherKbId != null) {
                    browserBuilder.environment().put("G5_OTHER_KB_ID", otherKbId);
                    browserBuilder.environment().put("G5_BROWSER_OTHER_LOGIN_PASSWORD", otherBrowserLoginPassword);
                }
                if (ownerMessageContent != null) {
                    browserBuilder.environment().put("G5_BROWSER_OWNER_MESSAGE_CONTENT", ownerMessageContent);
                }
                browserBuilder.environment().put("G5_BROWSER_CURSOR_READY_FILE", cursorReadyFile.toString());
                Process browser = browserBuilder.start();
                if (!browser.waitFor(90, TimeUnit.SECONDS)) {
                    browser.toHandle().descendants().forEach(ProcessHandle::destroyForcibly);
                    browser.destroyForcibly();
                    throw new IllegalStateException("G5 Edge browser test timed out: " + Files.readString(browserLog));
                }
                assertThat(browser.exitValue()).withFailMessage(Files.readString(browserLog)).isZero();
                gapTask.get(5, TimeUnit.SECONDS);
                long latestSequence = eventStore.latestSequence(sessionId);
                assertThat(latestSequence).isGreaterThanOrEqualTo(141);
                assertReplayGap(HttpClient.newHttpClient(), httpPort, sessionId, ownerToken,
                        71, latestSequence);
                System.out.println("G5_BROWSER_L3_PASS");
            } finally {
                gapTask.cancel(true);
                worker.shutdownNow();
            }
        } finally {
            Files.deleteIfExists(cursorReadyFile);
            vite.destroy();
            if (!vite.waitFor(5, TimeUnit.SECONDS)) {
                vite.destroyForcibly();
            }
        }
    }

    private static void prepareBrowserGap(
            SseServiceImpl service,
            RedisChatSseEventStore eventStore,
            String sessionId,
            Path cursorReadyFile,
            boolean restartRedis,
            boolean sigkillRedis
    ) throws Exception {
        SseServiceImpl.SessionState state = awaitSessionClient(service, sessionId);
        SseEmitter emitter;
        synchronized (state.lock) {
            emitter = state.clients.iterator().next();
        }
        service.send(sessionId, SseMessage.builder()
                .type(SseMessage.Type.AI_THINKING)
                .payload(SseMessage.Payload.builder()
                        .statusText("g5-live-cursor")
                        .stepNumber(71)
                        .done(false)
                        .build())
                .build());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!Files.exists(cursorReadyFile) && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        if (!Files.exists(cursorReadyFile)) {
            throw new IllegalStateException("Browser did not confirm the live SSE cursor");
        }
        if (restartRedis || sigkillRedis) {
            String containerName = System.getProperty("g5.chat.full.redis.container");
            assertThat(containerName).matches("jchatmind-g5-full-redis-[0-9a-f]{8}");
            ProcessResult publishedPort = runDocker("port", containerName, "6379/tcp");
            assertThat(publishedPort.exitCode()).withFailMessage(publishedPort.output()).isZero();
            assertThat(publishedPort.output().trim())
                    .isEqualTo("127.0.0.1:" + requiredPort("g5.chat.http.redis.port"));
            ProcessResult appendOnly = runDocker("exec", containerName, "redis-cli", "CONFIG", "GET", "appendonly");
            assertThat(appendOnly.exitCode()).withFailMessage(appendOnly.output()).isZero();
            assertThat(appendOnly.output().toLowerCase()).contains("appendonly", "yes");
            ProcessResult appendFsync = runDocker("exec", containerName, "redis-cli", "CONFIG", "GET", "appendfsync");
            assertThat(appendFsync.exitCode()).withFailMessage(appendFsync.output()).isZero();
            assertThat(appendFsync.output().toLowerCase()).contains("appendfsync", "always");
            if (sigkillRedis) {
                ProcessResult kill = runDocker("kill", "--signal", "KILL", containerName);
                assertThat(kill.exitCode()).withFailMessage(kill.output()).isZero();
                ProcessResult start = runDocker("start", containerName);
                assertThat(start.exitCode()).withFailMessage(start.output()).isZero();
            } else {
                ProcessResult restart = runDocker("restart", "--timeout", "5", containerName);
                assertThat(restart.exitCode()).withFailMessage(restart.output()).isZero();
            }
            long recoveryDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            boolean recovered = false;
            while (System.nanoTime() < recoveryDeadline) {
                try {
                    recovered = eventStore.latestSequence(sessionId) == 71;
                    if (recovered) break;
                } catch (RuntimeException exception) {
                    recovered = false;
                }
                Thread.sleep(100);
            }
            assertThat(recovered).as("Full application Redis sequence after browser SSE failure").isTrue();
        }
        synchronized (state.lock) {
            state.clients.remove(emitter);
        }
        try {
            for (int index = 0; index < HISTORY_SIZE; index++) {
                service.send(sessionId, SseMessage.builder()
                        .type(SseMessage.Type.AI_THINKING)
                        .build());
            }
        } finally {
            emitter.complete();
        }
    }

    private static SseServiceImpl.SessionState awaitSessionClient(SseServiceImpl service, String sessionId)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            SseServiceImpl.SessionState state = sessionState(service, sessionId);
            if (state != null) {
                synchronized (state.lock) {
                    if (!state.clients.isEmpty()) {
                        return state;
                    }
                }
            }
            Thread.sleep(10);
        }
        throw new IllegalStateException("Browser did not connect to the live SSE endpoint");
    }

    @SuppressWarnings("unchecked")
    private static SseServiceImpl.SessionState sessionState(SseServiceImpl service, String sessionId) {
        ConcurrentMap<String, SseServiceImpl.SessionState> sessions =
                (ConcurrentMap<String, SseServiceImpl.SessionState>) ReflectionTestUtils.getField(service, "sessions");
        return sessions.get(sessionId);
    }

    private static void awaitUi(Process vite, String uiBaseUrl, Path viteLog) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            String output = Files.exists(viteLog) ? Files.readString(viteLog) : "";
            if (output.contains("G5_VITE_LISTENING")) {
                return;
            }
            if (!vite.isAlive()) {
                throw new IllegalStateException("G5 Vite server exited: " + output);
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("G5 Vite server did not become ready at " + uiBaseUrl
                + ", log=" + Files.readString(viteLog));
    }

    private static int availablePort() throws IOException {
        for (int attempt = 0; attempt < 5; attempt++) {
            try (ServerSocket socket = new ServerSocket(0)) {
                if (socket.getLocalPort() >= 49152) {
                    return socket.getLocalPort();
                }
            }
        }
        throw new IllegalStateException("Unable to reserve an isolated high UI port");
    }

    private static int requiredPort(String property) {
        int port = Integer.getInteger(property, 0);
        if (port < 49152 || port > 65535) {
            throw new IllegalStateException("An isolated high port is required for " + property);
        }
        return port;
    }
}
