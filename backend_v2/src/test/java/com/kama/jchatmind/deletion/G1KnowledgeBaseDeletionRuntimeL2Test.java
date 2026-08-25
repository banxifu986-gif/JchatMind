package com.kama.jchatmind.deletion;

import com.kama.jchatmind.auth.RequestScopeData;
import com.kama.jchatmind.config.RabbitMQConfig;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.KnowledgeBaseDeletionTaskMapper;
import com.kama.jchatmind.model.entity.KnowledgeBaseDeletionTask;
import com.kama.jchatmind.service.impl.KnowledgeBaseDeletionTaskServiceImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringJUnitConfig(G1KnowledgeBaseDeletionRuntimeL2TestConfig.class)
@EnabledIfEnvironmentVariable(named = "G1_KNOWLEDGE_BASE_DELETION_L2", matches = "true")
class G1KnowledgeBaseDeletionRuntimeL2Test {

    private static final long OWNER_ID = 70001L;
    private static final long OTHER_OWNER_ID = 70002L;
    private static final String KB_ID = "00000000-0000-0000-0000-000000009101";

    @org.springframework.beans.factory.annotation.Autowired
    private JdbcTemplate jdbcTemplate;

    @org.springframework.beans.factory.annotation.Autowired
    private RequestScopeData requestScopeData;

    @org.springframework.beans.factory.annotation.Autowired
    private KnowledgeBaseDeletionTaskServiceImpl deletionTaskService;

    @org.springframework.beans.factory.annotation.Autowired
    private KnowledgeBaseDeletionTaskMapper deletionTaskMapper;

    @org.springframework.beans.factory.annotation.Autowired
    private AmqpAdmin rabbitAdmin;

    private Path storageRoot;

    @BeforeEach
    void setUp() throws Exception {
        storageRoot = Path.of(System.getProperty("g1.storage.dir"));
        clearStorage();
        purgeQueues();
        jdbcTemplate.execute("DROP TABLE IF EXISTS knowledge_base_deletion_audit, knowledge_base_deletion_task, chunk_bge_m3, ingestion_task, document, knowledge_base, jchatmind_user CASCADE");
        jdbcTemplate.execute("CREATE TABLE jchatmind_user (user_id BIGINT PRIMARY KEY, account VARCHAR(128), username VARCHAR(128), password VARCHAR(128))");
        jdbcTemplate.execute("CREATE TABLE knowledge_base (id UUID PRIMARY KEY, name VARCHAR(128), description VARCHAR(255), metadata JSONB, owner_id BIGINT NOT NULL REFERENCES jchatmind_user(user_id), created_at TIMESTAMP, updated_at TIMESTAMP)");
        jdbcTemplate.execute("CREATE TABLE document (id UUID PRIMARY KEY, kb_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE, filename VARCHAR(255), filetype VARCHAR(32), size BIGINT, metadata JSONB, created_at TIMESTAMP, updated_at TIMESTAMP)");
        jdbcTemplate.execute("CREATE TABLE chunk_bge_m3 (id UUID PRIMARY KEY, kb_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE, doc_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE)");
        jdbcTemplate.execute("CREATE TABLE ingestion_task (task_id UUID PRIMARY KEY, owner_id BIGINT NOT NULL REFERENCES jchatmind_user(user_id), kb_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE, document_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE)");
        applyDeletionTaskMigration();
        jdbcTemplate.update("INSERT INTO jchatmind_user VALUES (?, ?, ?, ?), (?, ?, ?, ?)", OWNER_ID, "g1-deletion-owner", "owner", "isolated", OTHER_OWNER_ID, "g1-deletion-other", "other", "isolated");
        jdbcTemplate.update("INSERT INTO knowledge_base VALUES (?::uuid, ?, ?, '{}'::jsonb, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", KB_ID, "G1 deletion", "isolated", OWNER_ID);
        jdbcTemplate.update("INSERT INTO document VALUES ('00000000-0000-0000-0000-000000009111'::uuid, ?::uuid, ?, ?, ?, '{}'::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)", KB_ID, "evidence.md", "md", 20);
        jdbcTemplate.update("INSERT INTO chunk_bge_m3 VALUES ('00000000-0000-0000-0000-000000009121'::uuid, ?::uuid, '00000000-0000-0000-0000-000000009111'::uuid)", KB_ID);
        jdbcTemplate.update("INSERT INTO ingestion_task VALUES ('00000000-0000-0000-0000-000000009131'::uuid, ?, ?::uuid, '00000000-0000-0000-0000-000000009111'::uuid)", OWNER_ID, KB_ID);
        Files.createDirectories(storageRoot.resolve(KB_ID));
        Files.writeString(storageRoot.resolve(KB_ID).resolve("evidence.md"), "isolated deletion evidence");
        requestScopeData.setUserId(OWNER_ID);
    }

    @AfterEach
    void tearDown() throws Exception {
        purgeQueues();
        jdbcTemplate.execute("DROP TABLE IF EXISTS knowledge_base_deletion_audit, knowledge_base_deletion_task, chunk_bge_m3, ingestion_task, document, knowledge_base, jchatmind_user CASCADE");
        clearStorage();
    }

    @Test
    void shouldRejectSecondOwnerBeforeCreatingDeletionTask() {
        requestScopeData.setUserId(OTHER_OWNER_ID);

        assertThatThrownBy(() -> deletionTaskService.requestDeletion(KB_ID))
                .isInstanceOf(BizException.class)
                .hasMessage("无权访问知识库");

        assertThat(countRows("knowledge_base")).isEqualTo(1);
        assertThat(countRows("knowledge_base_deletion_task")).isZero();
        assertThat(Files.exists(storageRoot.resolve(KB_ID))).isTrue();
    }

    @Test
    void shouldCompleteOwnerDeletionThroughRealRabbitAndKeepAudit() throws Exception {
        KnowledgeBaseDeletionTask task = deletionTaskService.requestDeletion(KB_ID);

        awaitTask(task.getId(), "SUCCEEDED");

        KnowledgeBaseDeletionTask completed = deletionTaskMapper.selectById(task.getId());
        assertThat(countRows("knowledge_base")).isZero();
        assertThat(countRows("document")).isZero();
        assertThat(countRows("chunk_bge_m3")).isZero();
        assertThat(countRows("ingestion_task")).isZero();
        assertThat(countRows("knowledge_base_deletion_task")).isEqualTo(1);
        assertThat(countRows("knowledge_base_deletion_audit")).isEqualTo(1);
        assertThat(completed.getStatus()).isEqualTo("SUCCEEDED");
        assertThat(completed.getProgress()).isEqualTo(100);
        assertThat(completed.getAttemptCount()).isZero();
        assertThat(Files.exists(storageRoot.resolve(KB_ID))).isFalse();
    }

    @Test
    void shouldHideOwnerDeletionTaskFromSecondOwner() {
        KnowledgeBaseDeletionTask task = deletionTaskService.requestDeletion(KB_ID);
        requestScopeData.setUserId(OTHER_OWNER_ID);

        assertThatThrownBy(() -> deletionTaskService.getTask(task.getId()))
                .isInstanceOf(BizException.class)
                .hasMessage("无权访问知识库删除任务");
    }

    private void applyDeletionTaskMigration() throws Exception {
        Path migration = Path.of(
                System.getProperty("user.dir"),
                "..",
                "sql",
                "knowledge-base",
                "2026-08-25-create-knowledge-base-deletion-task.sql"
        ).normalize();
        jdbcTemplate.execute(Files.readString(migration));
    }

    private int countRows(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private void awaitTask(String taskId, String expectedStatus) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            KnowledgeBaseDeletionTask task = deletionTaskMapper.selectById(taskId);
            if (task != null && expectedStatus.equals(task.getStatus())) {
                return;
            }
            Thread.onSpinWait();
        }
        KnowledgeBaseDeletionTask task = deletionTaskMapper.selectById(taskId);
        throw new AssertionError("删除任务未达到预期状态: expected=" + expectedStatus
                + ", actual=" + (task == null ? "missing" : task.getStatus()));
    }

    private void purgeQueues() {
        for (String queue : List.of(
                RabbitMQConfig.KNOWLEDGE_BASE_DELETION_QUEUE,
                RabbitMQConfig.KNOWLEDGE_BASE_DELETION_RETRY_QUEUE,
                RabbitMQConfig.KNOWLEDGE_BASE_DELETION_DLQ
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
                            throw new IllegalStateException("无法清理隔离删除任务测试文件", e);
                        }
                    });
        }
    }
}
