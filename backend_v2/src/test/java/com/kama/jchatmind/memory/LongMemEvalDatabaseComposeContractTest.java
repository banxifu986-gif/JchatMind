package com.kama.jchatmind.memory;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LongMemEvalDatabaseComposeContractTest {

    private static final Path COMPOSE_PATH = Path.of("..", "docker-compose.longmemeval-eval.yml");
    private static final Path SCHEMA_PATH = Path.of("..", "longmemeval-eval", "init", "001-memory-evaluation-schema.sql");

    @Test
    void provisionsMemoryEvaluationDatabaseOnItsOwnPortAndVolume() throws Exception {
        assertThat(Files.exists(COMPOSE_PATH)).isTrue();
        assertThat(Files.exists(SCHEMA_PATH)).isTrue();
        String compose = Files.readString(COMPOSE_PATH);

        assertThat(compose)
                .contains("container_name: jchatmind-longmemeval-eval-postgres")
                .contains("POSTGRES_DB: longmemeval_eval")
                .contains("POSTGRES_USER: longmemeval_eval")
                .contains("127.0.0.1:55433:5432")
                .contains("longmemeval_eval_postgres_data")
                .contains("./longmemeval-eval/init:/docker-entrypoint-initdb.d:ro")
                .doesNotContain("jchatmind-rag-eval-postgres")
                .doesNotContain("rag_eval_postgres_data")
                .doesNotContain("55432:5432");
    }
}
