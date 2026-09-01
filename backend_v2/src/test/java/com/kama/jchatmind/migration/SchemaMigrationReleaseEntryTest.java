package com.kama.jchatmind.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

class SchemaMigrationReleaseEntryTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldExposeExplicitReleaseEntry() {
        Class<?> entryType = load("com.kama.jchatmind.migration.SchemaMigrationReleaseEntry");

        assertThat(entryType).as("数据库迁移必须通过显式发布入口执行").isNotNull();
    }

    @Test
    void shouldWriteSuccessReportWithoutConnectionSecrets() throws Exception {
        Path reportPath = tempDir.resolve("release.json");
        SchemaMigrationExecutor.MigrationRunResult migration = new SchemaMigrationExecutor.MigrationRunResult(
                false, "2026-08-30", List.of("migration.one")
        );
        MigrationCatalogVerifier.VerificationResult catalog = new MigrationCatalogVerifier.VerificationResult(
                true, List.of(), List.of(), "c".repeat(64), "f".repeat(64)
        );

        SchemaMigrationReleaseEntry.ReleaseResult result = new SchemaMigrationReleaseEntry(
                () -> migration,
                () -> catalog,
                new SchemaMigrationReleaseEntry.ReleaseMetadata(
                        "a".repeat(64),
                        "b".repeat(64),
                        List.of("manual.owner-review"),
                        "release-1",
                        "commit-1",
                        "manual_restore_and_rebuild_required",
                        "maintenance-window execution only"
                ),
                reportPath
        ).run();

        assertThat(result.status()).isEqualTo(SchemaMigrationReleaseEntry.ReleaseStatus.SUCCEEDED);
        JsonNode report = new ObjectMapper().readTree(Files.readString(reportPath));
        assertThat(report.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(report.path("schemaVersion").asText()).isEqualTo("2026-08-30");
        assertThat(report.path("catalogVerified").asBoolean()).isTrue();
        assertThat(report.path("manifestSha256").asText()).isEqualTo("a".repeat(64));
        assertThat(report.path("approvedBaselineSha256").asText()).isEqualTo("b".repeat(64));
        assertThat(report.path("approvedPrerequisites").toString()).contains("manual.owner-review");
        assertThat(report.path("releaseId").asText()).isEqualTo("release-1");
        assertThat(report.path("codeRevision").asText()).isEqualTo("commit-1");
        assertThat(report.toString()).doesNotContain("jdbc", "password", "secret");
    }

    @Test
    void shouldFailClosedAndWriteFailureReportWhenCatalogDoesNotMatch() throws Exception {
        Path reportPath = tempDir.resolve("failure.json");
        MigrationCatalogVerifier.VerificationResult catalog = new MigrationCatalogVerifier.VerificationResult(
                false,
                List.of("table:public::missing:"),
                List.of("column:public:agent:allowed_kbs:"),
                "c".repeat(64),
                "f".repeat(64)
        );

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> new SchemaMigrationReleaseEntry(
                () -> new SchemaMigrationExecutor.MigrationRunResult(true, "2026-08-30", List.of()),
                () -> catalog,
                reportPath
        ).run())).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("catalog verification failed");

        JsonNode report = new ObjectMapper().readTree(Files.readString(reportPath));
        assertThat(report.path("status").asText()).isEqualTo("FAILED");
        assertThat(report.path("errorType").asText()).isEqualTo(IllegalStateException.class.getName());
        assertThat(report.path("catalogMissingObjects").toString()).contains("missing");
        assertThat(report.path("catalogForbiddenObjects").toString()).contains("allowed_kbs");
    }

    @Test
    void shouldFailClosedWhenMigrationRunnerReturnsNull() throws Exception {
        Path reportPath = tempDir.resolve("null-migration.json");
        MigrationCatalogVerifier.VerificationResult catalog = new MigrationCatalogVerifier.VerificationResult(
                true, List.of(), List.of(), "c".repeat(64), "f".repeat(64)
        );

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> new SchemaMigrationReleaseEntry(
                () -> null,
                () -> catalog,
                reportPath
        ).run()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Migration runner returned null");

        JsonNode report = new ObjectMapper().readTree(Files.readString(reportPath));
        assertThat(report.path("status").asText()).isEqualTo("FAILED");
    }

    private Class<?> load(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }
}
