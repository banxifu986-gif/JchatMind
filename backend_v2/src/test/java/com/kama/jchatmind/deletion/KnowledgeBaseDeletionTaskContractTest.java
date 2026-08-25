package com.kama.jchatmind.deletion;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;

import com.kama.jchatmind.mapper.KnowledgeBaseDeletionTaskMapper;
import com.kama.jchatmind.model.entity.KnowledgeBaseDeletionTask;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class KnowledgeBaseDeletionTaskContractTest {

    @Test
    void shouldProvideIndependentPersistentDeletionTask() {
        Path migration = Path.of("..", "sql", "knowledge-base", "2026-08-25-create-knowledge-base-deletion-task.sql");

        assertThatCode(() -> Class.forName(
                "com.kama.jchatmind.service.impl.KnowledgeBaseDeletionTaskServiceImpl"
        )).doesNotThrowAnyException();
        assertThat(Files.exists(migration)).isTrue();
    }

    @Test
    void shouldExposeDeletionRequestThatReturnsDeletionTask() throws Exception {
        Class<?> serviceType = Class.forName(
                "com.kama.jchatmind.service.impl.KnowledgeBaseDeletionTaskServiceImpl"
        );

        Method method = serviceType.getDeclaredMethod("requestDeletion", String.class);

        assertThat(method.getReturnType().getName())
                .isEqualTo("com.kama.jchatmind.model.entity.KnowledgeBaseDeletionTask");
    }

    @Test
    void shouldPersistDeletionRetryStateIndependentlyOfDeletedKnowledgeBase() throws Exception {
        Path migration = Path.of("..", "sql", "knowledge-base", "2026-08-25-create-knowledge-base-deletion-task.sql");
        String migrationSql = Files.readString(migration);

        assertThat(migrationSql)
                .contains("owner_id")
                .contains("knowledge_base_id")
                .contains("status")
                .contains("attempt_count")
                .contains("task_type")
                .contains("idempotency_key")
                .contains("input_snapshot")
                .contains("progress")
                .contains("knowledge_base_deletion_audit")
                .doesNotContain("FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_base");
        assertThatCode(() -> Class.forName(
                "com.kama.jchatmind.mapper.KnowledgeBaseDeletionTaskMapper"
        )).doesNotThrowAnyException();
    }

    @Test
    void shouldExposeClaimCompletionAndRetryLifecycle() throws Exception {
        Class<?> serviceType = Class.forName(
                "com.kama.jchatmind.service.impl.KnowledgeBaseDeletionTaskServiceImpl"
        );

        assertThat(serviceType.getDeclaredMethod("claimTask", String.class).getReturnType())
                .isEqualTo(KnowledgeBaseDeletionTask.class);
        assertThat(serviceType.getDeclaredMethod("completeClaimedTask", KnowledgeBaseDeletionTask.class).getReturnType())
                .isEqualTo(void.class);
        assertThat(serviceType.getDeclaredMethod(
                "failClaimedTask",
                KnowledgeBaseDeletionTask.class,
                String.class
                ).getReturnType()).isEqualTo(String.class);
    }

    @Test
    void shouldSerializeDuplicateDeletionRequestsByOwnerAndIdempotencyKey() throws Exception {
        assertThat(KnowledgeBaseDeletionTaskMapper.class.getMethod(
                "selectByOwnerIdAndIdempotencyKey",
                Long.class,
                String.class
        ).getReturnType()).isEqualTo(KnowledgeBaseDeletionTask.class);
        assertThat(KnowledgeBaseDeletionTaskMapper.class.getMethod(
                "lockOwnerIdempotencyKey",
                Long.class,
                String.class
                ).getReturnType()).isEqualTo(Integer.class);
    }

    @Test
    void shouldReportMidpointProgressWhileDeletionDirectoryIsRunning() throws Exception {
        String mapperXml = Files.readString(Path.of(
                "src",
                "main",
                "resources",
                "mapper",
                "KnowledgeBaseDeletionTaskMapper.xml"
        ));

        assertThat(mapperXml).contains("WHEN #{nextStatus} = 'RUNNING' THEN 50");
        assertThat(mapperXml).contains("WHEN #{nextStatus} = 'SUCCEEDED' THEN 100");
    }
}
