package com.kama.jchatmind.migration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public final class SchemaMigrationReleaseEntry {

    public enum ReleaseStatus {
        SUCCEEDED,
        FAILED
    }

    @FunctionalInterface
    public interface MigrationRunner {

        SchemaMigrationExecutor.MigrationRunResult migrate();
    }

    @FunctionalInterface
    public interface CatalogVerifier {

        MigrationCatalogVerifier.VerificationResult verify();
    }

    @FunctionalInterface
    public interface LockRunner {

        <T> T withMigrationLock(Supplier<T> operation);
    }

    public record ReleaseResult(
            ReleaseStatus status,
            SchemaMigrationExecutor.MigrationRunResult migration,
            MigrationCatalogVerifier.VerificationResult catalog,
            String errorType
    ) {
    }

    public record ReleaseMetadata(
            String manifestSha256,
            String approvedBaselineSha256,
            List<String> approvedPrerequisites,
            String releaseId,
            String codeRevision,
            String rollbackState,
            String knownLimitations
    ) {

        public ReleaseMetadata {
            requireSha256(manifestSha256, "manifestSha256");
            requireSha256(approvedBaselineSha256, "approvedBaselineSha256");
            approvedPrerequisites = List.copyOf(approvedPrerequisites);
            requireText(releaseId, "releaseId");
            requireText(codeRevision, "codeRevision");
            requireText(rollbackState, "rollbackState");
            requireText(knownLimitations, "knownLimitations");
        }

        private static void requireSha256(String value, String field) {
            if (value == null || !value.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(field + " must be a lowercase SHA-256");
            }
        }

        private static void requireText(String value, String field) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(field + " must not be blank");
            }
        }
    }

    private final MigrationRunner migrationRunner;
    private final CatalogVerifier catalogVerifier;
    private final LockRunner lockRunner;
    private final ReleaseMetadata metadata;
    private final Path reportPath;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SchemaMigrationReleaseEntry(
            MigrationRunner migrationRunner,
            CatalogVerifier catalogVerifier,
            Path reportPath
    ) {
        this(migrationRunner, catalogVerifier, new LockRunner() {
            @Override
            public <T> T withMigrationLock(Supplier<T> operation) {
                return operation.get();
            }
        }, null, reportPath);
    }

    public SchemaMigrationReleaseEntry(
            MigrationRunner migrationRunner,
            CatalogVerifier catalogVerifier,
            ReleaseMetadata metadata,
            Path reportPath
    ) {
        this(migrationRunner, catalogVerifier, new LockRunner() {
            @Override
            public <T> T withMigrationLock(Supplier<T> operation) {
                return operation.get();
            }
        }, metadata, reportPath);
    }

    public SchemaMigrationReleaseEntry(
            MigrationRunner migrationRunner,
            CatalogVerifier catalogVerifier,
            LockRunner lockRunner,
            Path reportPath
    ) {
        this(migrationRunner, catalogVerifier, lockRunner, null, reportPath);
    }

    public SchemaMigrationReleaseEntry(
            MigrationRunner migrationRunner,
            CatalogVerifier catalogVerifier,
            LockRunner lockRunner,
            ReleaseMetadata metadata,
            Path reportPath
    ) {
        this.migrationRunner = Objects.requireNonNull(migrationRunner, "migrationRunner");
        this.catalogVerifier = Objects.requireNonNull(catalogVerifier, "catalogVerifier");
        this.lockRunner = Objects.requireNonNull(lockRunner, "lockRunner");
        this.metadata = metadata;
        this.reportPath = Objects.requireNonNull(reportPath, "reportPath").toAbsolutePath().normalize();
    }

    public ReleaseResult run() {
        return lockRunner.withMigrationLock(this::runLocked);
    }

    private ReleaseResult runLocked() {
        SchemaMigrationExecutor.MigrationRunResult migration = null;
        MigrationCatalogVerifier.VerificationResult catalog = null;
        try {
            migration = migrationRunner.migrate();
            if (migration == null) {
                throw new IllegalStateException("Migration runner returned null");
            }
            catalog = catalogVerifier.verify();
            if (catalog == null) {
                throw new IllegalStateException("Catalog verifier returned null");
            }
            if (!catalog.verified()) {
                throw new IllegalStateException(
                        "Migration catalog verification failed: missing=" + catalog.missingObjects().size()
                                + ", forbidden=" + catalog.forbiddenObjects().size()
                                + ", unexpected=" + catalog.unexpectedObjects().size()
                                + ", definitionMismatches=" + catalog.definitionMismatches().size()
                );
            }
            ReleaseResult result = new ReleaseResult(ReleaseStatus.SUCCEEDED, migration, catalog, null);
            writeReport(result);
            return result;
        } catch (RuntimeException failure) {
            ReleaseResult result = new ReleaseResult(
                    ReleaseStatus.FAILED,
                    migration,
                    catalog,
                    failure.getClass().getName()
            );
            try {
                writeReport(result);
            } catch (RuntimeException reportFailure) {
                failure.addSuppressed(reportFailure);
            }
            throw failure;
        }
    }

    private void writeReport(ReleaseResult result) {
        ObjectNode report = objectMapper.createObjectNode();
        report.put("reportVersion", "1");
        report.put("status", result.status().name());
        report.put("generatedAtUtc", Instant.now().toString());
        if (metadata != null) {
            report.put("manifestSha256", metadata.manifestSha256());
            report.put("approvedBaselineSha256", metadata.approvedBaselineSha256());
            addStrings(report.putArray("approvedPrerequisites"), metadata.approvedPrerequisites());
            report.put("releaseId", metadata.releaseId());
            report.put("codeRevision", metadata.codeRevision());
            report.put("rollbackState", metadata.rollbackState());
            report.put("knownLimitations", metadata.knownLimitations());
        }
        if (result.migration() != null) {
            report.put("cleanInstall", result.migration().cleanInstall());
            report.put("schemaVersion", result.migration().schemaVersion());
            ArrayNode applied = report.putArray("appliedMigrationIds");
            result.migration().appliedMigrationIds().forEach(applied::add);
        }
        if (result.catalog() != null) {
            report.put("catalogVerified", result.catalog().verified());
            report.put("catalogContractSha256", result.catalog().contractSha256());
            report.put("catalogFingerprint", result.catalog().observedFingerprint());
            addStrings(report.putArray("catalogMissingObjects"), result.catalog().missingObjects());
            addStrings(report.putArray("catalogForbiddenObjects"), result.catalog().forbiddenObjects());
            addStrings(report.putArray("catalogUnexpectedObjects"), result.catalog().unexpectedObjects());
            addStrings(report.putArray("catalogDefinitionMismatches"), result.catalog().definitionMismatches());
        }
        if (result.errorType() != null) {
            report.put("errorType", result.errorType());
        }

        try {
            Path parent = reportPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), report);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot write migration release report", e);
        }
    }

    private void addStrings(ArrayNode target, List<String> values) {
        values.forEach(target::add);
    }
}
