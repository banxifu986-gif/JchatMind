package com.kama.jchatmind.ingestion;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionTaskMigrationContractTest {

    private static final Path MIGRATION = Path.of(
            "..", "sql", "ingestion", "2026-08-18-create-ingestion-task.sql"
    );

    @Test
    void shouldDefineOwnerScopedIdempotentIngestionTaskTable() throws Exception {
        assertThat(Files.exists(MIGRATION)).isTrue();

        String migration = Files.readString(MIGRATION).toLowerCase();

        assertThat(migration)
                .contains("create table ingestion_task")
                .contains("owner_id")
                .contains("kb_id")
                .contains("document_id")
                .contains("idempotency_key")
                .contains("unique (owner_id, idempotency_key)")
                .contains("foreign key (owner_id) references jchatmind_user(user_id)")
                .contains("foreign key (kb_id) references knowledge_base(id)")
                .contains("queued")
                .contains("running")
                .contains("retrying")
                .contains("failed")
                .contains("dead_letter")
                .contains("cancelled")
                .contains("succeeded")
                .contains("create index idx_ingestion_task_owner_created_at")
                .contains("create index idx_ingestion_task_status_created_at");
    }
}
