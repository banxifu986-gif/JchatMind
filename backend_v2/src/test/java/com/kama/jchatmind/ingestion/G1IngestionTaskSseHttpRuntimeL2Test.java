package com.kama.jchatmind.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.auth.JwtUtil;
import com.kama.jchatmind.model.entity.IngestionTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        classes = G1RuntimeSseHttpTestConfig.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.config.name=g1-sse-http-isolated",
                "spring.ai.model.chat=none",
                "spring.ai.model.embedding=none",
                "spring.ai.model.image=none",
                "spring.ai.mcp.server.enabled=false",
                "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration,org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration",
                "jwt.expiration=3600"
        }
)
@EnabledIfEnvironmentVariable(named = "G1_SSE_HTTP_L2", matches = "true")
class G1IngestionTaskSseHttpRuntimeL2Test {

    private static final long OWNER_A = 71001L;
    private static final long OWNER_B = 71002L;
    private static final String KB_ID = "00000000-0000-0000-0000-000000009501";
    private static final String DOCUMENT_ID = "00000000-0000-0000-0000-000000009511";
    private static final String TASK_ID = "00000000-0000-0000-0000-000000009521";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private IngestionTaskProgressService progressService;

    @Autowired
    private ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @DynamicPropertySource
    static void registerJwtSecret(DynamicPropertyRegistry registry) {
        registry.add("jwt.secret", () -> "g1-sse-http-test-" + UUID.randomUUID() + UUID.randomUUID());
    }

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS ingestion_task, document, knowledge_base, jchatmind_user CASCADE");
        jdbcTemplate.execute("CREATE TABLE jchatmind_user (user_id BIGINT PRIMARY KEY, account VARCHAR(128), username VARCHAR(128), password VARCHAR(128))");
        jdbcTemplate.execute("CREATE TABLE knowledge_base (id UUID PRIMARY KEY, name VARCHAR(128), description VARCHAR(255), metadata JSONB, owner_id BIGINT NOT NULL REFERENCES jchatmind_user(user_id), created_at TIMESTAMP, updated_at TIMESTAMP)");
        jdbcTemplate.execute("CREATE TABLE document (id UUID PRIMARY KEY, kb_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE, filename VARCHAR(255), filetype VARCHAR(32), size BIGINT, metadata JSONB, created_at TIMESTAMP, updated_at TIMESTAMP)");
        jdbcTemplate.execute("CREATE TABLE ingestion_task (task_id UUID PRIMARY KEY, owner_id BIGINT NOT NULL REFERENCES jchatmind_user(user_id), kb_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE, document_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE, idempotency_key VARCHAR(128) NOT NULL, task_type VARCHAR(64) NOT NULL, status VARCHAR(32) NOT NULL, attempt_count INTEGER NOT NULL, max_attempts INTEGER NOT NULL, error_summary VARCHAR(500), created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL, started_at TIMESTAMP, completed_at TIMESTAMP, UNIQUE(owner_id, idempotency_key))");
        jdbcTemplate.update("INSERT INTO jchatmind_user VALUES (?, ?, ?, ?), (?, ?, ?, ?)",
                OWNER_A, "g1-sse-owner", "owner", "isolated",
                OWNER_B, "g1-sse-other", "other", "isolated");
        jdbcTemplate.update("INSERT INTO knowledge_base VALUES (?::uuid, ?, ?, '{}'::jsonb, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                KB_ID, "G1 SSE", "isolated", OWNER_A);
        jdbcTemplate.update("INSERT INTO document VALUES (?::uuid, ?::uuid, ?, ?, ?, '{}'::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                DOCUMENT_ID, KB_ID, "sse.md", "md", 1);
        jdbcTemplate.update("INSERT INTO ingestion_task VALUES (?::uuid, ?, ?::uuid, ?::uuid, ?, ?, ?, ?, ?, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, NULL)",
                TASK_ID, OWNER_A, KB_ID, DOCUMENT_ID, "g1-sse-key", "DOCUMENT_INGESTION", "QUEUED", 0, 3);
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS ingestion_task, document, knowledge_base, jchatmind_user CASCADE");
    }

    @Test
    void shouldDeliverPublishedProgressToEveryAuthorizedSseConnection() throws Exception {
        String ownerToken = jwtUtil.generateToken(OWNER_A);
        try (SseConnection first = openSse(ownerToken); SseConnection second = openSse(ownerToken)) {
            assertThat(first.statusCode()).isEqualTo(200);
            assertThat(second.statusCode()).isEqualTo(200);
            assertThat(first.nextEvent().status()).isEqualTo("QUEUED");
            assertThat(second.nextEvent().status()).isEqualTo("QUEUED");

            IngestionTask running = IngestionTask.builder()
                    .id(TASK_ID)
                    .kbId(KB_ID)
                    .documentId(DOCUMENT_ID)
                    .status("RUNNING")
                    .attemptCount(0)
                    .maxAttempts(3)
                    .build();
            progressService.publish(running);

            CompletableFuture<IngestionTaskProgressEvent> firstUpdate = CompletableFuture.supplyAsync(first::nextEvent);
            CompletableFuture<IngestionTaskProgressEvent> secondUpdate = CompletableFuture.supplyAsync(second::nextEvent);
            assertThat(firstUpdate.get(5, TimeUnit.SECONDS).status()).isEqualTo("RUNNING");
            assertThat(secondUpdate.get(5, TimeUnit.SECONDS).status()).isEqualTo("RUNNING");

            try (SseConnection replay = openSse(ownerToken, "1")) {
                assertThat(replay.nextEvent().status()).isEqualTo("RUNNING");
                assertThat(replay.nextEventSequence()).isEqualTo(2);
            }
        }
    }

    @Test
    void shouldRejectAnotherOwnerWithoutLeakingTaskIdentity() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(sseUri())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + jwtUtil.generateToken(OWNER_B))
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.body())
                .doesNotContain(TASK_ID)
                .doesNotContain(KB_ID)
                .doesNotContain(DOCUMENT_ID);
    }

    private SseConnection openSse(String token) throws Exception {
        return openSse(token, null);
    }

    private SseConnection openSse(String token, String lastEventId) throws Exception {
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(sseUri())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        if (lastEventId != null) {
            requestBuilder.header("Last-Event-ID", lastEventId);
        }
        HttpRequest request = requestBuilder.GET().build();
        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        return new SseConnection(response.statusCode(), response.body(), objectMapper);
    }

    private URI sseUri() {
        return URI.create("http://127.0.0.1:" + port + "/sse/ingestion/" + TASK_ID);
    }

    private static class SseConnection implements AutoCloseable {

        private final int statusCode;
        private final InputStream inputStream;
        private final BufferedReader reader;
        private final ObjectMapper objectMapper;
        private long lastSequence;

        private SseConnection(int statusCode, InputStream inputStream, ObjectMapper objectMapper) {
            this.statusCode = statusCode;
            this.inputStream = inputStream;
            this.reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            this.objectMapper = objectMapper;
        }

        private int statusCode() {
            return statusCode;
        }

        private IngestionTaskProgressEvent nextEvent() {
            try {
                String eventName = null;
                String data = null;
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (data != null) {
                            if (!"ingestion-progress".equals(eventName)) {
                                throw new AssertionError("unexpected SSE event " + eventName);
                            }
                            lastSequence = objectMapper.readTree(data).path("sequence").asLong();
                            return objectMapper.readValue(data, IngestionTaskProgressEvent.class);
                        }
                        continue;
                    }
                    if (line.startsWith("event:")) {
                        eventName = line.substring("event:".length()).trim();
                    } else if (line.startsWith("data:")) {
                        data = line.substring("data:".length()).trim();
                    }
                }
                throw new AssertionError("SSE connection closed before the expected event");
            } catch (IOException e) {
                throw new IllegalStateException("Unable to read SSE event", e);
            }
        }

        private long nextEventSequence() {
            return lastSequence;
        }

        @Override
        public void close() throws IOException {
            inputStream.close();
        }
    }
}
