package com.kama.jchatmind.ingestion;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class IngestionTaskPersistenceContractTest {

    @Test
    void shouldPersistTaskOwnerIdempotencyAndStateWithConditionalTransition() throws Exception {
        Path entity = Path.of(
                "src", "main", "java", "com", "kama", "jchatmind", "model", "entity", "IngestionTask.java"
        );
        Path mapper = Path.of(
                "src", "main", "java", "com", "kama", "jchatmind", "mapper", "IngestionTaskMapper.java"
        );
        Path mapperXml = Path.of(
                "src", "main", "resources", "mapper", "IngestionTaskMapper.xml"
        );

        assertThat(Files.exists(entity)).isTrue();
        assertThat(Files.exists(mapper)).isTrue();
        assertThat(Files.exists(mapperXml)).isTrue();

        assertThat(Files.readString(entity))
                .contains("ownerId")
                .contains("idempotencyKey")
                .contains("attemptCount")
                .contains("maxAttempts")
                .contains("errorSummary");
        assertThat(Files.readString(mapper))
                .contains("selectByOwnerIdAndIdempotencyKey")
                .contains("lockOwnerIdempotencyKey")
                .contains("updateStatusIfCurrent");

        String xml = Files.readString(mapperXml).toLowerCase();
        assertThat(xml)
                .contains("from ingestion_task")
                .contains("owner_id = #{ownerid}")
                .contains("idempotency_key = #{idempotencykey}")
                .contains("pg_advisory_xact_lock")
                .contains("hashtextextended")
                .contains("update ingestion_task")
                .contains("status = #{nextstatus}")
                .contains("and status = #{currentstatus}")
                .contains("on conflict (owner_id, idempotency_key) do nothing");
    }
}
