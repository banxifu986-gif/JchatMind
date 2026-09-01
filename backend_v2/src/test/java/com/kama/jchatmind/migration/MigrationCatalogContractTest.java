package com.kama.jchatmind.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrationCatalogContractTest {

    @TempDir
    Path tempDir;

    private static final Path CONTRACT = Path.of("..", "sql", "migrations", "catalog-contract.json");

    @Test
    void shouldDeclareAuditableManagedCatalogContract() throws Exception {
        assertThat(Files.exists(CONTRACT)).isTrue();

        JsonNode root = new ObjectMapper().readTree(Files.readString(CONTRACT));
        assertThat(root.path("contractVersion").asText()).isEqualTo("1");
        assertThat(root.path("schema").asText()).isEqualTo("public");
        assertThat(root.path("requiredExtensions").isArray()).isTrue();
        assertThat(root.path("allowedExtensions").isArray()).isTrue();
        assertThat(root.path("requiredTables").isArray()).isTrue();
        assertThat(root.path("allowedTables").isArray()).isTrue();
        assertThat(root.path("requiredColumns").isObject()).isTrue();
        assertThat(root.path("allowedColumns").isObject()).isTrue();
        assertThat(root.path("forbiddenColumns").isObject()).isTrue();
        assertThat(root.path("requiredConstraints").isArray()).isTrue();
        assertThat(root.path("allowedConstraints").isArray()).isTrue();
        assertThat(root.path("requiredIndexes").isArray()).isTrue();
        assertThat(root.path("allowedIndexes").isArray()).isTrue();
        assertThat(root.path("requiredFunctions").isArray()).isTrue();
        assertThat(root.path("allowedFunctions").isArray()).isTrue();
        assertThat(root.path("requiredTriggers").isArray()).isTrue();
        assertThat(root.path("allowedTriggers").isArray()).isTrue();

        Set<String> tables = new HashSet<>();
        root.path("requiredTables").forEach(table -> tables.add(table.asText()));
        assertThat(tables).contains(
                "jchatmind_schema_migration_ledger",
                "knowledge_base_deletion_task",
                "knowledge_base_deletion_audit",
                "mcp_principal",
                "mcp_principal_credential",
                "mcp_principal_user_grant",
                "mcp_access_audit"
        );
        assertThat(root.path("requiredExtensions").toString())
                .contains("vector", "vchord_bm25");
        assertThat(root.path("forbiddenColumns").path("agent").toString())
                .contains("allowed_kbs");
    }

    @Test
    void shouldRejectNonArrayColumnContractEntries() throws Exception {
        Path invalid = tempDir.resolve("invalid-catalog-contract.json");
        Files.writeString(invalid, """
                {
                  "contractVersion": "1",
                  "schema": "public",
                  "requiredExtensions": [],
                  "requiredTables": [],
                  "requiredColumns": {"agent": "allowed_kbs"}
                }
                """);

        assertThatThrownBy(() -> MigrationCatalogContract.load(invalid))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Migration catalog contract field agent must be an array");
    }
}
