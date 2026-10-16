package com.kama.jchatmind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.controller.ChatMessageController;
import com.kama.jchatmind.controller.SseController;
import com.kama.jchatmind.event.listener.ChatEventListener;
import com.kama.jchatmind.message.SseMessage;
import com.kama.jchatmind.service.MemoryExtractionResult;
import com.kama.jchatmind.service.UserMemoryFacadeService;
import com.kama.jchatmind.service.impl.G5ChatHttpRecoveryRuntimeL2Test;
import com.kama.jchatmind.service.impl.RedisChatSseEventStore;
import com.kama.jchatmind.service.impl.SseServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.zhipuai.ZhiPuAiChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jmx.export.annotation.AnnotationMBeanExporter;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;

@SpringBootTest(
        classes = {JchatmindApplication.class, G5FullApplicationHttpPreflightL2Test.IsolatedModels.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.config.name=g5-full-isolated",
                "spring.ai.model.chat=none",
                "spring.ai.model.embedding=none",
                "spring.ai.model.image=none",
                "spring.ai.mcp.client.enabled=false",
                "spring.ai.mcp.server.enabled=false",
                "spring.rabbitmq.listener.simple.auto-startup=false",
                "spring.rabbitmq.listener.direct.auto-startup=false",
                "spring.rabbitmq.dynamic=false",
                "spring.mail.host=127.0.0.1",
                "spring.mail.username=g5-isolated",
                "mybatis.mapper-locations=classpath*:mapper/*.xml",
                "mybatis.type-handlers-package=com.kama.jchatmind.typehandler",
                "ollama.base-url=http://127.0.0.1:55454",
                "ollama.embedding-model=g5-isolated",
                "document.storage.base-path=target/g5-full-documents",
                "server.address=127.0.0.1"
        }
)
@EnabledIfSystemProperty(named = "g5.chat.full.l2", matches = "true")
class G5FullApplicationHttpPreflightL2Test {

    private static final String JWT_SECRET = "g5-full-isolated-" + UUID.randomUUID() + UUID.randomUUID();

    @LocalServerPort
    private int port;

    @Autowired
    private ApplicationContext context;

    @DynamicPropertySource
    static void isolatedProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:postgresql://127.0.0.1:"
                + requiredPort("g5.chat.http.pg.port") + "/g5_chat_http_l2");
        registry.add("spring.datasource.username", () -> "g5_http");
        registry.add("spring.data.redis.host", () -> "127.0.0.1");
        registry.add("spring.data.redis.port", () -> requiredPort("g5.chat.http.redis.port"));
        registry.add("spring.rabbitmq.host", () -> "127.0.0.1");
        registry.add("spring.rabbitmq.port", () -> 55453);
        registry.add("jwt.secret", () -> JWT_SECRET);
        registry.add("jwt.expiration", () -> 3600);
    }

    @Test
    void shouldStartFullApplicationAndServeOwnedHistory() throws Exception {
        assertThat(Boolean.getBoolean("g5.chat.full.redis.sigkill.l2")
                && !Boolean.getBoolean("g5.chat.full.redis.restart.l2")).isFalse();
        assertThat(Boolean.getBoolean("g5.chat.full.redis.cross.instance.l2")
                && !Boolean.getBoolean("g5.chat.full.redis.restart.l2")).isFalse();
        assertThat(Boolean.getBoolean("g5.chat.full.redis.two.full.apps.l2")
                && !Boolean.getBoolean("g5.chat.full.redis.cross.instance.l2")).isFalse();
        assertThat(Boolean.getBoolean("g5.chat.full.redis.separate.jvm.l2")
                && (!Boolean.getBoolean("g5.chat.full.redis.cross.instance.l2")
                || Boolean.getBoolean("g5.chat.full.redis.two.full.apps.l2"))).isFalse();
        assertThat(Boolean.getBoolean("g5.chat.full.browser.l3")
                && Boolean.getBoolean("g5.chat.full.redis.restart.l2")).isFalse();
        assertThat(Boolean.getBoolean("g5.chat.full.browser.redis.restart.l3")
                && !Boolean.getBoolean("g5.chat.full.browser.l3")).isFalse();
        assertThat(Boolean.getBoolean("g5.chat.full.browser.redis.restart.l3")
                && Boolean.getBoolean("g5.chat.full.redis.restart.l2")).isFalse();
        assertThat(Boolean.getBoolean("g5.chat.full.browser.redis.sigkill.l3")
                && !Boolean.getBoolean("g5.chat.full.browser.l3")).isFalse();
        assertThat(Boolean.getBoolean("g5.chat.full.browser.redis.sigkill.l3")
                && Boolean.getBoolean("g5.chat.full.browser.redis.restart.l3")).isFalse();
        assertThat(Boolean.getBoolean("g5.chat.full.browser.redis.sigkill.l3")
                && Boolean.getBoolean("g5.chat.full.redis.restart.l2")).isFalse();
        assertThat(context.getBean(SseController.class)).isNotNull();
        assertThat(context.getBean(ChatMessageController.class)).isNotNull();
        assertThat(context.getBean(RedisChatSseEventStore.class)).isNotNull();

        HttpClient client = HttpClient.newHttpClient();
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                                + "/sse/connect/" + UUID.randomUUID()))
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(response.statusCode()).isEqualTo(401);

        JdbcTemplate jdbcTemplate = context.getBean(JdbcTemplate.class);
        assertThat(jdbcTemplate.queryForObject("SELECT current_database()", String.class))
                .isEqualTo("g5_chat_http_l2");
        assertThat(jdbcTemplate.queryForObject("SELECT current_user", String.class))
                .isEqualTo("g5_http");
        String sessionId = UUID.randomUUID().toString();
        String agentId = UUID.randomUUID().toString();
        String ownedKbId = UUID.randomUUID().toString();
        String otherKbId = UUID.randomUUID().toString();
        SseServiceImpl sseService = context.getBean(SseServiceImpl.class);
        RedisChatSseEventStore eventStore = context.getBean(RedisChatSseEventStore.class);
        try {
            jdbcTemplate.execute("CREATE TABLE jchatmind_user ("
                    + "user_id BIGINT PRIMARY KEY, account VARCHAR(128), username VARCHAR(128), "
                    + "password VARCHAR(128), gender SMALLINT, birthday DATE, avatar_url TEXT, "
                    + "email VARCHAR(255), school VARCHAR(255), signature TEXT, "
                    + "is_banned SMALLINT, is_admin SMALLINT, last_login_at TIMESTAMP, "
                    + "created_at TIMESTAMP, updated_at TIMESTAMP)");
            jdbcTemplate.execute("CREATE TABLE chat_session ("
                    + "id UUID PRIMARY KEY, user_id TEXT NOT NULL, agent_id UUID, title TEXT, "
                    + "metadata JSONB, created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL)");
            jdbcTemplate.execute("CREATE TABLE chat_message ("
                    + "id UUID PRIMARY KEY DEFAULT gen_random_uuid(), "
                    + "session_id UUID NOT NULL REFERENCES chat_session(id), "
                    + "role VARCHAR(32) NOT NULL, content TEXT NOT NULL, metadata JSONB, "
                    + "created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL)");
            if (Boolean.getBoolean("g5.chat.full.browser.l3")) {
                jdbcTemplate.execute("CREATE TABLE agent ("
                        + "id UUID PRIMARY KEY, user_id BIGINT NOT NULL, name TEXT, description TEXT, "
                        + "system_prompt TEXT, model TEXT, allowed_tools JSONB, chat_options JSONB, "
                        + "created_at TIMESTAMP, updated_at TIMESTAMP)");
                jdbcTemplate.execute("CREATE TABLE agent_knowledge_base ("
                        + "agent_id UUID, kb_id UUID, bound_by_user_id BIGINT, bound_at TIMESTAMP)");
                jdbcTemplate.execute("CREATE TABLE knowledge_base ("
                        + "id UUID PRIMARY KEY, name TEXT, description TEXT, metadata JSONB, "
                        + "owner_id BIGINT NOT NULL, created_at TIMESTAMP, updated_at TIMESTAMP)");
            }
            Timestamp createdAt = Timestamp.valueOf(LocalDateTime.of(2026, 10, 14, 0, 0));
            String password = UUID.randomUUID().toString() + UUID.randomUUID();
            String otherPassword = UUID.randomUUID().toString() + UUID.randomUUID();
            jdbcTemplate.update("INSERT INTO jchatmind_user "
                            + "(user_id, account, username, password, is_banned, is_admin, created_at, updated_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                    7L, "g5-full-user", "G5 full user",
                    context.getBean(PasswordEncoder.class).encode(password), 0, 0, createdAt, createdAt);
            jdbcTemplate.update("INSERT INTO chat_session "
                            + "(id, user_id, agent_id, title, created_at, updated_at) "
                            + "VALUES (?::uuid, ?, ?::uuid, ?, ?, ?)",
                    sessionId, "7", agentId, "G5 full application", createdAt, createdAt);
            if (Boolean.getBoolean("g5.chat.full.browser.l3")) {
                jdbcTemplate.update("INSERT INTO jchatmind_user "
                                + "(user_id, account, username, password, is_banned, is_admin, created_at, updated_at) "
                                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        8L, "g5-other-user", "G5 other user",
                        context.getBean(PasswordEncoder.class).encode(otherPassword),
                        0, 0, createdAt, createdAt);
                jdbcTemplate.update("INSERT INTO agent "
                                + "(id, user_id, name, description, system_prompt, model, "
                                + "allowed_tools, chat_options, created_at, updated_at) "
                                + "VALUES (?::uuid, ?, ?, ?, ?, ?, '[]'::jsonb, "
                                + "'{\"temperature\":0.7,\"topP\":1.0,\"messageLength\":10}'::jsonb, ?, ?)",
                        agentId, 7L, "G5 isolated agent", "isolated", "", "deepseek-chat",
                        createdAt, createdAt);
                jdbcTemplate.update("INSERT INTO knowledge_base "
                                + "(id, name, description, metadata, owner_id, created_at, updated_at) "
                                + "VALUES (?::uuid, ?, ?, '{}'::jsonb, ?, ?, ?)",
                        ownedKbId, "G5 owner KB", "isolated", 7L, createdAt, createdAt);
                jdbcTemplate.update("INSERT INTO knowledge_base "
                                + "(id, name, description, metadata, owner_id, created_at, updated_at) "
                                + "VALUES (?::uuid, ?, ?, '{}'::jsonb, ?, ?, ?)",
                        otherKbId, "G5 other KB", "isolated", 8L,
                        createdAt, createdAt);
            }
            for (int index = 0; index < 70; index++) {
                Timestamp messageTime = Timestamp.valueOf(createdAt.toLocalDateTime().plusSeconds(index));
                String role = index % 2 == 0 ? "user" : "assistant";
                String metadata = index % 2 == 0 ? null : "{\"toolCalls\":[]}";
                jdbcTemplate.update("INSERT INTO chat_message "
                                + "(id, session_id, role, content, metadata, created_at, updated_at) "
                                + "VALUES (?::uuid, ?::uuid, ?, ?, ?::jsonb, ?, ?)",
                        UUID.randomUUID().toString(), sessionId,
                        role, "history-" + index, metadata, messageTime, messageTime);
            }

            for (int index = 0; index < 70; index++) {
                sseService.send(sessionId, SseMessage.builder()
                        .type(SseMessage.Type.AI_THINKING)
                        .build());
            }
            assertThat(eventStore.latestSequence(sessionId)).isEqualTo(70);
            HttpResponse<String> login = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                                    + "/api/users/login"))
                            .timeout(Duration.ofSeconds(10))
                            .header("Content-Type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(context.getBean(ObjectMapper.class)
                                    .writeValueAsString(Map.of("account", "g5-full-user", "password", password))))
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertThat(login.statusCode()).isEqualTo(200);
            JsonNode loginBody = context.getBean(ObjectMapper.class).readTree(login.body());
            assertThat(loginBody.path("code").asInt()).isEqualTo(200);
            assertThat(loginBody.path("data").path("userId").asLong()).isEqualTo(7L);
            String token = loginBody.path("data").path("token").asText();
            assertThat(token).isNotBlank();
            assertReplayGap(client, token, sessionId, 70);
            HttpResponse<InputStream> replay = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                                    + "/sse/connect/" + sessionId))
                            .timeout(Duration.ofSeconds(10))
                            .header("Authorization", "Bearer " + token)
                            .header("Last-Event-ID", "69")
                            .header("Accept", "text/event-stream")
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofInputStream()
            );
            assertThat(replay.statusCode()).isEqualTo(200);
            try (InputStream stream = replay.body()) {
                String firstEvent = CompletableFuture.supplyAsync(() -> readFirstEvent(stream))
                        .get(5, TimeUnit.SECONDS);
                assertThat(firstEvent).contains("id:70", "event:message", "AI_THINKING");
            }
            if (Boolean.getBoolean("g5.chat.full.redis.restart.l2")) {
                String containerName = System.getProperty("g5.chat.full.redis.container");
                assertThat(containerName).matches("jchatmind-g5-full-redis-[0-9a-f]{8}");
                ProcessResult publishedPort = runDocker("port", containerName, "6379/tcp");
                assertThat(publishedPort.exitCode()).withFailMessage(publishedPort.output()).isZero();
                assertThat(publishedPort.output().trim())
                        .isEqualTo("127.0.0.1:" + requiredPort("g5.chat.http.redis.port"));
                HttpResponse<InputStream> liveResponse = client.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                                        + "/sse/connect/" + sessionId))
                                .timeout(Duration.ofSeconds(10))
                                .header("Authorization", "Bearer " + token)
                                .header("Accept", "text/event-stream")
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofInputStream()
                );
                assertThat(liveResponse.statusCode()).isEqualTo(200);
                try (ConfigurableApplicationContext publisher =
                             Boolean.getBoolean("g5.chat.full.redis.cross.instance.l2")
                                     && !Boolean.getBoolean("g5.chat.full.redis.separate.jvm.l2")
                                     ? (Boolean.getBoolean("g5.chat.full.redis.two.full.apps.l2")
                                             ? startSecondFullApplication() : startIndependentPublisher())
                                     : null;
                     InputStream liveStream = liveResponse.body()) {
                    if (publisher != null) {
                        if (Boolean.getBoolean("g5.chat.full.redis.two.full.apps.l2")) {
                            assertThat(publisher.getBean(JchatmindApplication.class)).isNotNull();
                            assertThat(publisher.getBean(SseController.class)).isNotNull();
                        }
                        assertThat(publisher.getBean(RedisChatSseEventStore.class)
                                .latestSequence(sessionId)).isEqualTo(70);
                    }
                    CompletableFuture<String> liveEvent = CompletableFuture.supplyAsync(
                            () -> readFirstMessageEvent(liveStream));
                    if (Boolean.getBoolean("g5.chat.full.redis.sigkill.l2")) {
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
                            recovered = eventStore.latestSequence(sessionId) == 70;
                            if (recovered) {
                                break;
                            }
                        } catch (RuntimeException exception) {
                            recovered = false;
                        }
                        Thread.sleep(100);
                    }
                    assertThat(recovered).as("Full application Redis sequence after AOF restart").isTrue();
                    assertReplayGap(client, token, sessionId, 70);
                    if (Boolean.getBoolean("g5.chat.full.redis.separate.jvm.l2")) {
                        assertThat(runFullAppProcessProbe(sessionId)).isTrue();
                        assertThat(liveEvent.get(5, TimeUnit.SECONDS))
                                .contains("id:71", "event:message", "full-app-separate-jvm");
                        assertThat(eventStore.latestSequence(sessionId)).isEqualTo(90);
                    } else if (publisher == null) {
                        sseService.send(sessionId, SseMessage.builder()
                                .type(SseMessage.Type.AI_THINKING)
                                .payload(SseMessage.Payload.builder()
                                        .statusText("full-app-after-restart")
                                        .build())
                                .build());
                        assertThat(eventStore.latestSequence(sessionId)).isEqualTo(71);
                        assertThat(liveEvent.get(5, TimeUnit.SECONDS))
                                .contains("id:71", "event:message", "full-app-after-restart");
                    } else {
                        SseServiceImpl remoteSender = publisher.getBean(SseServiceImpl.class);
                        String delivered = null;
                        for (int attempt = 0; attempt < 20 && delivered == null; attempt++) {
                            remoteSender.send(sessionId, SseMessage.builder()
                                    .type(SseMessage.Type.AI_THINKING)
                                    .payload(SseMessage.Payload.builder()
                                            .statusText("full-app-cross-instance-" + attempt)
                                            .build())
                                    .build());
                            try {
                                delivered = liveEvent.get(500, TimeUnit.MILLISECONDS);
                            } catch (TimeoutException exception) {
                                // Wait for the full application's Redis listener to resubscribe.
                            }
                        }
                        assertThat(delivered)
                                .contains("id:71", "event:message", "full-app-cross-instance");
                        assertThat(eventStore.latestSequence(sessionId)).isGreaterThanOrEqualTo(71);
                    }
                }
            }
            HttpResponse<String> history = client.send(
                    HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                                    + "/api/chat-messages/session/" + sessionId))
                            .timeout(Duration.ofSeconds(10))
                            .header("Authorization", "Bearer " + token)
                            .GET()
                            .build(),
                    HttpResponse.BodyHandlers.ofString()
            );
            assertThat(history.statusCode()).isEqualTo(200);
            JsonNode body = context.getBean(ObjectMapper.class).readTree(history.body());
            assertThat(body.path("code").asInt()).isEqualTo(200);
            JsonNode messages = body.path("data").path("chatMessages");
            assertThat(messages.size()).isEqualTo(70);
            assertThat(messages.get(0).path("content").asText()).isEqualTo("history-0");
            assertThat(messages.get(69).path("content").asText()).isEqualTo("history-69");
            if (Boolean.getBoolean("g5.chat.full.browser.l3")) {
                assertThat(mockingDetails(context.getBean(DeepSeekChatModel.class)).isMock()).isTrue();
                assertThat(mockingDetails(context.getBean(ZhiPuAiChatModel.class)).isMock()).isTrue();
                assertThat(mockingDetails(context.getBean(UserMemoryFacadeService.class)).isMock()).isTrue();
                HttpResponse<String> otherLogin = client.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                                        + "/api/users/login"))
                                .timeout(Duration.ofSeconds(10))
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(context.getBean(ObjectMapper.class)
                                        .writeValueAsString(Map.of("account", "g5-other-user",
                                                "password", otherPassword))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString()
                );
                assertThat(otherLogin.statusCode()).isEqualTo(200);
                JsonNode otherLoginBody = context.getBean(ObjectMapper.class).readTree(otherLogin.body());
                assertThat(otherLoginBody.path("code").asInt()).isEqualTo(200);
                assertThat(otherLoginBody.path("data").path("userId").asLong()).isEqualTo(8L);
                String otherToken = otherLoginBody.path("data").path("token").asText();
                assertThat(otherToken).isNotBlank();
                HttpResponse<String> forbiddenHistory = client.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                                        + "/api/chat-messages/session/" + sessionId))
                                .timeout(Duration.ofSeconds(10))
                                .header("Authorization", "Bearer " + otherToken)
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString()
                );
                assertThat(forbiddenHistory.statusCode()).isEqualTo(200);
                JsonNode forbiddenBody = context.getBean(ObjectMapper.class).readTree(forbiddenHistory.body());
                assertThat(forbiddenBody.path("code").asInt()).isEqualTo(500);
                assertThat(forbiddenBody.path("data").isNull()).isTrue();
                assertThat(forbiddenHistory.body()).doesNotContain("history-0", "history-69");
                HttpResponse<String> forbiddenSse = client.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                                        + "/sse/connect/" + sessionId))
                                .timeout(Duration.ofSeconds(10))
                                .header("Authorization", "Bearer " + otherToken)
                                .header("Accept", "text/event-stream")
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString()
                );
                assertThat(forbiddenSse.statusCode()).isEqualTo(404);
                Long messageCountBefore = jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM chat_message WHERE session_id = ?::uuid",
                        Long.class, sessionId);
                long sequenceBefore = eventStore.latestSequence(sessionId);
                HttpResponse<String> forbiddenWrite = client.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                                        + "/api/chat-messages"))
                                .timeout(Duration.ofSeconds(10))
                                .header("Authorization", "Bearer " + otherToken)
                                .header("Content-Type", "application/json")
                                .POST(HttpRequest.BodyPublishers.ofString(context.getBean(ObjectMapper.class)
                                        .writeValueAsString(Map.of(
                                                "agentId", agentId,
                                                "sessionId", sessionId,
                                                "role", "user",
                                                "content", "g5-cross-owner-rejected"))))
                                .build(),
                        HttpResponse.BodyHandlers.ofString()
                );
                assertThat(forbiddenWrite.statusCode()).isEqualTo(200);
                JsonNode forbiddenWriteBody = context.getBean(ObjectMapper.class)
                        .readTree(forbiddenWrite.body());
                assertThat(forbiddenWriteBody.path("code").asInt()).isEqualTo(500);
                assertThat(forbiddenWriteBody.path("message").asText()).contains("聊天会话不存在");
                assertThat(forbiddenWriteBody.path("data").isNull()).isTrue();
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM chat_message WHERE session_id = ?::uuid",
                        Long.class, sessionId)).isEqualTo(messageCountBefore);
                assertThat(eventStore.latestSequence(sessionId)).isEqualTo(sequenceBefore);
                completeSessionEmitters(sseService, sessionId);
                String ownerContent = "g5-owner-send-" + UUID.randomUUID();
                G5ChatHttpRecoveryRuntimeL2Test.assertBrowserRecovery(
                        port, sessionId, token, sseService, eventStore, true, true,
                        ownedKbId, password, otherKbId, otherPassword, ownerContent,
                        Boolean.getBoolean("g5.chat.full.browser.redis.restart.l3"),
                        Boolean.getBoolean("g5.chat.full.browser.redis.sigkill.l3"));
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM chat_message WHERE session_id = ?::uuid AND content = ?",
                        Long.class, sessionId, ownerContent)).isEqualTo(1L);
                assertThat(jdbcTemplate.queryForObject(
                        "SELECT COUNT(*) FROM chat_message WHERE session_id = ?::uuid "
                                + "AND role = 'assistant' AND content = ?",
                        Long.class, sessionId, "G5 mocked agent reply")).isEqualTo(1L);
                ChatEventListener chatEventListener = context.getBean(ChatEventListener.class);
                boolean agentTaskExecutorIdle = false;
                for (int attempt = 0; attempt < 100; attempt++) {
                    ChatEventListener.ExecutorSnapshot snapshot = chatEventListener.getExecutorSnapshot();
                    if (snapshot.activeThreads() == 0 && snapshot.queuedTasks() == 0) {
                        agentTaskExecutorIdle = true;
                        break;
                    }
                    Thread.sleep(50);
                }
                assertThat(agentTaskExecutorIdle).isTrue();
            }
        } finally {
            try {
                completeSessionEmitters(sseService, sessionId);
                ReflectionTestUtils.invokeMethod(eventStore, "delete", sessionId);
            } finally {
                jdbcTemplate.execute("DROP TABLE IF EXISTS chat_message, chat_session, "
                        + "agent_knowledge_base, agent, knowledge_base, jchatmind_user CASCADE");
            }
        }
    }

    private void assertReplayGap(HttpClient client, String token, String sessionId, long expectedId)
            throws Exception {
        HttpResponse<InputStream> gap = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                                + "/sse/connect/" + sessionId))
                        .timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer " + token)
                        .header("Last-Event-ID", "0")
                        .header("Accept", "text/event-stream")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream()
        );
        assertThat(gap.statusCode()).isEqualTo(200);
        try (InputStream stream = gap.body()) {
            String firstEvent = CompletableFuture.supplyAsync(() -> readFirstEvent(stream))
                    .get(5, TimeUnit.SECONDS);
            assertThat(firstEvent).contains("id:" + expectedId, "event:replay-gap",
                    "data:history_window_exceeded");
        }
    }

    private static AnnotationConfigApplicationContext startIndependentPublisher() {
        LettuceConnectionFactory factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(
                "127.0.0.1", requiredPort("g5.chat.http.redis.port")));
        AnnotationConfigApplicationContext publisher = new AnnotationConfigApplicationContext();
        publisher.registerBean(RedisConnectionFactory.class, () -> factory);
        publisher.registerBean(StringRedisTemplate.class, () -> new StringRedisTemplate(factory));
        publisher.registerBean(ObjectMapper.class, () -> new ObjectMapper());
        publisher.register(RedisChatSseEventStore.class, SseServiceImpl.class);
        try {
            publisher.refresh();
            return publisher;
        } catch (RuntimeException exception) {
            publisher.close();
            throw exception;
        }
    }

    static ConfigurableApplicationContext startSecondFullApplication() {
        return new SpringApplicationBuilder(JchatmindApplication.class, IsolatedModels.class)
                .web(WebApplicationType.SERVLET)
                .properties(
                        "spring.config.name=g5-full-isolated",
                        "spring.ai.model.chat=none",
                        "spring.ai.model.embedding=none",
                        "spring.ai.model.image=none",
                        "spring.ai.mcp.client.enabled=false",
                        "spring.ai.mcp.server.enabled=false",
                        "spring.rabbitmq.listener.simple.auto-startup=false",
                        "spring.rabbitmq.listener.direct.auto-startup=false",
                        "spring.rabbitmq.dynamic=false",
                        "spring.rabbitmq.host=127.0.0.1",
                        "spring.rabbitmq.port=55453",
                        "spring.mail.host=127.0.0.1",
                        "spring.mail.username=g5-isolated",
                        "mybatis.mapper-locations=classpath*:mapper/*.xml",
                        "mybatis.type-handlers-package=com.kama.jchatmind.typehandler",
                        "ollama.base-url=http://127.0.0.1:55454",
                        "ollama.embedding-model=g5-isolated",
                        "document.storage.base-path=target/g5-full-documents",
                        "spring.datasource.url=jdbc:postgresql://127.0.0.1:"
                                + requiredPort("g5.chat.http.pg.port") + "/g5_chat_http_l2",
                        "spring.datasource.username=g5_http",
                        "spring.data.redis.host=127.0.0.1",
                        "spring.data.redis.port=" + requiredPort("g5.chat.http.redis.port"),
                        "jwt.secret=" + JWT_SECRET,
                        "jwt.expiration=3600",
                        "server.address=127.0.0.1",
                        "server.port=0"
                )
                .run();
    }

    private static boolean runFullAppProcessProbe(String sessionId) throws Exception {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        String classPath = System.getProperty("surefire.test.class.path");
        assertThat(classPath).isNotBlank();
        Path log = Path.of("target", "g5-full-app-publisher-process.log").toAbsolutePath();
        Process process = new ProcessBuilder(
                javaExecutable,
                "-Dspring.devtools.restart.enabled=false",
                "-Dg5.chat.http.pg.port=" + requiredPort("g5.chat.http.pg.port"),
                "-Dg5.chat.http.redis.port=" + requiredPort("g5.chat.http.redis.port"),
                "-cp", classPath,
                G5FullApplicationSsePublisherProcessProbe.class.getName(), sessionId
        ).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new IllegalStateException("Full application publisher process timed out");
        }
        assertThat(process.exitValue()).as("Full application publisher process exit code").isZero();
        return Files.readString(log).contains("G5_FULL_APP_PUBLISHED:90");
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

    private static void completeSessionEmitters(SseServiceImpl service, String sessionId) {
        Map<?, ?> sessions = (Map<?, ?>) ReflectionTestUtils.getField(service, "sessions");
        Object state = sessions.get(sessionId);
        if (state == null) {
            return;
        }
        Object lock = ReflectionTestUtils.getField(state, "lock");
        Collection<?> clients = (Collection<?>) ReflectionTestUtils.getField(state, "clients");
        List<SseEmitter> emitters;
        synchronized (lock) {
            emitters = clients.stream().map(SseEmitter.class::cast).toList();
            clients.clear();
        }
        emitters.forEach(SseEmitter::complete);
    }

    private static int requiredPort(String property) {
        int port = Integer.getInteger(property, 0);
        if (port < 49152 || port > 65535) {
            throw new IllegalStateException("An isolated high port is required for " + property);
        }
        return port;
    }

    @TestConfiguration
    static class IsolatedModels {

        @Bean
        static BeanPostProcessor isolatedMBeanExporter() {
            MBeanServer server = MBeanServerFactory.createMBeanServer("g5-full-" + UUID.randomUUID());
            return new BeanPostProcessor() {
                @Override
                public Object postProcessBeforeInitialization(Object bean, String beanName) {
                    if (bean instanceof AnnotationMBeanExporter exporter) {
                        exporter.setServer(server);
                    }
                    return bean;
                }
            };
        }

        @Bean
        DeepSeekChatModel deepSeekChatModel() {
            DeepSeekChatModel model = mock(DeepSeekChatModel.class);
            ChatResponse response = new ChatResponse(List.of(new Generation(
                    AssistantMessage.builder().content("G5 mocked agent reply").toolCalls(List.of()).build())));
            when(model.call(any(Prompt.class))).thenReturn(response);
            when(model.stream(any(Prompt.class))).thenReturn(Flux.just(response));
            return model;
        }

        @Bean
        ZhiPuAiChatModel zhiPuAiChatModel() {
            return mock(ZhiPuAiChatModel.class);
        }

        @Bean
        @Primary
        UserMemoryFacadeService isolatedUserMemoryFacadeService() {
            UserMemoryFacadeService service = mock(UserMemoryFacadeService.class);
            when(service.getConfirmedMemories(anyString())).thenReturn(List.of());
            when(service.recallRelevantMemories(anyString(), anyString(), anyInt())).thenReturn(List.of());
            when(service.extractMemoryCandidates(anyString(), anyString()))
                    .thenReturn(MemoryExtractionResult.SKIPPED);
            return service;
        }
    }
}
