package com.kama.jchatmind;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.controller.ChatMessageController;
import com.kama.jchatmind.controller.SseController;
import com.kama.jchatmind.event.listener.ChatEventListener;
import com.kama.jchatmind.event.listener.AgentExecutionTaskWorker;
import com.kama.jchatmind.event.ChatEvent;
import com.kama.jchatmind.event.listener.AgentExecutionQueueMetrics;
import com.kama.jchatmind.event.listener.AgentExecutionTaskRunner;
import com.kama.jchatmind.message.SseMessage;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.service.ChatMessageFacadeService;
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.jmx.export.annotation.AnnotationMBeanExporter;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
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
        registry.add("agent.execution.queue.enabled",
                () -> !Boolean.getBoolean("g5.chat.full.queue.two.jvm.l2")
                        && !Boolean.getBoolean("g5.chat.full.packaged.restart.l2"));
        registry.add("g5.chat.full.queue.probe",
                () -> Boolean.getBoolean("g5.chat.full.queue.two.jvm.l2"));
        registry.add("g5.chat.full.queue.probe.worker-id", () -> "parent");
        createIsolatedQueueAndWebhookSchema();
    }

    private static void createIsolatedQueueAndWebhookSchema() {
        String url = "jdbc:postgresql://127.0.0.1:"
                + requiredPort("g5.chat.http.pg.port") + "/g5_chat_http_l2";
        try (Connection connection = DriverManager.getConnection(url, "g5_http", "");
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS chat_session ("
                    + "id UUID PRIMARY KEY, user_id TEXT NOT NULL, agent_id UUID, title TEXT, "
                    + "metadata JSONB, created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS chat_message ("
                    + "id UUID PRIMARY KEY DEFAULT gen_random_uuid(), "
                    + "session_id UUID NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE, "
                    + "role VARCHAR(32) NOT NULL, content TEXT NOT NULL, metadata JSONB, "
                    + "created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL)");
            String executionQueueMigration = Files.readString(Path.of("..", "sql", "chat",
                    "2026-10-21-create-agent-execution-queue.sql"));
            statement.execute(executionQueueMigration);
            statement.execute("CREATE TABLE IF NOT EXISTS g5_agent_queue_probe_execution ("
                    + "worker_id TEXT NOT NULL, process_id BIGINT NOT NULL, session_id UUID NOT NULL, "
                    + "user_message_id UUID NOT NULL, sequence_no BIGINT NOT NULL, "
                    + "started_at TIMESTAMP NOT NULL, finished_at TIMESTAMP, "
                    + "PRIMARY KEY (worker_id, user_message_id))");
            statement.execute("CREATE TABLE webhook_delivery_outbox ("
                    + "outbox_id UUID PRIMARY KEY, callback_event_id UUID NOT NULL UNIQUE, "
                    + "source_id VARCHAR(128) NOT NULL, event_type VARCHAR(32) NOT NULL, "
                    + "aggregate_id UUID NOT NULL, event_payload TEXT NOT NULL, "
                    + "payload_sha256 VARCHAR(64) NOT NULL, delivery_status VARCHAR(16) NOT NULL DEFAULT 'PENDING', "
                    + "attempt_count INTEGER NOT NULL DEFAULT 0, max_attempts INTEGER NOT NULL DEFAULT 5, "
                    + "next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "last_http_status INTEGER, last_error_code VARCHAR(64), "
                    + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "delivered_at TIMESTAMP, dead_lettered_at TIMESTAMP)");
        } catch (Exception exception) {
            throw new IllegalStateException("无法准备隔离 Webhook dispatcher 测试表", exception);
        }
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
        assertThat(Boolean.getBoolean("g5.chat.full.queue.two.jvm.l2")
                && Boolean.getBoolean("g5.chat.full.browser.l3")).isFalse();
        assertThat(Boolean.getBoolean("g5.chat.full.packaged.restart.l2")
                && (Boolean.getBoolean("g5.chat.full.queue.two.jvm.l2")
                || Boolean.getBoolean("g5.chat.full.browser.l3")
                || Boolean.getBoolean("g5.chat.full.redis.restart.l2"))).isFalse();
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
        boolean persistentQueueEnabled = !Boolean.getBoolean("g5.chat.full.queue.two.jvm.l2")
                && !Boolean.getBoolean("g5.chat.full.packaged.restart.l2");
        if (persistentQueueEnabled) {
            assertThat(context.getBeansOfType(AgentExecutionQueueMetrics.class)).hasSize(1);
            ObjectName queueMetricsName = new ObjectName(
                    "com.kama.jchatmind:type=AgentExecutionQueueMetrics");
            assertThat(MBeanServerFactory.findMBeanServer(null))
                    .anySatisfy(server -> assertThat(server.isRegistered(queueMetricsName)).isTrue());
        }

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
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS chat_session ("
                    + "id UUID PRIMARY KEY, user_id TEXT NOT NULL, agent_id UUID, title TEXT, "
                    + "metadata JSONB, created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL)");
            jdbcTemplate.execute("CREATE TABLE IF NOT EXISTS chat_message ("
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
            if (Boolean.getBoolean("g5.chat.full.queue.two.jvm.l2")) {
                runFullApplicationQueueWorkerCompetition(client, context.getBean(ObjectMapper.class),
                        jdbcTemplate, token);
            }
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
            completeSessionEmitters(sseService, sessionId);
            if (Boolean.getBoolean("g5.chat.full.packaged.restart.l2")) {
                verifyPackagedApplicationRestart(client, context.getBean(ObjectMapper.class), token,
                        sessionId, eventStore, sseService);
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
                    long expectedLiveSequence = Boolean.getBoolean("g5.chat.full.redis.separate.jvm.l2")
                            ? 110L : 71L;
                    CompletableFuture<String> liveEvent = CompletableFuture.supplyAsync(
                            () -> readMessageEventsThrough(liveStream, expectedLiveSequence));
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
                        assertThat(runFullAppProcessProbe(sessionId)).isEqualTo(90L);
                        assertThat(runFullAppProcessProbe(sessionId)).isEqualTo(110L);
                        assertThat(liveEvent.get(5, TimeUnit.SECONDS))
                                .contains("id:71", "event:message", "full-app-separate-jvm");
                        assertThat(eventStore.latestSequence(sessionId)).isEqualTo(110);
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
                    completeSessionEmitters(sseService, sessionId);
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
            if (Boolean.getBoolean("g5.chat.full.process.restart.l2")) {
                assertThat(runFullAppHttpRestartProcessProbe(sessionId, 70L)).isEqualTo(71L);
                assertThat(runFullAppHttpRestartProcessProbe(sessionId, 71L)).isEqualTo(72L);
                assertThat(eventStore.latestSequence(sessionId)).isEqualTo(72L);
            }
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
                boolean persistedTaskCompleted = false;
                for (int attempt = 0; attempt < 100; attempt++) {
                    Long completedTaskCount = jdbcTemplate.queryForObject(
                            "SELECT COUNT(*) FROM agent_execution_task task "
                                    + "JOIN chat_message user_message ON user_message.id = task.user_message_id "
                                    + "WHERE task.session_id = ?::uuid AND user_message.content = ? "
                                    + "AND task.status = 'COMPLETED'",
                            Long.class, sessionId, ownerContent);
                    if (completedTaskCount != null && completedTaskCount == 1L) {
                        persistedTaskCompleted = true;
                        break;
                    }
                    Thread.sleep(50);
                }
                assertThat(persistedTaskCompleted).isTrue();
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
                stopPersistentQueuePolling();
                jdbcTemplate.execute("DROP TABLE IF EXISTS chat_message, chat_session, "
                        + "agent_execution_task, agent_execution_turn_message, agent_execution_session_sequence, "
                        + "g5_agent_queue_probe_execution, webhook_delivery_outbox, "
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

    private void verifyPackagedApplicationRestart(
            HttpClient client,
            ObjectMapper objectMapper,
            String token,
            String sessionId,
            RedisChatSseEventStore eventStore,
            SseServiceImpl sseService
    ) throws Exception {
        Path jar = Path.of("target", "jchatmind-0.0.1-SNAPSHOT.jar").toAbsolutePath();
        assertThat(Files.exists(jar)).as("packaged Spring Boot executable JAR").isTrue();
        Path processDirectory = Path.of("target", "g5-packaged-restart-" + UUID.randomUUID()).toAbsolutePath();
        Files.createDirectories(processDirectory);
        Path configFile = processDirectory.resolve("isolated.properties");
        Properties properties = new Properties();
        properties.setProperty("spring.main.web-application-type", "servlet");
        properties.setProperty("spring.application.name", "g5-packaged-restart-l2");
        properties.setProperty("spring.ai.model.embedding", "none");
        properties.setProperty("spring.ai.model.image", "none");
        properties.setProperty("spring.ai.deepseek.api-key", "isolated-" + UUID.randomUUID());
        properties.setProperty("spring.ai.deepseek.base-url", "http://127.0.0.1:55454");
        properties.setProperty("spring.ai.zhipuai.api-key", "isolated-" + UUID.randomUUID());
        properties.setProperty("spring.ai.zhipuai.base-url", "http://127.0.0.1:55454");
        properties.setProperty("spring.ai.mcp.client.enabled", "false");
        properties.setProperty("spring.ai.mcp.server.enabled", "false");
        properties.setProperty("spring.rabbitmq.listener.simple.auto-startup", "false");
        properties.setProperty("spring.rabbitmq.listener.direct.auto-startup", "false");
        properties.setProperty("spring.rabbitmq.dynamic", "false");
        properties.setProperty("agent.execution.queue.enabled", "false");
        properties.setProperty("spring.rabbitmq.host", "127.0.0.1");
        properties.setProperty("spring.rabbitmq.port", "55453");
        properties.setProperty("spring.mail.host", "127.0.0.1");
        properties.setProperty("spring.mail.username", "g5-isolated");
        properties.setProperty("mybatis.mapper-locations", "classpath*:mapper/*.xml");
        properties.setProperty("mybatis.type-handlers-package", "com.kama.jchatmind.typehandler");
        properties.setProperty("ollama.base-url", "http://127.0.0.1:55454");
        properties.setProperty("ollama.embedding-model", "g5-isolated");
        properties.setProperty("document.storage.base-path", "target/g5-packaged-documents");
        properties.setProperty("spring.datasource.url", "jdbc:postgresql://127.0.0.1:"
                + requiredPort("g5.chat.http.pg.port") + "/g5_chat_http_l2");
        properties.setProperty("spring.datasource.username", "g5_http");
        properties.setProperty("spring.datasource.password", "");
        properties.setProperty("spring.data.redis.host", "127.0.0.1");
        properties.setProperty("spring.data.redis.port", Integer.toString(requiredPort("g5.chat.http.redis.port")));
        properties.setProperty("jwt.secret", JWT_SECRET);
        properties.setProperty("jwt.expiration", "3600");
        Files.writeString(configFile, propertiesToText(properties));

        int firstPort = findFreePort();
        Path firstLog = processDirectory.resolve("first.log");
        Process first = startPackagedApplication(jar, configFile, firstPort, firstLog);
        int secondPort = findFreePort();
        Path secondLog = processDirectory.resolve("second.log");
        Process second = null;
        try {
            awaitPackagedApplication(client, first, firstPort, sessionId, firstLog);
            assertPackagedHistory(client, objectMapper, firstPort, sessionId, token);
            assertPackagedReplay(client, firstPort, sessionId, token, 69, 70);
            stopPackagedApplication(first);

            sseService.send(sessionId, SseMessage.builder()
                    .type(SseMessage.Type.AI_THINKING)
                    .payload(SseMessage.Payload.builder().statusText("packaged-jvm-restart").build())
                    .build());
            assertThat(eventStore.latestSequence(sessionId)).isEqualTo(71);

            second = startPackagedApplication(jar, configFile, secondPort, secondLog);
            awaitPackagedApplication(client, second, secondPort, sessionId, secondLog);
            assertPackagedHistory(client, objectMapper, secondPort, sessionId, token);
            assertPackagedReplay(client, secondPort, sessionId, token, 70, 71);
            assertThat(eventStore.latestSequence(sessionId)).isEqualTo(71);
            stopPackagedApplication(second);
        } finally {
            stopPackagedApplication(first);
            if (second != null) {
                stopPackagedApplication(second);
            }
            Files.deleteIfExists(configFile);
            deletePackagedProcessFile(firstLog);
            deletePackagedProcessFile(secondLog);
            Files.deleteIfExists(processDirectory);
        }
    }

    private static void deletePackagedProcessFile(Path file) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (true) {
            try {
                Files.deleteIfExists(file);
                return;
            } catch (IOException exception) {
                if (System.nanoTime() >= deadline) {
                    throw exception;
                }
                Thread.sleep(100);
            }
        }
    }

    private static String propertiesToText(Properties properties) throws IOException {
        try (java.io.StringWriter writer = new java.io.StringWriter()) {
            properties.store(writer, "isolated packaged application runtime");
            return writer.toString();
        }
    }

    private static int findFreePort() throws IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static Process startPackagedApplication(Path jar, Path configFile, int port, Path logFile)
            throws IOException {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        return new ProcessBuilder(
                javaExecutable,
                "-Dspring.devtools.restart.enabled=false",
                "-jar",
                jar.toString(),
                "--spring.config.location=" + configFile.toUri(),
                "--server.address=127.0.0.1",
                "--server.port=" + port
        ).redirectErrorStream(true).redirectOutput(logFile.toFile()).start();
    }

    private static void awaitPackagedApplication(
            HttpClient client,
            Process process,
            int port,
            String sessionId,
            Path logFile
    ) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < deadline && process.isAlive()) {
            try {
                HttpResponse<String> response = client.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                                        + "/sse/connect/" + sessionId))
                                .timeout(Duration.ofSeconds(2))
                                .GET()
                                .build(),
                        HttpResponse.BodyHandlers.ofString()
                );
                if (response.statusCode() == 401) {
                    return;
                }
            } catch (Exception ignored) {
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Packaged application failed to become ready: "
                + (Files.exists(logFile) ? Files.readString(logFile) : "no output"));
    }

    private static void assertPackagedHistory(
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
        JsonNode messages = objectMapper.readTree(history.body()).path("data").path("chatMessages");
        assertThat(history.statusCode()).isEqualTo(200);
        assertThat(messages.size()).isEqualTo(70);
        assertThat(messages.get(0).path("content").asText()).isEqualTo("history-0");
        assertThat(messages.get(69).path("content").asText()).isEqualTo("history-69");
    }

    private static void assertPackagedReplay(
            HttpClient client,
            int port,
            String sessionId,
            String token,
            long cursor,
            long expectedId
    ) throws Exception {
        HttpResponse<InputStream> replay = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port
                                + "/sse/connect/" + sessionId))
                        .timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer " + token)
                        .header("Last-Event-ID", Long.toString(cursor))
                        .header("Accept", "text/event-stream")
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofInputStream()
        );
        assertThat(replay.statusCode()).isEqualTo(200);
        try (InputStream stream = replay.body()) {
            String event = CompletableFuture.supplyAsync(() -> readFirstEvent(stream))
                    .get(5, TimeUnit.SECONDS);
            assertThat(event).contains("id:" + expectedId, "event:message");
            if (expectedId == 71) {
                assertThat(event).contains("packaged-jvm-restart");
            }
        }
    }

    private static void stopPackagedApplication(Process process) throws Exception {
        if (process.isAlive()) {
            process.destroy();
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(10, TimeUnit.SECONDS);
            }
        }
    }

    private void runFullApplicationQueueWorkerCompetition(
            HttpClient client,
            ObjectMapper objectMapper,
            JdbcTemplate jdbcTemplate,
            String token
    ) throws Exception {
        assertThat(context.getBeansOfType(com.kama.jchatmind.event.listener.AgentExecutionTaskWorker.class))
                .isEmpty();
        Path processDirectory = Path.of("target", "g5-full-app-queue-" + UUID.randomUUID())
                .toAbsolutePath();
        Files.createDirectories(processDirectory);
        Path workerAReady = processDirectory.resolve("worker-a-ready");
        Path workerAStop = processDirectory.resolve("worker-a-stop");
        Path workerALog = processDirectory.resolve("worker-a.log");
        Path workerBReady = processDirectory.resolve("worker-b-ready");
        Path workerBStop = processDirectory.resolve("worker-b-stop");
        Path workerBLog = processDirectory.resolve("worker-b.log");
        List<Process> workers = new ArrayList<>();
        List<String> sessionIds = new ArrayList<>();
        try {
            workers.add(startFullApplicationQueueWorkerProcess("worker-a", workerAReady, workerAStop, workerALog));
            awaitFullApplicationQueueWorker(workerAReady, workers.get(0), workerALog);
            workers.add(startFullApplicationQueueWorkerProcess("worker-b", workerBReady, workerBStop, workerBLog));
            awaitFullApplicationQueueWorker(workerBReady, workers.get(1), workerBLog);

            LocalDateTime createdAt = LocalDateTime.now();
            String agentId = UUID.randomUUID().toString();
            String secondTurnMessageId = null;
            for (int index = 0; index < 15; index++) {
                String sessionId = UUID.randomUUID().toString();
                sessionIds.add(sessionId);
                jdbcTemplate.update("INSERT INTO chat_session "
                                + "(id, user_id, agent_id, title, created_at, updated_at) "
                                + "VALUES (?::uuid, '7', ?::uuid, ?, ?, ?)",
                        sessionId, agentId, "two-JVM queue " + index, createdAt, createdAt);
                submitQueueProbeTurn(client, objectMapper, token, sessionId, agentId, "turn-0-" + index);
                if (index == 0) {
                    secondTurnMessageId = submitQueueProbeTurn(
                            client, objectMapper, token, sessionId, agentId, "turn-1-0"
                    );
                }
            }

            waitForFullApplicationQueue(jdbcTemplate, sessionIds, 16, workerALog, workerBLog);
            assertThat(countForQueueProbeSessions(jdbcTemplate,
                    "agent_execution_task", sessionIds,
                    "AND status = 'COMPLETED' AND attempt_count = 1")).isEqualTo(16);
            assertThat(countForQueueProbeSessions(jdbcTemplate,
                    "agent_execution_task", sessionIds,
                    "AND status IN ('PENDING', 'RUNNING', 'FAILED')")).isZero();
            assertThat(countForQueueProbeSessions(jdbcTemplate,
                    "g5_agent_queue_probe_execution", sessionIds, "")).isEqualTo(16);
            assertThat(countForQueueProbeWorkers(jdbcTemplate, sessionIds)).contains("worker-a", "worker-b");
            Long overlappingTurns = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM g5_agent_queue_probe_execution first_turn
                    JOIN g5_agent_queue_probe_execution second_turn
                      ON second_turn.session_id = first_turn.session_id
                     AND second_turn.sequence_no = 1
                    WHERE first_turn.session_id = ?::uuid
                      AND first_turn.sequence_no = 0
                      AND (first_turn.finished_at IS NULL OR second_turn.started_at < first_turn.finished_at)
                    """, Long.class, sessionIds.get(0));
            assertThat(overlappingTurns).isZero();
            List<ChatMessageDTO> orderedHistory = context.getBean(ChatMessageFacadeService.class)
                    .getChatMessagesBySessionIdRecently(sessionIds.get(0), 20, "7", secondTurnMessageId);
            assertThat(orderedHistory)
                    .extracting(ChatMessageDTO::getContent)
                    .containsExactly("turn-0-0", "probe-answer-0", "turn-1-0", "probe-answer-1");

            Files.writeString(workerAStop, "stop");
            Files.writeString(workerBStop, "stop");
            for (Process worker : workers) {
                assertThat(worker.waitFor(30, TimeUnit.SECONDS)).isTrue();
                assertThat(worker.exitValue()).isZero();
            }
        } finally {
            Files.writeString(workerAStop, "stop");
            Files.writeString(workerBStop, "stop");
            for (Process worker : workers) {
                if (worker.isAlive()) {
                    worker.destroyForcibly();
                    worker.waitFor(10, TimeUnit.SECONDS);
                }
            }
            if (!sessionIds.isEmpty()) {
                for (String sessionId : sessionIds) {
                    jdbcTemplate.update("DELETE FROM chat_session WHERE id = ?::uuid", sessionId);
                }
                jdbcTemplate.update("DELETE FROM g5_agent_queue_probe_execution "
                        + "WHERE session_id IN (" + uuidPlaceholders(sessionIds.size()) + ")",
                        sessionIds.toArray());
            }
            Files.deleteIfExists(workerAReady);
            Files.deleteIfExists(workerAStop);
            Files.deleteIfExists(workerALog);
            Files.deleteIfExists(workerBReady);
            Files.deleteIfExists(workerBStop);
            Files.deleteIfExists(workerBLog);
            Files.deleteIfExists(processDirectory);
        }
    }

    private String submitQueueProbeTurn(
            HttpClient client,
            ObjectMapper objectMapper,
            String token,
            String sessionId,
            String agentId,
            String content
    ) throws Exception {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/chat-messages"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(Map.of(
                                "agentId", agentId,
                                "sessionId", sessionId,
                                "role", "user",
                                "content", content
                        ))))
                        .build(),
                HttpResponse.BodyHandlers.ofString()
        );
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = objectMapper.readTree(response.body());
        assertThat(body.path("code").asInt()).isEqualTo(200);
        String messageId = body.path("data").path("chatMessageId").asText();
        assertThat(messageId).isNotBlank();
        return messageId;
    }

    private static Process startFullApplicationQueueWorkerProcess(
            String workerId,
            Path readyFile,
            Path stopFile,
            Path logFile
    ) throws Exception {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        String classPath = System.getProperty("surefire.test.class.path");
        assertThat(classPath).isNotBlank();
        return new ProcessBuilder(
                javaExecutable,
                "-Dspring.devtools.restart.enabled=false",
                "-Dg5.chat.http.pg.port=" + requiredPort("g5.chat.http.pg.port"),
                "-Dg5.chat.http.redis.port=" + requiredPort("g5.chat.http.redis.port"),
                "-cp", classPath,
                G5FullApplicationQueueWorkerProcessProbe.class.getName(),
                workerId,
                readyFile.toString(),
                stopFile.toString()
        ).redirectErrorStream(true).redirectOutput(logFile.toFile()).start();
    }

    private static void awaitFullApplicationQueueWorker(Path readyFile, Process worker, Path logFile)
            throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (!Files.exists(readyFile) && worker.isAlive() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(Files.exists(readyFile))
                .withFailMessage("Full application queue worker did not become ready: %s",
                        Files.exists(logFile) ? Files.readString(logFile) : "no output")
                .isTrue();
    }

    private static void waitForFullApplicationQueue(
            JdbcTemplate jdbcTemplate,
            List<String> sessionIds,
            long expectedCompleted,
            Path workerALog,
            Path workerBLog
    ) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            long completed = countForQueueProbeSessions(jdbcTemplate, "agent_execution_task", sessionIds,
                    "AND status = 'COMPLETED'");
            long active = countForQueueProbeSessions(jdbcTemplate, "agent_execution_task", sessionIds,
                    "AND status IN ('PENDING', 'RUNNING')");
            if (completed == expectedCompleted && active == 0) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Full application queue workers did not drain tasks: worker-a="
                + (Files.exists(workerALog) ? Files.readString(workerALog) : "no output")
                + "; worker-b=" + (Files.exists(workerBLog) ? Files.readString(workerBLog) : "no output"));
    }

    private static long countForQueueProbeSessions(
            JdbcTemplate jdbcTemplate,
            String table,
            List<String> sessionIds,
            String filter
    ) {
        String sql = "SELECT COUNT(*) FROM " + table + " WHERE session_id IN ("
                + uuidPlaceholders(sessionIds.size()) + ") " + filter;
        return jdbcTemplate.queryForObject(sql, Long.class, sessionIds.toArray());
    }

    private static List<String> countForQueueProbeWorkers(JdbcTemplate jdbcTemplate, List<String> sessionIds) {
        String sql = "SELECT DISTINCT worker_id FROM g5_agent_queue_probe_execution WHERE session_id IN ("
                + uuidPlaceholders(sessionIds.size()) + ") ORDER BY worker_id";
        return jdbcTemplate.queryForList(sql, String.class, sessionIds.toArray());
    }

    private static String uuidPlaceholders(int count) {
        return String.join(",", java.util.Collections.nCopies(count, "?::uuid"));
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

    static ConfigurableApplicationContext startSecondFullApplication(String... additionalProperties) {
        List<String> properties = new ArrayList<>(List.of(
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
                ));
        properties.addAll(List.of(additionalProperties));
        return new SpringApplicationBuilder(JchatmindApplication.class, IsolatedModels.class)
                .web(WebApplicationType.SERVLET)
                .properties(properties.toArray(String[]::new))
                .run();
    }

    private static long runFullAppProcessProbe(String sessionId) throws Exception {
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
        String marker = new String(Files.readAllBytes(log), StandardCharsets.ISO_8859_1).lines()
                .filter(line -> line.startsWith("G5_FULL_APP_PUBLISHED:"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Full application publisher process emitted no sequence"));
        return Long.parseLong(marker.substring("G5_FULL_APP_PUBLISHED:".length()));
    }

    private static long runFullAppHttpRestartProcessProbe(String sessionId, long expectedSequence)
            throws Exception {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
        String classPath = System.getProperty("surefire.test.class.path");
        assertThat(classPath).isNotBlank();
        Path log = Path.of("target", "g5-full-app-http-restart-" + expectedSequence + ".log").toAbsolutePath();
        Process process = new ProcessBuilder(
                javaExecutable,
                "-Dspring.devtools.restart.enabled=false",
                "-Dg5.chat.http.pg.port=" + requiredPort("g5.chat.http.pg.port"),
                "-Dg5.chat.http.redis.port=" + requiredPort("g5.chat.http.redis.port"),
                "-cp", classPath,
                G5FullApplicationHttpRestartProcessProbe.class.getName(),
                sessionId,
                Long.toString(expectedSequence)
        ).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
            throw new IllegalStateException("Full application HTTP restart process timed out");
        }
        assertThat(process.exitValue()).as("Full application HTTP restart process exit code").isZero();
        String prefix = "G5_FULL_APP_HTTP_RESTART_PASS:";
        String marker = new String(Files.readAllBytes(log), StandardCharsets.ISO_8859_1).lines()
                .filter(line -> line.startsWith(prefix))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Full application HTTP restart process emitted no result"));
        String[] values = marker.substring(prefix.length()).split(":");
        assertThat(values).containsExactly(Long.toString(expectedSequence), Long.toString(expectedSequence + 1));
        return Long.parseLong(values[1]);
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

    static String readFirstEvent(InputStream stream) {
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

    private static String readMessageEventsThrough(InputStream stream, long expectedSequence) {
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            StringBuilder events = new StringBuilder();
            StringBuilder currentEvent = new StringBuilder();
            String eventId = null;
            boolean messageEvent = false;
            String line;
            while ((line = reader.readLine()) != null) {
                currentEvent.append(line).append('\n');
                if (line.startsWith("id:")) {
                    eventId = line.substring("id:".length()).trim();
                } else if ("event:message".equals(line)) {
                    messageEvent = true;
                }
                if (line.isEmpty()) {
                    events.append(currentEvent);
                    if (messageEvent && eventId != null && Long.parseLong(eventId) >= expectedSequence) {
                        return events.toString();
                    }
                    currentEvent.setLength(0);
                    eventId = null;
                    messageEvent = false;
                }
            }
            throw new AssertionError("SSE connection closed before message id " + expectedSequence);
        } catch (IOException exception) {
            throw new IllegalStateException("Unable to read post-restart SSE message", exception);
        }
    }

    static void completeSessionEmitters(SseServiceImpl service, String sessionId) {
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

    private void stopPersistentQueuePolling() {
        Map<String, AgentExecutionTaskWorker> workers = context.getBeansOfType(AgentExecutionTaskWorker.class);
        if (workers.isEmpty()) {
            return;
        }
        ScheduledAnnotationBeanPostProcessor scheduler =
                context.getBean(ScheduledAnnotationBeanPostProcessor.class);
        workers.forEach((beanName, worker) -> scheduler.postProcessBeforeDestruction(worker, beanName));
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

        @Bean
        @Primary
        @ConditionalOnProperty(name = "g5.chat.full.queue.probe", havingValue = "true")
        AgentExecutionTaskRunner isolatedQueueProbeRunner(
                JdbcTemplate jdbcTemplate,
                ChatMessageFacadeService chatMessageFacadeService,
                Environment environment
        ) {
            String workerId = environment.getProperty("g5.chat.full.queue.probe.worker-id", "parent");
            return event -> {
                jdbcTemplate.update("INSERT INTO g5_agent_queue_probe_execution "
                                + "(worker_id, process_id, session_id, user_message_id, sequence_no, started_at) "
                                + "VALUES (?, ?, ?::uuid, ?::uuid, ?, clock_timestamp())",
                        workerId,
                        ProcessHandle.current().pid(),
                        event.getSessionId(),
                        event.getUserMessageId(),
                        event.getSequenceNo());
                long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
                while (System.nanoTime() < deadline) {
                    Long childWorkers = jdbcTemplate.queryForObject(
                            "SELECT COUNT(DISTINCT worker_id) FROM g5_agent_queue_probe_execution "
                                    + "WHERE worker_id IN ('worker-a', 'worker-b')",
                            Long.class
                    );
                    if (childWorkers != null && childWorkers == 2) {
                        break;
                    }
                    sleepProbeRunner(25);
                }
                Long childWorkers = jdbcTemplate.queryForObject(
                        "SELECT COUNT(DISTINCT worker_id) FROM g5_agent_queue_probe_execution "
                                + "WHERE worker_id IN ('worker-a', 'worker-b')",
                        Long.class
                );
                if (childWorkers == null || childWorkers != 2) {
                    throw new IllegalStateException("Both independent full-app worker JVMs did not execute a task");
                }
                sleepProbeRunner(50);
                chatMessageFacadeService.createChatMessage(
                        ChatMessageDTO.builder()
                                .sessionId(event.getSessionId())
                                .role(ChatMessageDTO.RoleType.ASSISTANT)
                                .content("probe-answer-" + event.getSequenceNo())
                                .build(),
                        event.getUserId()
                );
                jdbcTemplate.update("UPDATE g5_agent_queue_probe_execution SET finished_at = clock_timestamp() "
                                + "WHERE worker_id = ? AND user_message_id = ?::uuid",
                        workerId, event.getUserMessageId());
            };
        }

        private static void sleepProbeRunner(long milliseconds) {
            try {
                Thread.sleep(milliseconds);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Full-app queue probe runner was interrupted", exception);
            }
        }
    }
}
