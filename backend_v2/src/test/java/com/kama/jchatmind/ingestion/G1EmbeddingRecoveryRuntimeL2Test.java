package com.kama.jchatmind.ingestion;

import com.kama.jchatmind.config.RabbitMQConfig;
import com.kama.jchatmind.mapper.IngestionTaskMapper;
import com.kama.jchatmind.service.DocumentStorageService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringJUnitConfig(G1RuntimeEmbeddingRecoveryTestConfig.class)
@EnabledIfEnvironmentVariable(named = "G1_EMBEDDING_RECOVERY_L2", matches = "true")
class G1EmbeddingRecoveryRuntimeL2Test {

    private static final long OWNER_ID = 70003L;
    private static final String KB_ID = "00000000-0000-0000-0000-000000009301";
    private static final String DOCUMENT_ID = "00000000-0000-0000-0000-000000009311";
    private static final String TASK_ID = "00000000-0000-0000-0000-000000009321";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin rabbitAdmin;

    @Autowired
    private IngestionTaskMapper ingestionTaskMapper;

    @Autowired
    private DocumentStorageService documentStorageService;

    @Autowired
    private G1RuntimeEmbeddingRecoveryTestConfig.EmbeddingRecoveryProbe embeddingRecoveryProbe;

    @Autowired
    private G1RuntimeEmbeddingRecoveryTestConfig.IngestionProcessingProbe ingestionProcessingProbe;

    private Path storageRoot;

    @BeforeEach
    void setUp() throws Exception {
        storageRoot = Path.of(System.getProperty("g1.storage.dir"));
        clearStorage();
        purgeQueues();
        jdbcTemplate.execute("DROP TABLE IF EXISTS chunk_bge_m3, ingestion_task, document, knowledge_base, jchatmind_user CASCADE");
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbcTemplate.execute("CREATE TABLE jchatmind_user (user_id BIGINT PRIMARY KEY, account VARCHAR(128), username VARCHAR(128), password VARCHAR(128))");
        jdbcTemplate.execute("CREATE TABLE knowledge_base (id UUID PRIMARY KEY, name VARCHAR(128), description VARCHAR(255), metadata JSONB, owner_id BIGINT NOT NULL REFERENCES jchatmind_user(user_id), created_at TIMESTAMP, updated_at TIMESTAMP)");
        jdbcTemplate.execute("CREATE TABLE document (id UUID PRIMARY KEY, kb_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE, filename VARCHAR(255), filetype VARCHAR(32), size BIGINT, metadata JSONB, created_at TIMESTAMP, updated_at TIMESTAMP)");
        jdbcTemplate.execute("CREATE TABLE chunk_bge_m3 (id UUID PRIMARY KEY DEFAULT gen_random_uuid(), kb_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE, doc_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE, content TEXT, metadata JSONB, embedding vector(1024), created_at TIMESTAMP, updated_at TIMESTAMP)");
        jdbcTemplate.execute("CREATE TABLE ingestion_task (task_id UUID PRIMARY KEY, owner_id BIGINT NOT NULL REFERENCES jchatmind_user(user_id), kb_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE, document_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE, idempotency_key VARCHAR(128) NOT NULL, task_type VARCHAR(64) NOT NULL, status VARCHAR(32) NOT NULL, attempt_count INTEGER NOT NULL, max_attempts INTEGER NOT NULL, error_summary VARCHAR(500), created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL, started_at TIMESTAMP, completed_at TIMESTAMP, UNIQUE(owner_id, idempotency_key))");
        jdbcTemplate.update("INSERT INTO jchatmind_user VALUES (?, ?, ?, ?)", OWNER_ID, "g1-embedding-recovery", "g1", "isolated");
        jdbcTemplate.update("INSERT INTO knowledge_base VALUES (?::uuid, ?, ?, '{}'::jsonb, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", KB_ID, "G1 embedding recovery", "isolated", OWNER_ID);

        Path documentDir = storageRoot.resolve(KB_ID).resolve(DOCUMENT_ID);
        Files.createDirectories(documentDir);
        Files.writeString(documentDir.resolve("recovery.md"), "# Recovery Guide\nTransient embedding failure must recover.\n\n## Retry\nRabbitMQ returns the task after its TTL.\n");
        String relativePath = KB_ID + "/" + DOCUMENT_ID + "/recovery.md";
        jdbcTemplate.update("INSERT INTO document VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                DOCUMENT_ID, KB_ID, "recovery.md", "md", 110, "{\"filePath\":\"" + relativePath + "\"}");
        jdbcTemplate.update("INSERT INTO ingestion_task VALUES (?::uuid, ?, ?::uuid, ?::uuid, ?, ?, ?, ?, ?, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, NULL)",
                TASK_ID, OWNER_ID, KB_ID, DOCUMENT_ID, "g1-embedding-recovery-key", "DOCUMENT_INGESTION", "QUEUED", 0, 3);
        assertStoredFileReadable(relativePath, documentDir.resolve("recovery.md"));
    }

    @AfterEach
    void tearDown() throws Exception {
        purgeQueues();
        jdbcTemplate.execute("DROP TABLE IF EXISTS chunk_bge_m3, ingestion_task, document, knowledge_base, jchatmind_user CASCADE");
        clearStorage();
    }

    @Test
    void shouldRecoverThroughRabbitRetryWhenEmbeddingDependencyReturns() throws Exception {
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.INGESTION_EXCHANGE,
                RabbitMQConfig.INGESTION_ROUTING_KEY,
                TASK_ID
        );

        awaitStatus("RETRYING", 1, Duration.ofSeconds(15));
        assertThat(embeddingRecoveryProbe.attemptCount())
                .as("processor failure: %s", ingestionProcessingProbe.lastFailure())
                .isEqualTo(1);
        assertThat(embeddingRecoveryProbe.lastEndpoint()).isEqualTo("unavailable");
        assertThat(retryQueueMessageCount()).isGreaterThanOrEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chunk_bge_m3", Integer.class)).isZero();
        assertThat(regularFileCount()).isEqualTo(1);

        awaitStatus("SUCCEEDED", 1, Duration.ofSeconds(90));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chunk_bge_m3", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chunk_bge_m3 WHERE embedding IS NOT NULL", Integer.class)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chunk_bge_m3 WHERE vector_dims(embedding) = 1024", Integer.class)).isEqualTo(2);
        assertThat(regularFileCount()).isEqualTo(1);
    }

    private void awaitStatus(String expectedStatus, int expectedAttemptCount, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            var task = ingestionTaskMapper.selectById(TASK_ID);
            if (task != null && expectedStatus.equals(task.getStatus())
                    && expectedAttemptCount == task.getAttemptCount()) {
                return;
            }
            Thread.sleep(100);
        }
        var task = ingestionTaskMapper.selectById(TASK_ID);
        throw new AssertionError("任务未达到预期状态: expected=" + expectedStatus + "/" + expectedAttemptCount
                + ", actual=" + (task == null ? "missing" : task.getStatus() + "/" + task.getAttemptCount()
                + ", error=" + task.getErrorSummary())
                + ", embeddingAttempts=" + embeddingRecoveryProbe.attemptCount()
                + ", lastEndpoint=" + embeddingRecoveryProbe.lastEndpoint()
                + ", lastFailure=" + embeddingRecoveryProbe.lastFailure());
    }

    private int retryQueueMessageCount() {
        return rabbitTemplate.execute(channel -> channel.queueDeclarePassive(RabbitMQConfig.INGESTION_RETRY_QUEUE).getMessageCount());
    }

    private void assertStoredFileReadable(String relativePath, Path expectedFile) throws Exception {
        Path storedFile = documentStorageService.getFilePath(relativePath);
        assertThat(storedFile).isEqualTo(expectedFile);
        assertThat(Files.isRegularFile(storedFile)).isTrue();
        try (var inputStream = Files.newInputStream(storedFile)) {
            assertThat(inputStream.read()).isNotEqualTo(-1);
        }
    }

    private long regularFileCount() throws Exception {
        try (var paths = Files.walk(storageRoot)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    private void purgeQueues() {
        for (String queue : List.of(
                RabbitMQConfig.INGESTION_QUEUE,
                RabbitMQConfig.INGESTION_RETRY_QUEUE,
                RabbitMQConfig.INGESTION_DLQ
        )) {
            try {
                rabbitAdmin.purgeQueue(queue, true);
            } catch (Exception ignored) {
            }
        }
    }

    private void clearStorage() throws Exception {
        if (!Files.exists(storageRoot)) {
            return;
        }
        try (var paths = Files.walk(storageRoot)) {
            paths.sorted(Comparator.reverseOrder())
                    .filter(path -> !path.equals(storageRoot))
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception e) {
                            throw new IllegalStateException("无法清理测试临时文件", e);
                        }
                    });
        }
    }
}
