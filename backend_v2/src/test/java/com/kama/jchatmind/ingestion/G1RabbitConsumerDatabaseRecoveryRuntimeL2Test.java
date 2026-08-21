package com.kama.jchatmind.ingestion;

import com.kama.jchatmind.config.RabbitMQConfig;
import com.kama.jchatmind.mapper.IngestionTaskMapper;
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

@SpringJUnitConfig(G1RuntimeRabbitRecoveryTestConfig.class)
@EnabledIfEnvironmentVariable(named = "G1_RABBIT_RECOVERY_L2", matches = "true")
class G1RabbitConsumerDatabaseRecoveryRuntimeL2Test {

    private static final long OWNER_ID = 70001L;
    private static final String KB_ID = "00000000-0000-0000-0000-000000008101";
    private static final String DOCUMENT_ID = "00000000-0000-0000-0000-000000008111";
    private static final String TASK_ID = "00000000-0000-0000-0000-000000008121";
    private static final String CHUNK_ID = "00000000-0000-0000-0000-000000008131";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin rabbitAdmin;

    @Autowired
    private IngestionTaskMapper ingestionTaskMapper;

    private Path storageRoot;

    @BeforeEach
    void setUp() throws Exception {
        storageRoot = Path.of(System.getProperty("g1.storage.dir"));
        clearStorage();
        purgeQueues();
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS g1_fail_chunk_delete()");
        jdbcTemplate.execute("DROP TABLE IF EXISTS chunk_bge_m3, ingestion_task, document, knowledge_base, jchatmind_user CASCADE");
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbcTemplate.execute("CREATE TABLE jchatmind_user (user_id BIGINT PRIMARY KEY, account VARCHAR(128), username VARCHAR(128), password VARCHAR(128))");
        jdbcTemplate.execute("CREATE TABLE knowledge_base (id UUID PRIMARY KEY, name VARCHAR(128), description VARCHAR(255), metadata JSONB, owner_id BIGINT NOT NULL REFERENCES jchatmind_user(user_id), created_at TIMESTAMP, updated_at TIMESTAMP)");
        jdbcTemplate.execute("CREATE TABLE document (id UUID PRIMARY KEY, kb_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE, filename VARCHAR(255), filetype VARCHAR(32), size BIGINT, metadata JSONB, created_at TIMESTAMP, updated_at TIMESTAMP)");
        jdbcTemplate.execute("CREATE TABLE chunk_bge_m3 (id UUID PRIMARY KEY, kb_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE, doc_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE, content TEXT, metadata JSONB, embedding vector(3), created_at TIMESTAMP, updated_at TIMESTAMP)");
        jdbcTemplate.execute("CREATE TABLE ingestion_task (task_id UUID PRIMARY KEY, owner_id BIGINT NOT NULL REFERENCES jchatmind_user(user_id), kb_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE, document_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE, idempotency_key VARCHAR(128) NOT NULL, task_type VARCHAR(64) NOT NULL, status VARCHAR(32) NOT NULL, attempt_count INTEGER NOT NULL, max_attempts INTEGER NOT NULL, error_summary VARCHAR(500), created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL, started_at TIMESTAMP, completed_at TIMESTAMP, UNIQUE(owner_id, idempotency_key))");
        jdbcTemplate.update("INSERT INTO jchatmind_user VALUES (?, ?, ?, ?)", OWNER_ID, "g1-rabbit-recovery", "g1", "isolated");
        jdbcTemplate.update("INSERT INTO knowledge_base VALUES (?::uuid, ?, ?, '{}'::jsonb, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", KB_ID, "G1 Rabbit recovery", "isolated", OWNER_ID);

        Path documentDir = storageRoot.resolve(KB_ID).resolve(DOCUMENT_ID);
        Files.createDirectories(documentDir);
        Files.writeString(documentDir.resolve("source.md"), "# Recovery test\n\nDatabase failure must rollback chunks.\n");
        String relativePath = KB_ID + "/" + DOCUMENT_ID + "/source.md";
        jdbcTemplate.update("INSERT INTO document VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                DOCUMENT_ID, KB_ID, "source.md", "md", 50, "{\"filePath\":\"" + relativePath + "\"}");
        jdbcTemplate.update("INSERT INTO chunk_bge_m3 VALUES (?::uuid, ?::uuid, ?::uuid, ?, '{}'::jsonb, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                CHUNK_ID, KB_ID, DOCUMENT_ID, "old chunk");
        jdbcTemplate.update("INSERT INTO ingestion_task VALUES (?::uuid, ?, ?::uuid, ?::uuid, ?, ?, ?, ?, ?, NULL, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, NULL, NULL)",
                TASK_ID, OWNER_ID, KB_ID, DOCUMENT_ID, "g1-rabbit-recovery-key", "DOCUMENT_INGESTION", "QUEUED", 0, 3);

        jdbcTemplate.execute("CREATE FUNCTION g1_fail_chunk_delete() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled chunk database failure'; END; $$");
        jdbcTemplate.execute("CREATE TRIGGER g1_fail_chunk_delete BEFORE DELETE ON chunk_bge_m3 FOR EACH ROW EXECUTE FUNCTION g1_fail_chunk_delete()");
    }

    @AfterEach
    void tearDown() throws Exception {
        purgeQueues();
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS g1_fail_chunk_delete ON chunk_bge_m3");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS g1_fail_chunk_delete()");
        jdbcTemplate.execute("DROP TABLE IF EXISTS chunk_bge_m3, ingestion_task, document, knowledge_base, jchatmind_user CASCADE");
        clearStorage();
    }

    @Test
    void shouldRollbackChunksAndRouteRealDatabaseFailureToRetryAndDeadLetter() throws Exception {
        publishMainTask();
        awaitStatus("RETRYING", 1);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM document", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ingestion_task", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chunk_bge_m3", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT attempt_count FROM ingestion_task WHERE task_id = ?::uuid", Integer.class, TASK_ID)).isEqualTo(1);
        assertThat(retryQueueMessageCount()).isGreaterThanOrEqualTo(1);
        assertThat(regularFileCount()).isEqualTo(1);

        publishMainTask();
        awaitStatus("RETRYING", 2);
        publishMainTask();
        awaitStatus("DEAD_LETTER", 3);

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM document", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM ingestion_task", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM chunk_bge_m3", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT attempt_count FROM ingestion_task WHERE task_id = ?::uuid", Integer.class, TASK_ID)).isEqualTo(3);
        assertThat(deadLetterQueueMessageCount()).isGreaterThanOrEqualTo(1);
        assertThat(regularFileCount()).isEqualTo(1);
    }

    private void publishMainTask() {
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.INGESTION_EXCHANGE,
                RabbitMQConfig.INGESTION_ROUTING_KEY,
                TASK_ID
        );
    }

    private void awaitStatus(String expectedStatus, int expectedAttemptCount) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            var task = ingestionTaskMapper.selectById(TASK_ID);
            if (task != null && expectedStatus.equals(task.getStatus())
                    && expectedAttemptCount == task.getAttemptCount()) {
                return;
            }
            Thread.onSpinWait();
        }
        var task = ingestionTaskMapper.selectById(TASK_ID);
        throw new AssertionError("任务未达到预期状态: expected=" + expectedStatus + "/" + expectedAttemptCount
                + ", actual=" + (task == null ? "missing" : task.getStatus() + "/" + task.getAttemptCount()));
    }

    private int retryQueueMessageCount() {
        return rabbitTemplate.execute(channel -> channel.queueDeclarePassive(RabbitMQConfig.INGESTION_RETRY_QUEUE).getMessageCount());
    }

    private int deadLetterQueueMessageCount() {
        return rabbitTemplate.execute(channel -> channel.queueDeclarePassive(RabbitMQConfig.INGESTION_DLQ).getMessageCount());
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
