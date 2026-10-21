package com.kama.jchatmind.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.harness.HarnessContext;
import com.kama.jchatmind.agent.harness.HarnessProperties;
import com.kama.jchatmind.agent.harness.HarnessResult;
import com.kama.jchatmind.agent.harness.approval.InMemoryApprovalStore;
import com.kama.jchatmind.agent.harness.interceptor.HumanApprovalInterceptor;
import com.kama.jchatmind.ingestion.G1RuntimePostgresTestConfig;
import com.kama.jchatmind.ingestion.IngestionTaskStateMachine;
import com.kama.jchatmind.mapper.IngestionTaskMapper;
import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.model.entity.IngestionTask;
import com.kama.jchatmind.model.vo.IngestionTaskVO;
import com.kama.jchatmind.service.impl.IngestionTaskServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfSystemProperty(named = "g4.webhook.ingress.l2", matches = "true")
class WebhookIngressRuntimeL2Test {

    private static final long OWNER_ID = 70001L;
    private static final String SOURCE_ID = "build-system";
    private static final String KB_ID = "00000000-0000-0000-0000-000000007001";
    private static final String OTHER_KB_ID = "00000000-0000-0000-0000-000000007002";
    private static final String DOCUMENT_ID = "00000000-0000-0000-0000-000000007011";
    private static final String SIGNING_KEY = "g4-isolated-webhook-signing-key";

    @org.junit.jupiter.api.io.TempDir
    Path temporaryDirectory;

    private JdbcTemplate jdbcTemplate;

    private InboundWebhookController controller;

    private StringRedisTemplate redisTemplate;

    private AnnotationConfigApplicationContext context;

    private String containerName;
    private String redisContainerName;
    private boolean postgresStarted;
    private boolean redisStarted;
    private String databaseName;
    private String databaseUrl;
    private int postgresPort;
    private int redisPort;
    private final Map<String, String> previousSystemProperties = new HashMap<>();

    @BeforeEach
    void startIsolatedInfrastructure() throws Exception {
        String nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        containerName = "jchatmind-g4-webhook-pg-" + nonce;
        redisContainerName = "jchatmind-g4-webhook-redis-" + nonce;
        databaseName = "g1_ingestion_" + nonce;
        postgresPort = findAvailablePort();
        redisPort = findAvailablePort();
        databaseUrl = "jdbc:postgresql://127.0.0.1:" + postgresPort + "/" + databaseName;

        setSystemProperty("g1.pg.url", databaseUrl);
        setSystemProperty("g1.pg.nonce", nonce);
        setSystemProperty("g1.storage.dir", temporaryDirectory.toString());
        setSystemProperty("g4.webhook.redis.port", Integer.toString(redisPort));

        try {
            ProcessResult postgresResult = runDocker(
                    "run", "--detach", "--name", containerName,
                    "--label", "org.jchatmind.test=g4-webhook-ingress-l2",
                    "--publish", "127.0.0.1:" + postgresPort + ":5432",
                    "--env", "POSTGRES_DB=" + databaseName,
                    "--env", "POSTGRES_USER=g1ingestion",
                    "--env", "POSTGRES_HOST_AUTH_METHOD=trust",
                    "postgres:16"
            );
            assertThat(postgresResult.exitCode()).withFailMessage(postgresResult.output()).isZero();
            postgresStarted = true;
            ProcessResult postgresPortResult = runDocker("port", containerName, "5432/tcp");
            assertThat(postgresPortResult.exitCode()).withFailMessage(postgresPortResult.output()).isZero();
            assertThat(postgresPortResult.output().trim()).isEqualTo("127.0.0.1:" + postgresPort);
            awaitPostgres(databaseUrl);

            ProcessResult redisResult = runDocker(
                    "run", "--detach", "--name", redisContainerName,
                    "--label", "org.jchatmind.test=g4-webhook-ingress-l2",
                    "--publish", "127.0.0.1:" + redisPort + ":6379",
                    "redis:7-alpine"
            );
            assertThat(redisResult.exitCode()).withFailMessage(redisResult.output()).isZero();
            redisStarted = true;
            ProcessResult redisPortResult = runDocker("port", redisContainerName, "6379/tcp");
            assertThat(redisPortResult.exitCode()).withFailMessage(redisPortResult.output()).isZero();
            assertThat(redisPortResult.output().trim()).isEqualTo("127.0.0.1:" + redisPort);
            awaitPort(redisPort);

            context = new AnnotationConfigApplicationContext();
            context.register(WebhookL2Configuration.class);
            context.refresh();
            jdbcTemplate = context.getBean(JdbcTemplate.class);
            controller = context.getBean(InboundWebhookController.class);
            redisTemplate = context.getBean(StringRedisTemplate.class);
            createSchemaAndSourceData();
        } catch (Exception exception) {
            stopIsolatedInfrastructure();
            throw exception;
        }
    }

    @AfterEach
    void stopIsolatedInfrastructure() throws Exception {
        if (context != null) {
            context.close();
            context = null;
        }
        if (redisStarted) {
            ProcessResult result = runDocker("rm", "--force", redisContainerName);
            assertThat(result.exitCode()).withFailMessage(result.output()).isZero();
            redisStarted = false;
        }
        if (postgresStarted) {
            ProcessResult result = runDocker("rm", "--force", containerName);
            assertThat(result.exitCode()).withFailMessage(result.output()).isZero();
            postgresStarted = false;
        }
        restoreSystemProperties();
    }

    @Test
    void shouldPersistTaskBeforeReservingEventAndAllowRetryAfterDatabaseFailure() throws Exception {
        SignedRequest request = signedRequest("event-retry", KB_ID, DOCUMENT_ID);
        Response result = submit(request);
        assertThat(result.httpStatus()).isEqualTo(202);
        String firstTaskId = result.task().getTaskId();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ingestion_task", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM webhook_inbox_event", Integer.class)).isEqualTo(1);
        assertThat(redisTemplate.hasKey(redisKey(request.eventId()))).isTrue();

        result = submit(request);
        assertThat(result.httpStatus()).isEqualTo(202);
        assertThat(result.task().getTaskId()).isEqualTo(firstTaskId);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ingestion_task", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM webhook_inbox_event", Integer.class)).isEqualTo(1);

        SignedRequest failedRequest = signedRequest("event-db-failure", KB_ID, DOCUMENT_ID);
        jdbcTemplate.execute("CREATE FUNCTION g4_fail_ingestion_insert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled isolated failure'; END; $$");
        jdbcTemplate.execute("CREATE TRIGGER g4_fail_ingestion_insert BEFORE INSERT ON ingestion_task FOR EACH ROW EXECUTE FUNCTION g4_fail_ingestion_insert()");
        assertThatThrownBy(() -> submit(failedRequest)).isInstanceOf(RuntimeException.class);
        assertThat(redisTemplate.hasKey(redisKey(failedRequest.eventId()))).isFalse();
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ingestion_task", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM webhook_inbox_event", Integer.class)).isEqualTo(1);

        jdbcTemplate.execute("DROP TRIGGER g4_fail_ingestion_insert ON ingestion_task");
        jdbcTemplate.execute("DROP FUNCTION g4_fail_ingestion_insert()");
        result = submit(failedRequest);
        assertThat(result.httpStatus()).isEqualTo(202);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ingestion_task", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM webhook_inbox_event", Integer.class)).isEqualTo(2);
        assertThat(redisTemplate.hasKey(redisKey(failedRequest.eventId()))).isTrue();

        IngestionTaskServiceImpl taskService = context.getBean(IngestionTaskServiceImpl.class);
        IngestionTask claimed = taskService.claimTask(firstTaskId);
        jdbcTemplate.execute("CREATE FUNCTION g4_fail_webhook_outbox_insert() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled outbox failure'; END; $$");
        jdbcTemplate.execute("CREATE TRIGGER g4_fail_webhook_outbox_insert BEFORE INSERT ON webhook_delivery_outbox FOR EACH ROW EXECUTE FUNCTION g4_fail_webhook_outbox_insert()");
        assertThatThrownBy(() -> taskService.completeClaimedTask(claimed)).isInstanceOf(RuntimeException.class);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM ingestion_task WHERE task_id = ?::uuid", String.class, firstTaskId))
                .isEqualTo("RUNNING");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM webhook_delivery_outbox", Integer.class)).isZero();

        jdbcTemplate.execute("DROP TRIGGER g4_fail_webhook_outbox_insert ON webhook_delivery_outbox");
        jdbcTemplate.execute("DROP FUNCTION g4_fail_webhook_outbox_insert()");
        taskService.completeClaimedTask(context.getBean(IngestionTaskMapper.class).selectById(firstTaskId));
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM ingestion_task WHERE task_id = ?::uuid", String.class, firstTaskId))
                .isEqualTo("SUCCEEDED");
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM webhook_delivery_outbox", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT event_type FROM webhook_delivery_outbox", String.class))
                .isEqualTo("INGESTION_SUCCEEDED");

        AtomicReference<String> callbackSignature = new AtomicReference<>();
        AtomicReference<String> callbackTimestamp = new AtomicReference<>();
        AtomicReference<String> callbackEventId = new AtomicReference<>();
        AtomicReference<String> callbackBody = new AtomicReference<>();
        AtomicInteger failedIngestionAttempts = new AtomicInteger();
        HttpServer callbackServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        callbackServer.createContext("/callback", exchange -> {
            callbackSignature.set(exchange.getRequestHeaders().getFirst("X-Webhook-Signature"));
            callbackTimestamp.set(exchange.getRequestHeaders().getFirst("X-Webhook-Timestamp"));
            callbackEventId.set(exchange.getRequestHeaders().getFirst("X-Webhook-Event-Id"));
            callbackBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            int status = callbackBody.get().contains("APPROVAL_PENDING")
                    || (callbackBody.get().contains("INGESTION_FAILED")
                    && failedIngestionAttempts.getAndIncrement() == 0)
                    ? 503
                    : 204;
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        callbackServer.start();
        try {
            WebhookProperties properties = context.getBean(WebhookProperties.class);
            properties.getSources().get(SOURCE_ID).setCallbackUrl(
                    "http://127.0.0.1:" + callbackServer.getAddress().getPort() + "/callback"
            );
            WebhookDeliveryDispatcher dispatcher = new WebhookDeliveryDispatcher(jdbcTemplate, properties);
            dispatcher.dispatchDueCallbacks();
            String storedPayload = jdbcTemplate.queryForObject(
                    "SELECT event_payload FROM webhook_delivery_outbox", String.class
            );
            UUID storedCallbackEventId = UUID.fromString(jdbcTemplate.queryForObject(
                    "SELECT callback_event_id::text FROM webhook_delivery_outbox", String.class
            ));
            Instant sentAt = Instant.ofEpochSecond(Long.parseLong(callbackTimestamp.get()));
            assertThat(callbackEventId.get()).isEqualTo(storedCallbackEventId.toString());
            assertThat(callbackBody.get()).isEqualTo(storedPayload);
            assertThat(callbackSignature.get()).isEqualTo(WebhookSignature.sign(
                    SOURCE_ID,
                    storedCallbackEventId.toString(),
                    sentAt,
                    storedPayload,
                    "isolated-outbound-key".getBytes(StandardCharsets.UTF_8)
            ));
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT delivery_status FROM webhook_delivery_outbox", String.class
            )).isEqualTo("DELIVERED");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT attempt_count FROM webhook_delivery_outbox", Integer.class
            )).isEqualTo(1);

            String failedTaskId = jdbcTemplate.queryForObject(
                    "SELECT task_id::text FROM ingestion_task WHERE idempotency_key = ?",
                    String.class,
                    "webhook:" + RedisWebhookEventIdRegistry.eventIdentityHash(SOURCE_ID, "event-db-failure")
            );
            IngestionTaskStateMachine.Status failedStatus = null;
            for (int attempt = 0; attempt < 3 && failedStatus != IngestionTaskStateMachine.Status.DEAD_LETTER; attempt++) {
                IngestionTask claimedTask = taskService.claimTask(failedTaskId);
                failedStatus = taskService.failClaimedTask(claimedTask, "sensitive path details");
            }
            assertThat(failedStatus).isEqualTo(IngestionTaskStateMachine.Status.DEAD_LETTER);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM webhook_delivery_outbox WHERE event_type = 'INGESTION_FAILED'",
                    Integer.class
            )).isEqualTo(1);

            dispatcher.dispatchDueCallbacks();
            UUID failedAggregateId = UUID.fromString(failedTaskId);
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT delivery_status FROM webhook_delivery_outbox WHERE aggregate_id = ?::uuid",
                    String.class,
                    failedAggregateId.toString()
            )).isEqualTo("RETRYING");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT attempt_count FROM webhook_delivery_outbox WHERE aggregate_id = ?::uuid",
                    Integer.class,
                    failedAggregateId.toString()
            )).isEqualTo(1);
            jdbcTemplate.update(
                    "UPDATE webhook_delivery_outbox SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 minute' WHERE aggregate_id = ?::uuid",
                    failedAggregateId.toString()
            );
            dispatcher.dispatchDueCallbacks();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT delivery_status FROM webhook_delivery_outbox WHERE aggregate_id = ?::uuid",
                    String.class,
                    failedAggregateId.toString()
            )).isEqualTo("DELIVERED");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT attempt_count FROM webhook_delivery_outbox WHERE aggregate_id = ?::uuid",
                    Integer.class,
                    failedAggregateId.toString()
            )).isEqualTo(2);

            HarnessProperties approvalProperties = new HarnessProperties();
            approvalProperties.getHumanApproval().setEnabled(true);
            approvalProperties.getHumanApproval().setTools(List.of("sendEmail"));
            HarnessResult approvalResult = new HarnessResult();
            HarnessContext approvalContext = HarnessContext.builder()
                    .sessionId("session-1")
                    .agentId("agent-1")
                    .userId(Long.toString(OWNER_ID))
                    .webhookSourceId(SOURCE_ID)
                    .toolCallId("approval-call-1")
                    .toolName("sendEmail")
                    .toolInput("private approval tool input")
                    .build();
            approvalResult.addContext(approvalContext);
            new HumanApprovalInterceptor(
                    approvalProperties,
                    new InMemoryApprovalStore(),
                    new WebhookApprovalOutboxService(jdbcTemplate, properties)
            ).beforeExecution(approvalContext, approvalResult);
            String approvalRequestId = approvalResult.getDecision("approval-call-1").getApprovalRequestId();
            assertThat(approvalRequestId).isNotBlank();
            UUID approvalAggregateId = UUID.fromString(approvalRequestId);
            String approvalPayload = jdbcTemplate.queryForObject(
                    "SELECT event_payload FROM webhook_delivery_outbox WHERE aggregate_id = ?::uuid",
                    String.class,
                    approvalRequestId
            );
            var approvalEvent = new ObjectMapper().readTree(approvalPayload);
            assertThat(approvalEvent.path("eventType").asText()).isEqualTo("APPROVAL_PENDING");
            assertThat(approvalEvent.path("ownerId").asLong()).isEqualTo(OWNER_ID);
            assertThat(approvalEvent.path("sessionId").asText()).isEqualTo("session-1");
            assertThat(approvalEvent.path("approvalId").asText()).isEqualTo(approvalRequestId);
            assertThat(approvalPayload).doesNotContain("private approval tool input", "toolInput", "query", "claim");
            jdbcTemplate.update(
                    "UPDATE webhook_delivery_outbox SET max_attempts = 1 WHERE aggregate_id = ?::uuid",
                    approvalAggregateId.toString()
            );
            dispatcher.dispatchDueCallbacks();
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT delivery_status FROM webhook_delivery_outbox WHERE aggregate_id = ?::uuid",
                    String.class,
                    approvalAggregateId.toString()
            )).isEqualTo("DEAD_LETTER");
            assertThat(jdbcTemplate.queryForObject(
                    "SELECT attempt_count FROM webhook_delivery_outbox WHERE aggregate_id = ?::uuid",
                    Integer.class,
                    approvalAggregateId.toString()
            )).isEqualTo(1);
        } finally {
            callbackServer.stop(0);
        }
    }

    private void createSchemaAndSourceData() {
        jdbcTemplate.execute("CREATE TABLE jchatmind_user (user_id BIGINT PRIMARY KEY, account VARCHAR(128), username VARCHAR(128), password VARCHAR(128))");
        jdbcTemplate.execute("CREATE TABLE knowledge_base (id UUID PRIMARY KEY, name VARCHAR(128), description VARCHAR(255), metadata JSONB, owner_id BIGINT NOT NULL REFERENCES jchatmind_user(user_id), created_at TIMESTAMP, updated_at TIMESTAMP)");
        jdbcTemplate.execute("CREATE TABLE document (id UUID PRIMARY KEY, kb_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE, filename VARCHAR(255), filetype VARCHAR(32), size BIGINT, metadata JSONB, created_at TIMESTAMP, updated_at TIMESTAMP)");
        jdbcTemplate.execute("CREATE TABLE ingestion_task (task_id UUID PRIMARY KEY, owner_id BIGINT NOT NULL REFERENCES jchatmind_user(user_id), kb_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE, document_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE, idempotency_key VARCHAR(128) NOT NULL, task_type VARCHAR(64) NOT NULL, status VARCHAR(32) NOT NULL, attempt_count INTEGER NOT NULL, max_attempts INTEGER NOT NULL, error_summary VARCHAR(500), created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL, started_at TIMESTAMP, completed_at TIMESTAMP, UNIQUE(owner_id, idempotency_key))");
        jdbcTemplate.execute("CREATE TABLE webhook_inbox_event (inbox_event_id UUID PRIMARY KEY, source_id VARCHAR(128) NOT NULL, source_event_sha256 VARCHAR(64) NOT NULL, owner_id BIGINT NOT NULL REFERENCES jchatmind_user(user_id), task_id UUID NOT NULL REFERENCES ingestion_task(task_id), payload_sha256 VARCHAR(64) NOT NULL, received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, UNIQUE(source_id, source_event_sha256))");
        jdbcTemplate.execute("CREATE TABLE webhook_delivery_outbox (outbox_id UUID PRIMARY KEY, callback_event_id UUID NOT NULL UNIQUE, source_id VARCHAR(128) NOT NULL, event_type VARCHAR(32) NOT NULL, aggregate_id UUID NOT NULL, event_payload TEXT NOT NULL, payload_sha256 VARCHAR(64) NOT NULL, delivery_status VARCHAR(16) NOT NULL DEFAULT 'PENDING', attempt_count INTEGER NOT NULL DEFAULT 0, max_attempts INTEGER NOT NULL DEFAULT 5, next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, last_http_status INTEGER, last_error_code VARCHAR(64), created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, delivered_at TIMESTAMP, dead_lettered_at TIMESTAMP, UNIQUE(source_id, aggregate_id, event_type))");
        jdbcTemplate.update("INSERT INTO jchatmind_user VALUES (?, ?, ?, ?)", OWNER_ID, "g4-webhook-owner", "G4", "isolated");
        jdbcTemplate.update("INSERT INTO knowledge_base VALUES (?::uuid, ?, ?, '{}'::jsonb, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", KB_ID, "G4 webhook KB", "isolated", OWNER_ID);
        jdbcTemplate.update("INSERT INTO knowledge_base VALUES (?::uuid, ?, ?, '{}'::jsonb, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", OTHER_KB_ID, "G4 other KB", "isolated", OWNER_ID);
        jdbcTemplate.update("INSERT INTO document VALUES (?::uuid, ?::uuid, ?, ?, ?, '{}'::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", DOCUMENT_ID, KB_ID, "guide.md", "md", 12L);
    }

    private Response submit(SignedRequest request) {
        var response = controller.receive(
                SOURCE_ID,
                request.eventId(),
                Long.toString(request.timestamp().getEpochSecond()),
                request.signature(),
                request.payload().getBytes(StandardCharsets.UTF_8)
        );
        return new Response(response.getStatusCode().value(), response.getBody().getData());
    }

    private SignedRequest signedRequest(String eventId, String kbId, String documentId) throws Exception {
        String payload = new ObjectMapper().writeValueAsString(Map.of("kbId", kbId, "documentId", documentId));
        Instant timestamp = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        return new SignedRequest(eventId, timestamp, payload, sign(SOURCE_ID, eventId, timestamp, payload));
    }

    private String sign(String sourceId, String eventId, Instant timestamp, String payload) throws GeneralSecurityException {
        byte[] source = sourceId.getBytes(StandardCharsets.UTF_8);
        byte[] id = eventId.getBytes(StandardCharsets.UTF_8);
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        ByteBuffer input = ByteBuffer.allocate(Byte.BYTES + Integer.BYTES + source.length
                        + Integer.BYTES + id.length + Long.BYTES + Integer.BYTES + body.length)
                .order(ByteOrder.BIG_ENDIAN);
        input.put((byte) 1);
        putText(input, source);
        putText(input, id);
        input.putLong(timestamp.getEpochSecond());
        putText(input, body);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SIGNING_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return java.util.HexFormat.of().formatHex(mac.doFinal(input.array()));
    }


    private void putText(ByteBuffer input, byte[] value) {
        input.putInt(value.length);
        input.put(value);
    }

    private String redisKey(String eventId) {
        return "jchatmind:webhook:{" + RedisWebhookEventIdRegistry.eventIdentityHash(SOURCE_ID, eventId)
                + "}:event-id";
    }

    private void awaitPostgres(String url) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            try (java.sql.Connection ignored = java.sql.DriverManager.getConnection(url, "g1ingestion", "")) {
                return;
            } catch (Exception exception) {
                Thread.sleep(100);
            }
        }
        throw new IllegalStateException("Isolated PostgreSQL did not become ready");
    }

    private void awaitPort(int port) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 300);
                return;
            } catch (IOException exception) {
                Thread.sleep(100);
            }
        }
        throw new IllegalStateException("Isolated Redis did not become ready");
    }

    private static int findAvailablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private ProcessResult runDocker(String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(45, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Docker command did not finish within 45 seconds");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProcessResult(process.exitValue(), output);
    }

    private void setSystemProperty(String name, String value) {
        previousSystemProperties.put(name, System.getProperty(name));
        System.setProperty(name, value);
    }

    private void restoreSystemProperties() {
        for (Map.Entry<String, String> entry : previousSystemProperties.entrySet()) {
            if (entry.getValue() == null) {
                System.clearProperty(entry.getKey());
            } else {
                System.setProperty(entry.getKey(), entry.getValue());
            }
        }
        previousSystemProperties.clear();
    }

    @Configuration
    @Import({G1RuntimePostgresTestConfig.class, WebhookRuntimeConfiguration.class})
    static class WebhookL2Configuration {

        @Bean(destroyMethod = "destroy")
        LettuceConnectionFactory webhookConnectionFactory() {
            int port = Integer.parseInt(System.getProperty("g4.webhook.redis.port"));
            LettuceConnectionFactory factory = new LettuceConnectionFactory(
                    new RedisStandaloneConfiguration("127.0.0.1", port)
            );
            factory.afterPropertiesSet();
            return factory;
        }

        @Bean
        StringRedisTemplate webhookRedisTemplate(LettuceConnectionFactory webhookConnectionFactory) {
            StringRedisTemplate template = new StringRedisTemplate(webhookConnectionFactory);
            template.afterPropertiesSet();
            return template;
        }

        @Bean
        RedisWebhookEventIdRegistry webhookEventIdRegistry(StringRedisTemplate webhookRedisTemplate) {
            return new RedisWebhookEventIdRegistry(webhookRedisTemplate);
        }

        @Bean
        WebhookProperties webhookProperties() {
            WebhookProperties properties = new WebhookProperties();
            WebhookProperties.Source source = new WebhookProperties.Source();
            source.setOwnerId(OWNER_ID);
            source.setAllowedKbIds(java.util.Set.of(KB_ID));
            source.setInboundSigningKey(SIGNING_KEY);
            source.setCallbackUrl("https://example.invalid/webhook");
            source.setOutboundSigningKey("isolated-outbound-key");
            properties.getSources().put(SOURCE_ID, source);
            return properties;
        }

        @Bean
        WebhookIngressService webhookIngressService(
                IngestionTaskServiceImpl ingestionTaskService,
                JdbcTemplate jdbcTemplate,
                InboundWebhookVerifier verifier
        ) {
            return new WebhookIngressService(ingestionTaskService, jdbcTemplate, verifier);
        }

        @Bean
        InboundWebhookController inboundWebhookController(
                WebhookProperties webhookProperties,
                InboundWebhookVerifier verifier,
                WebhookIngressService webhookIngressService,
                ObjectMapper objectMapper
        ) {
            return new InboundWebhookController(webhookProperties, verifier, webhookIngressService, objectMapper);
        }
    }

    private record SignedRequest(String eventId, Instant timestamp, String payload, String signature) {
    }

    private record Response(int httpStatus, IngestionTaskVO task) {
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
