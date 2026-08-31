package com.kama.jchatmind.migration;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaMigrationExecutorTest {

    private static final Path PROJECT_ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path MANIFEST = PROJECT_ROOT.resolve("sql/migrations/manifest.json");

    @Test
    void shouldFailClosedWhenCleanInstallHasNoApprovedBaseline() {
        RecordingStore store = new RecordingStore(SchemaMigrationExecutor.MigrationState.empty());

        assertThatThrownBy(() -> executor(store, null, null).migrate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("approved baseline");
        assertThat(store.operations).isEmpty();
    }

    @Test
    void shouldApplyBaselineAndMigrationsInManifestOrderAndSkipVerifiedReplay() throws Exception {
        Path baseline = Files.createTempFile("jchatmind-approved-baseline-", ".sql");
        Files.writeString(baseline, "CREATE TABLE approved_baseline_marker(id INTEGER);", StandardCharsets.UTF_8);
        String baselineSha256 = sha256(baseline);
        RecordingStore store = new RecordingStore(SchemaMigrationExecutor.MigrationState.empty());

        SchemaMigrationExecutor firstRun = executor(store, baseline, baselineSha256);
        SchemaMigrationExecutor.MigrationRunResult firstResult = firstRun.migrate();
        SchemaMigrationExecutor.MigrationRunResult replayResult = firstRun.migrate();

        assertThat(firstResult.cleanInstall()).isTrue();
        assertThat(firstResult.appliedMigrationIds()).hasSize(16);
        assertThat(firstResult.appliedMigrationIds()).isSortedAccordingTo((left, right) ->
                Integer.compare(store.orders.get(left), store.orders.get(right)));
        assertThat(replayResult.cleanInstall()).isFalse();
        assertThat(replayResult.appliedMigrationIds()).isEmpty();
        assertThat(store.operations).startsWith("baseline");
        assertThat(store.baselineSql).isEqualTo(Files.readString(baseline, StandardCharsets.UTF_8));
        assertThat(store.operations).hasSize(17);
        assertThat(store.lockCalls).isEqualTo(2);
    }

    @Test
    void shouldRejectRunningLedgerBeforeApplyingAnyNewMigration() {
        RecordingStore store = new RecordingStore(new SchemaMigrationExecutor.MigrationState(
                true,
                "a".repeat(64),
                List.of(new SchemaMigrationExecutor.LedgerEntry(
                        "auth.create-user-table",
                        10,
                        "b82f050023e0e9cfdb0d01ca3e10bdc97c83843cc2d9bb95991a8ec5767f84a4",
                        SchemaMigrationExecutor.MigrationStatus.RUNNING
                ))
        ));

        assertThatThrownBy(() -> executor(store, null, null).migrate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RUNNING");
        assertThat(store.operations).isEmpty();
    }

    @Test
    void shouldKeepFailedMigrationOutOfSuccessfulReplay() throws Exception {
        Path baseline = Files.createTempFile("jchatmind-approved-baseline-", ".sql");
        Files.writeString(baseline, "CREATE TABLE approved_baseline_marker(id INTEGER);", StandardCharsets.UTF_8);
        RecordingStore store = new RecordingStore(SchemaMigrationExecutor.MigrationState.empty());
        store.failOnMigrationNumber = 2;

        assertThatThrownBy(() -> executor(store, baseline, sha256(baseline)).migrate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("simulated migration failure");
        assertThat(store.state.entries()).anyMatch(entry ->
                entry.status() == SchemaMigrationExecutor.MigrationStatus.RUNNING);

        store.failOnMigrationNumber = -1;
        assertThatThrownBy(() -> executor(store, baseline, sha256(baseline)).migrate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RUNNING");
    }

    @Test
    void shouldRequireExplicitApprovalForManualMigrationPrerequisite() throws Exception {
        Path baseline = Files.createTempFile("jchatmind-approved-baseline-", ".sql");
        Files.writeString(baseline, "CREATE TABLE approved_baseline_marker(id INTEGER);", StandardCharsets.UTF_8);
        RecordingStore store = new RecordingStore(SchemaMigrationExecutor.MigrationState.empty());

        assertThatThrownBy(() -> executor(store, baseline, sha256(baseline), Set.of()).migrate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("manual.owner-review");
        assertThat(store.operations).contains("baseline");
        assertThat(store.operations).doesNotContain("knowledge-base.enforce-owner-not-null");
    }

    @Test
    void shouldRejectApprovalSetThatTargetsNormalMigrationDependency() {
        RecordingStore store = new RecordingStore(SchemaMigrationExecutor.MigrationState.empty());

        assertThatThrownBy(() -> executor(store, null, null, Set.of("auth.create-user-table")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("manual.");
    }

    @Test
    void shouldRejectFutureMigrationDependencyBeforeInstallingBaseline() throws Exception {
        Path manifest = Files.createTempFile("jchatmind-invalid-manifest-", ".json");
        String original = Files.readString(MANIFEST, StandardCharsets.UTF_8);
        String invalid = original.replace(
                "\"requires\": [\"auth.create-user-table\"]",
                "\"requires\": [\"mcp.create-principal-access\"]"
        );
        Files.writeString(manifest, invalid, StandardCharsets.UTF_8);
        Path baseline = Files.createTempFile("jchatmind-approved-baseline-", ".sql");
        Files.writeString(baseline, "CREATE TABLE approved_baseline_marker(id INTEGER);", StandardCharsets.UTF_8);
        RecordingStore store = new RecordingStore(SchemaMigrationExecutor.MigrationState.empty());

        assertThatThrownBy(() -> new SchemaMigrationExecutor(
                PROJECT_ROOT,
                manifest,
                baseline,
                sha256(baseline),
                store,
                Set.of("manual.owner-review")
        ).migrate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("precede");
        assertThat(store.operations).isEmpty();
    }

    @Test
    void shouldRejectManifestWithWrongJsonFieldTypesBeforeOpeningStore() throws Exception {
        Path manifest = Files.createTempFile("jchatmind-invalid-manifest-", ".json");
        String original = Files.readString(MANIFEST, StandardCharsets.UTF_8);
        Files.writeString(manifest, original.replace(
                "\"schemaVersion\": \"2026-08-30\"",
                "\"schemaVersion\": 20260830"
        ), StandardCharsets.UTF_8);
        RecordingStore store = new RecordingStore(SchemaMigrationExecutor.MigrationState.empty());

        assertThatThrownBy(() -> new SchemaMigrationExecutor(
                PROJECT_ROOT,
                manifest,
                null,
                null,
                store,
                Set.of("manual.owner-review")
        ).migrate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("manifest");
        assertThat(store.lockCalls).isZero();
    }

    @Test
    void shouldRequireApprovedBaselineHashForUpgrade() {
        RecordingStore store = new RecordingStore(SchemaMigrationExecutor.MigrationState.baselineInstalled(
                "a".repeat(64),
                List.of()
        ));

        assertThatThrownBy(() -> new SchemaMigrationExecutor(
                PROJECT_ROOT,
                MANIFEST,
                null,
                null,
                store,
                Set.of("manual.owner-review")
        ).migrate())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("approved baseline hash");
        assertThat(store.operations).isEmpty();
    }

    private SchemaMigrationExecutor executor(
            RecordingStore store,
            Path baseline,
            String baselineSha256
    ) {
        return executor(store, baseline, baselineSha256, Set.of("manual.owner-review"));
    }

    private SchemaMigrationExecutor executor(
            RecordingStore store,
            Path baseline,
            String baselineSha256,
            Set<String> approvedPrerequisites
    ) {
        return new SchemaMigrationExecutor(
                PROJECT_ROOT,
                MANIFEST,
                baseline,
                baselineSha256,
                store,
                approvedPrerequisites
        );
    }

    private String sha256(Path path) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    }

    private static final class RecordingStore implements SchemaMigrationExecutor.MigrationStore {

        private SchemaMigrationExecutor.MigrationState state;
        private final List<String> operations = new ArrayList<>();
        private final java.util.Map<String, Integer> orders = new java.util.HashMap<>();
        private String baselineSql;
        private int lockCalls;
        private int appliedMigrationNumber;
        private int failOnMigrationNumber = -1;

        private RecordingStore(SchemaMigrationExecutor.MigrationState state) {
            this.state = state;
        }

        @Override
        public SchemaMigrationExecutor.MigrationState readState() {
            return state;
        }

        @Override
        public <T> T withMigrationLock(java.util.function.Supplier<T> operation) {
            lockCalls++;
            return operation.get();
        }

        @Override
        public void installBaseline(String sql, String baselineSha256) {
            operations.add("baseline");
            baselineSql = sql;
            state = SchemaMigrationExecutor.MigrationState.baselineInstalled(
                    baselineSha256,
                    state.entries()
            );
        }

        @Override
        public void apply(SchemaMigrationExecutor.Migration migration, String sql) {
            appliedMigrationNumber++;
            orders.put(migration.id(), migration.order());
            state = state.withRunning(migration);
            if (appliedMigrationNumber == failOnMigrationNumber) {
                throw new IllegalStateException("simulated migration failure");
            }
            operations.add(migration.id());
            state = state.withApplied(migration);
        }
    }
}
