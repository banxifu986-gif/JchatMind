package com.kama.jchatmind.migration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MigrationReleaseApplicationTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldExposeCommandLineReleaseApplication() {
        Class<?> applicationType = load("com.kama.jchatmind.migration.MigrationReleaseApplication");

        assertThat(applicationType).as("发布流程必须有显式、可审计的命令行入口").isNotNull();
    }

    @Test
    void shouldRequireExplicitConfirmationBeforeOpeningDatabaseConnection() {
        assertThatThrownBy(() -> MigrationReleaseApplication.run(new String[] {
                "--jdbc-url", "jdbc:postgresql://127.0.0.1:1/unused"
        }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("--confirm-schema-release is required");
    }

    @Test
    void shouldRejectReleaseInputsOutsideProjectRoot() {
        assertThatThrownBy(() -> MigrationReleaseApplication.run(new String[] {
                "--confirm-schema-release",
                "--project-root", ".",
                "--manifest", "..\\outside-manifest.json"
        }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Release input path escapes project root");
    }

    @Test
    void shouldRequireManifestToDeclareCatalogContractWhenCatalogIsNotOverridden() throws Exception {
        Path migrationDirectory = tempDir.resolve("sql").resolve("migrations");
        Files.createDirectories(migrationDirectory);
        Path manifest = migrationDirectory.resolve("manifest.json");
        Files.writeString(manifest, "{\"schemaVersion\":\"test\"}");

        assertThatThrownBy(() -> MigrationReleaseApplication.run(new String[] {
                "--confirm-schema-release",
                "--project-root", tempDir.toString()
        }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Migration manifest does not declare catalogContract");
    }

    @Test
    void shouldRejectCatalogOverrideThatDiffersFromManifestContract() throws Exception {
        Path migrationDirectory = tempDir.resolve("sql").resolve("migrations");
        Files.createDirectories(migrationDirectory);
        Path manifest = migrationDirectory.resolve("manifest.json");
        Path declared = tempDir.resolve("declared-catalog.json");
        Path overridden = tempDir.resolve("overridden-catalog.json");
        Files.writeString(manifest, "{\"catalogContract\":\"declared-catalog.json\",\"catalogContractSha256\":\"0000000000000000000000000000000000000000000000000000000000000000\"}");
        Files.writeString(declared, "{}");
        Files.writeString(overridden, "{}");

        assertThatThrownBy(() -> MigrationReleaseApplication.run(new String[] {
                "--confirm-schema-release",
                "--project-root", tempDir.toString(),
                "--catalog", overridden.toString()
        }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Catalog override does not match manifest catalogContract");
    }

    @Test
    void shouldRejectUnknownReleaseArguments() {
        assertThatThrownBy(() -> MigrationReleaseApplication.run(new String[] {
                "--confirm-schema-release",
                "--unknown-option", "value"
        }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unknown release argument: --unknown-option");
    }

    @Test
    void shouldRejectCatalogContentThatDoesNotMatchManifestHash() throws Exception {
        Path migrationDirectory = tempDir.resolve("sql").resolve("migrations");
        Files.createDirectories(migrationDirectory);
        Files.writeString(migrationDirectory.resolve("manifest.json"), """
                {
                  "catalogContract": "catalog.json",
                  "catalogContractSha256": "0000000000000000000000000000000000000000000000000000000000000000"
                }
                """);
        Files.writeString(tempDir.resolve("catalog.json"), """
                {
                  "contractVersion": "1",
                  "schema": "public",
                  "requiredExtensions": [],
                  "allowedExtensions": [],
                  "requiredTables": ["agent"],
                  "allowedTables": ["agent"],
                  "requiredColumns": {"agent": []},
                  "allowedColumns": {"agent": []},
                  "forbiddenColumns": {},
                  "requiredConstraints": [],
                  "allowedConstraints": [],
                  "requiredIndexes": [],
                  "allowedIndexes": [],
                  "requiredDefinitions": [],
                  "requiredFunctions": [],
                  "allowedFunctions": [],
                  "requiredTriggers": [],
                  "allowedTriggers": []
                }
                """);

        assertThatThrownBy(() -> MigrationReleaseApplication.run(new String[] {
                "--confirm-schema-release",
                "--project-root", tempDir.toString()
        }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Catalog contract hash does not match manifest");
    }

    private Class<?> load(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }
}
