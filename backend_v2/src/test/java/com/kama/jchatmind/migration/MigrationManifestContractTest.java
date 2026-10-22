package com.kama.jchatmind.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationManifestContractTest {

    private static final Path MANIFEST = Path.of("..", "sql", "migrations", "manifest.json");
    private static final List<String> EXPECTED_ORDER = List.of(
            "sql/auth/2026-05-26-create-user-table.sql",
            "sql/auth/2026-05-26-create-email-failure-table.sql",
            "sql/user-memory/2026-05-25-add-user-memory-columns.sql",
            "sql/user-memory/2026-05-25-add-embedding-column.sql",
            "sql/user-memory/2026-05-25-add-user-memory-candidate-status.sql",
            "sql/user-memory/2026-08-25-add-user-memory-expires-at.sql",
            "sql/user-memory/2026-08-25-add-user-memory-superseded-by.sql",
            "sql/user-memory/cosine_index.sql",
            "sql/knowledge-base/2026-08-18-add-knowledge-base-owner.sql",
            "sql/knowledge-base/2026-08-18-migrate-agent-knowledge-base.sql",
            "sql/knowledge-base/2026-08-18-enforce-knowledge-base-owner-not-null.sql",
            "sql/ingestion/2026-08-18-create-ingestion-task.sql",
            "sql/ingestion/2026-08-22-create-document-asset.sql",
            "sql/knowledge-base/2026-08-22-add-vchord-bm25-index.sql",
            "sql/mcp/2026-08-18-create-mcp-principal-access.sql",
            "sql/knowledge-base/2026-08-25-create-knowledge-base-deletion-task.sql",
            "sql/workflow/2026-10-21-create-workflow-execution-audit.sql",
            "sql/webhook/2026-10-21-create-webhook-delivery-outbox.sql",
            "sql/knowledge-base/2026-10-21-create-rag-index-revision.sql",
            "sql/chat/2026-10-21-create-agent-execution-queue.sql",
            "sql/skill/2026-10-22-create-builtin-skill-task.sql",
            "sql/skill/2026-10-22-add-builtin-skill-task-lease-index.sql"
    );

    @Test
    void shouldDeclareOneFailClosedOrderedMigrationManifestWithVerifiedHashes() throws Exception {
        assertThat(Files.exists(MANIFEST)).isTrue();

        JsonNode root;
        try (InputStream input = Files.newInputStream(MANIFEST)) {
            root = new ObjectMapper().readTree(input);
        }

        assertThat(root.path("manifestVersion").asText()).isEqualTo("1");
        assertThat(root.path("schemaVersion").asText()).isNotBlank();
        assertThat(root.path("baseline").path("required").asBoolean()).isTrue();
        assertThat(root.path("baseline").path("source").asText()).isNotBlank();
        assertThat(root.path("baseline").path("failClosedOnUnknownState").asBoolean()).isTrue();

        JsonNode migrations = root.path("migrations");
        assertThat(migrations.isArray()).isTrue();
        assertThat(migrations).hasSize(EXPECTED_ORDER.size());

        Set<String> ids = new HashSet<>();
        Set<String> paths = new HashSet<>();
        List<String> actualOrder = new ArrayList<>();
        int previousOrder = 0;
        for (JsonNode migration : migrations) {
            int order = migration.path("order").asInt();
            String id = migration.path("id").asText();
            String relativePath = migration.path("path").asText();
            String sha256 = migration.path("sha256").asText();

            assertThat(order).isGreaterThan(previousOrder);
            assertThat(ids.add(id)).isTrue();
            assertThat(paths.add(relativePath)).isTrue();
            assertThat(relativePath).startsWith("sql/").doesNotContain("..");
            assertThat(sha256).matches("[0-9a-f]{64}");
            assertThat(migration.path("transactional").isBoolean()).isTrue();
            assertThat(migration.path("requires").isArray()).isTrue();
            assertThat(Files.exists(Path.of("..", relativePath))).isTrue();
            assertThat(sha256(Path.of("..", relativePath))).isEqualTo(sha256);

            previousOrder = order;
            actualOrder.add(relativePath);
        }

        assertThat(actualOrder).containsExactlyElementsOf(EXPECTED_ORDER);
        assertThat(migrations.get(7).path("transactional").asBoolean()).isFalse();
        assertThat(root.path("execution").path("cleanInstall").asText()).isNotBlank();
        assertThat(root.path("execution").path("upgrade").asText()).isNotBlank();
        assertThat(root.path("execution").path("unknownState").asText()).containsIgnoringCase("fail");
        String catalogContract = root.path("catalogContract").asText();
        assertThat(catalogContract).isEqualTo("sql/migrations/catalog-contract.json");
        assertThat(Files.exists(Path.of("..", catalogContract))).isTrue();
        assertThat(root.path("catalogContractSha256").asText())
                .isEqualTo(sha256(Path.of("..", catalogContract)));
    }

    @Test
    void shouldDeclareAnIdempotentBuiltinSkillTaskLedgerAfterTheAgentQueue() throws Exception {
        JsonNode root;
        try (InputStream input = Files.newInputStream(MANIFEST)) {
            root = new ObjectMapper().readTree(input);
        }

        JsonNode migration = null;
        for (JsonNode candidate : root.path("migrations")) {
            if (candidate.path("id").asText().equals("skill.create-builtin-execution-task")) {
                migration = candidate;
                break;
            }
        }
        String path = "sql/skill/2026-10-22-create-builtin-skill-task.sql";
        assertThat(migration).isNotNull();
        assertThat(migration.path("id").asText()).isEqualTo("skill.create-builtin-execution-task");
        assertThat(migration.path("order").asInt()).isEqualTo(210);
        assertThat(migration.path("path").asText()).isEqualTo(path);
        assertThat(migration.path("transactional").asBoolean()).isTrue();
        assertThat(migration.path("requires").toString()).contains("chat.create-agent-execution-queue");
        assertThat(migration.path("sha256").asText()).isEqualTo(sha256(Path.of("..", path)));

        String catalogPath = root.path("catalogContract").asText();
        JsonNode catalog;
        try (InputStream input = Files.newInputStream(Path.of("..", catalogPath))) {
            catalog = new ObjectMapper().readTree(input);
        }
        assertThat(catalog.path("requiredTables").toString()).contains("builtin_skill_execution_task");
        assertThat(catalog.path("requiredColumns").path("builtin_skill_execution_task").toString())
                .contains("idempotency_key", "input_snapshot", "result_json", "attempt_count");
        assertThat(catalog.path("requiredConstraints").toString())
                .contains("uq_builtin_skill_task_requester_idempotency", "chk_builtin_skill_task_status");
        assertThat(catalog.path("requiredIndexes").toString())
                .contains("idx_builtin_skill_task_dispatch", "idx_builtin_skill_task_requester_created_at");

        String sql = Files.readString(Path.of("..", path));
        assertThat(sql)
                .contains("CREATE TABLE builtin_skill_execution_task")
                .contains("requester_user_id")
                .contains("skill_version")
                .contains("idempotency_key")
                .contains("input_snapshot")
                .contains("result_json")
                .contains("progress")
                .contains("attempt_count")
                .contains("failure_type")
                .contains("UNIQUE (requester_user_id, idempotency_key)")
                .contains("'PENDING'")
                .contains("'RUNNING'")
                .contains("'RETRYING'")
                .contains("'SUCCEEDED'")
                .contains("'FAILED'")
                .contains("'DEAD_LETTER'");
    }

    @Test
    void shouldDeclareARunningLeaseRecoveryIndexAfterTheBuiltinSkillTaskLedger() throws Exception {
        JsonNode root;
        try (InputStream input = Files.newInputStream(MANIFEST)) {
            root = new ObjectMapper().readTree(input);
        }

        JsonNode migration = root.path("migrations").get(root.path("migrations").size() - 1);
        String path = "sql/skill/2026-10-22-add-builtin-skill-task-lease-index.sql";
        assertThat(migration.path("id").asText()).isEqualTo("skill.add-builtin-execution-task-lease-index");
        assertThat(migration.path("order").asInt()).isEqualTo(220);
        assertThat(migration.path("path").asText()).isEqualTo(path);
        assertThat(migration.path("transactional").asBoolean()).isTrue();
        assertThat(migration.path("requires").toString()).contains("skill.create-builtin-execution-task");
        assertThat(migration.path("sha256").asText()).isEqualTo(sha256(Path.of("..", path)));

        JsonNode catalog;
        String catalogPath = root.path("catalogContract").asText();
        try (InputStream input = Files.newInputStream(Path.of("..", catalogPath))) {
            catalog = new ObjectMapper().readTree(input);
        }
        assertThat(catalog.path("requiredIndexes").toString())
                .contains("idx_builtin_skill_task_running_lease");

        String sql = Files.readString(Path.of("..", path));
        assertThat(sql)
                .contains("CREATE INDEX idx_builtin_skill_task_running_lease")
                .contains("ON builtin_skill_execution_task (lease_expires_at, task_id)")
                .contains("WHERE status = 'RUNNING' AND lease_expires_at IS NOT NULL");
    }

    private String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }
}
