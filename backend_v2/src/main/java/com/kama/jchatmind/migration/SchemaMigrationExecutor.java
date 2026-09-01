package com.kama.jchatmind.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

public final class SchemaMigrationExecutor {

    public enum MigrationStatus {
        RUNNING,
        APPLIED
    }

    public record Migration(
            String id,
            int order,
            String path,
            String sha256,
            boolean transactional,
            List<String> requires
    ) {

        public Migration {
            requires = List.copyOf(requires);
        }
    }

    public record LedgerEntry(
            String id,
            int order,
            String sha256,
            MigrationStatus status
    ) {
    }

    public record MigrationState(
            boolean ledgerPresent,
            String baselineSha256,
            List<LedgerEntry> entries
    ) {

        public MigrationState {
            entries = List.copyOf(entries);
        }

        public static MigrationState empty() {
            return new MigrationState(false, null, List.of());
        }

        public static MigrationState baselineInstalled(String baselineSha256, List<LedgerEntry> entries) {
            return new MigrationState(true, baselineSha256, entries);
        }

        public MigrationState withRunning(Migration migration) {
            return withEntry(new LedgerEntry(
                    migration.id(), migration.order(), migration.sha256(), MigrationStatus.RUNNING
            ));
        }

        public MigrationState withApplied(Migration migration) {
            return withEntry(new LedgerEntry(
                    migration.id(), migration.order(), migration.sha256(), MigrationStatus.APPLIED
            ));
        }

        private MigrationState withEntry(LedgerEntry replacement) {
            List<LedgerEntry> next = new ArrayList<>(entries);
            for (int index = 0; index < next.size(); index++) {
                if (next.get(index).id().equals(replacement.id())) {
                    next.set(index, replacement);
                    return new MigrationState(true, baselineSha256, next);
                }
            }
            next.add(replacement);
            return new MigrationState(true, baselineSha256, next);
        }
    }

    public record MigrationRunResult(
            boolean cleanInstall,
            String schemaVersion,
            List<String> appliedMigrationIds
    ) {

        public MigrationRunResult {
            appliedMigrationIds = List.copyOf(appliedMigrationIds);
        }
    }

    public interface MigrationStore {

        MigrationState readState();

        void installBaseline(String sql, String baselineSha256);

        void apply(Migration migration, String sql);

        default <T> T withMigrationLock(Supplier<T> operation) {
            return operation.get();
        }
    }

    private record ManifestBundle(String schemaVersion, List<Migration> migrations) {
    }

    private record VerifiedBaseline(String sql, String sha256) {
    }

    private final Path projectRoot;
    private final Path manifestPath;
    private final Path approvedBaselinePath;
    private final String approvedBaselineSha256;
    private final MigrationStore store;
    private final Set<String> approvedPrerequisites;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SchemaMigrationExecutor(
            Path projectRoot,
            Path manifestPath,
            Path approvedBaselinePath,
            String approvedBaselineSha256,
            MigrationStore store,
            Set<String> approvedPrerequisites
    ) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.manifestPath = manifestPath.toAbsolutePath().normalize();
        this.approvedBaselinePath = approvedBaselinePath == null
                ? null
                : approvedBaselinePath.toAbsolutePath().normalize();
        this.approvedBaselineSha256 = approvedBaselineSha256;
        this.store = Objects.requireNonNull(store, "store");
        this.approvedPrerequisites = Set.copyOf(Objects.requireNonNull(approvedPrerequisites, "approvedPrerequisites"));
        if (this.approvedPrerequisites.stream().anyMatch(prerequisite -> !prerequisite.startsWith("manual."))) {
            throw new IllegalArgumentException("approvedPrerequisites may only contain manual.* values");
        }
    }

    public MigrationRunResult migrate() {
        ManifestBundle manifest = readManifest();
        return store.withMigrationLock(() -> migrate(manifest));
    }

    private MigrationRunResult migrate(ManifestBundle manifest) {
        MigrationState state = store.readState();
        validateState(state, manifest.migrations(), approvedPrerequisites);

        boolean cleanInstall = !state.ledgerPresent();
        if (cleanInstall) {
            VerifiedBaseline baseline = verifyApprovedBaseline();
            store.installBaseline(baseline.sql(), baseline.sha256());
            state = MigrationState.baselineInstalled(baseline.sha256(), state.entries());
        } else {
            if (approvedBaselineSha256 == null || !approvedBaselineSha256.matches("[0-9a-f]{64}")) {
                throw new IllegalStateException("An approved baseline hash is required for upgrade");
            }
            VerifiedBaseline baseline = verifyApprovedBaseline();
            if (!baseline.sha256().equals(state.baselineSha256())) {
                throw new IllegalStateException("Approved baseline hash does not match migration ledger");
            }
        }

        Map<String, LedgerEntry> applied = new HashMap<>();
        for (LedgerEntry entry : state.entries()) {
            applied.put(entry.id(), entry);
        }

        List<String> newlyApplied = new ArrayList<>();
        for (Migration migration : manifest.migrations()) {
            LedgerEntry existing = applied.get(migration.id());
            if (existing != null) {
                continue;
            }
            verifyRequirements(migration, applied.keySet());
            store.apply(migration, readVerifiedSql(migration));
            applied.put(migration.id(), new LedgerEntry(
                    migration.id(), migration.order(), migration.sha256(), MigrationStatus.APPLIED
            ));
            newlyApplied.add(migration.id());
        }

        return new MigrationRunResult(cleanInstall, manifest.schemaVersion(), newlyApplied);
    }

    private ManifestBundle readManifest() {
        if (!Files.isRegularFile(manifestPath)) {
            throw new IllegalStateException("Migration manifest is missing: " + manifestPath);
        }

        try {
            JsonNode root = objectMapper.readTree(readUtf8(manifestPath, "migration manifest"));
            JsonNode manifestVersion = root == null ? null : root.get("manifestVersion");
            JsonNode baseline = root == null ? null : root.get("baseline");
            if (root == null
                    || !root.isObject()
                    || manifestVersion == null
                    || !manifestVersion.isTextual()
                    || baseline == null
                    || !baseline.isObject()
                    || !baseline.path("required").isBoolean()
                    || !baseline.path("failClosedOnUnknownState").isBoolean()
                    || !"1".equals(manifestVersion.asText())
                    || !baseline.path("required").asBoolean()
                    || !baseline.path("failClosedOnUnknownState").asBoolean()) {
                throw new IllegalStateException("Migration manifest does not declare a fail-closed baseline contract");
            }

            JsonNode schemaVersionNode = root.get("schemaVersion");
            String schemaVersion = schemaVersionNode == null ? "" : schemaVersionNode.asText();
            JsonNode migrationsNode = root.path("migrations");
            if (schemaVersion.isBlank()
                    || schemaVersionNode == null
                    || !schemaVersionNode.isTextual()
                    || !migrationsNode.isArray()
                    || migrationsNode.isEmpty()) {
                throw new IllegalStateException("Migration manifest is incomplete");
            }

            List<Migration> migrations = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            Set<String> paths = new HashSet<>();
            int previousOrder = 0;
            for (JsonNode node : migrationsNode) {
                if (!node.isObject()
                        || !node.path("id").isTextual()
                        || !node.path("order").isIntegralNumber()
                        || !node.path("path").isTextual()
                        || !node.path("sha256").isTextual()) {
                    throw new IllegalStateException("Migration manifest contains an invalid entry");
                }
                Migration migration = new Migration(
                        node.path("id").asText(),
                        node.path("order").asInt(),
                        node.path("path").asText(),
                        node.path("sha256").asText(),
                        readTransactional(node),
                        readStringList(node.path("requires"))
                );
                if (migration.id().isBlank()
                        || migration.order() <= previousOrder
                        || !ids.add(migration.id())
                        || !paths.add(migration.path())
                        || !migration.sha256().matches("[0-9a-f]{64}")) {
                    throw new IllegalStateException("Migration manifest contains an invalid entry");
                }
                Path migrationPath = resolveProjectPath(migration.path());
                if (!Files.isRegularFile(migrationPath) || !sha256(migrationPath).equals(migration.sha256())) {
                    throw new IllegalStateException("Migration hash verification failed: " + migration.path());
                }
                previousOrder = migration.order();
                migrations.add(migration);
            }

            Set<String> knownIds = Set.copyOf(ids);
            Map<String, Integer> orderById = new HashMap<>();
            for (Migration migration : migrations) {
                orderById.put(migration.id(), migration.order());
            }
            for (Migration migration : migrations) {
                for (String requirement : migration.requires()) {
                    if (!requirement.startsWith("baseline.")
                            && !requirement.startsWith("manual.")
                            && !knownIds.contains(requirement)) {
                        throw new IllegalStateException("Migration dependency is unknown: " + requirement);
                    }
                    if (knownIds.contains(requirement)
                            && orderById.get(requirement) >= migration.order()) {
                        throw new IllegalStateException("Migration dependency must precede: " + migration.id());
                    }
                }
            }
            return new ManifestBundle(schemaVersion, List.copyOf(migrations));
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read migration manifest", e);
        }
    }

    private void validateState(
            MigrationState state,
            List<Migration> migrations,
            Set<String> approvedPrerequisites
    ) {
        if (state == null || state.entries() == null) {
            throw new IllegalStateException("Migration ledger state is unknown");
        }
        if (!state.ledgerPresent()) {
            if (state.baselineSha256() != null || !state.entries().isEmpty()) {
                throw new IllegalStateException("Migration ledger state is inconsistent");
            }
            return;
        }
        if (state.baselineSha256() == null || !state.baselineSha256().matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("Migration ledger has no approved baseline");
        }

        Map<String, Migration> expected = new HashMap<>();
        for (Migration migration : migrations) {
            expected.put(migration.id(), migration);
        }

        Set<String> seen = new HashSet<>();
        int previousOrder = 0;
        for (int index = 0; index < state.entries().size(); index++) {
            LedgerEntry entry = state.entries().get(index);
            Migration migration = expected.get(entry.id());
            if (migration == null || seen.contains(entry.id())) {
                throw new IllegalStateException("Migration ledger contains an unknown or duplicate entry");
            }
            if (!migration.id().equals(migrations.get(index).id())) {
                throw new IllegalStateException("Migration ledger has a missing or out-of-order entry");
            }
            if (entry.order() != migration.order()
                    || entry.sha256() == null
                    || !entry.sha256().equals(migration.sha256())) {
                throw new IllegalStateException("Migration ledger hash or order does not match manifest: " + entry.id());
            }
            if (entry.order() <= previousOrder) {
                throw new IllegalStateException("Migration ledger order is not strictly increasing");
            }
            if (entry.status() == MigrationStatus.RUNNING) {
                throw new IllegalStateException("Migration ledger contains RUNNING entry: " + entry.id());
            }
            if (entry.status() != MigrationStatus.APPLIED) {
                throw new IllegalStateException("Migration ledger contains an unknown status: " + entry.id());
            }
            verifyAppliedRequirements(migration, seen, approvedPrerequisites);
            seen.add(entry.id());
            previousOrder = entry.order();
        }
    }

    private void verifyRequirements(Migration migration, Set<String> appliedIds) {
        for (String requirement : migration.requires()) {
            if (!requirement.startsWith("baseline.")
                    && !appliedIds.contains(requirement)
                    && !approvedPrerequisites.contains(requirement)) {
                throw new IllegalStateException(
                        "Migration dependency is not applied: " + migration.id() + " requires " + requirement
                );
            }
        }
    }

    private void verifyAppliedRequirements(
            Migration migration,
            Set<String> appliedIds,
            Set<String> approvedPrerequisites
    ) {
        for (String requirement : migration.requires()) {
            if (requirement.startsWith("manual.") && !approvedPrerequisites.contains(requirement)) {
                throw new IllegalStateException(
                        "Manual migration prerequisite is not approved: " + requirement
                );
            }
            if (!requirement.startsWith("baseline.")
                    && !requirement.startsWith("manual.")
                    && !appliedIds.contains(requirement)) {
                throw new IllegalStateException("Migration dependency is not applied: " + migration.id());
            }
        }
    }

    private VerifiedBaseline verifyApprovedBaseline() {
        if (approvedBaselinePath == null
                || approvedBaselineSha256 == null
                || !approvedBaselineSha256.matches("[0-9a-f]{64}")
                || !Files.isRegularFile(approvedBaselinePath)) {
            throw new IllegalStateException("An approved baseline is required for clean install");
        }
        try {
            byte[] content = Files.readAllBytes(approvedBaselinePath);
            String actualSha256 = sha256(content);
            if (!actualSha256.equals(approvedBaselineSha256)) {
                throw new IllegalStateException("Approved baseline hash verification failed");
            }
            return new VerifiedBaseline(decodeUtf8(content, "approved baseline"), actualSha256);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read approved baseline", e);
        }
    }

    private String readVerifiedSql(Migration migration) {
        Path migrationPath = resolveProjectPath(migration.path());
        try {
            byte[] content = Files.readAllBytes(migrationPath);
            if (!sha256(content).equals(migration.sha256())) {
                throw new IllegalStateException("Migration changed after manifest verification: " + migration.path());
            }
            return decodeUtf8(content, migration.path());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read migration: " + migration.path(), e);
        }
    }

    private Path resolveProjectPath(String relativePath) {
        Path path = Path.of(relativePath);
        Path resolved = projectRoot.resolve(path).normalize();
        if (path.isAbsolute() || !resolved.startsWith(projectRoot)) {
            throw new IllegalStateException("Migration path escapes project root: " + relativePath);
        }
        return resolved;
    }

    private List<String> readStringList(JsonNode node) {
        if (!node.isArray()) {
            throw new IllegalStateException("Migration dependency list is invalid");
        }
        List<String> values = new ArrayList<>();
        node.forEach(value -> {
            if (!value.isTextual()) {
                throw new IllegalStateException("Migration dependency list contains a non-text value");
            }
            String valueText = value.asText();
            if (valueText.isBlank()) {
                throw new IllegalStateException("Migration dependency list contains a blank entry");
            }
            values.add(valueText);
        });
        return values;
    }

    private boolean readTransactional(JsonNode node) {
        if (!node.path("transactional").isBoolean()) {
            throw new IllegalStateException("Migration transactional flag is invalid");
        }
        return node.path("transactional").asBoolean();
    }

    private String sha256(Path path) {
        try {
            return sha256(Files.readAllBytes(path));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot hash migration file: " + path, e);
        }
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception e) {
            throw new IllegalStateException("Cannot hash migration content", e);
        }
    }

    private String readUtf8(Path path, String description) {
        try {
            return decodeUtf8(Files.readAllBytes(path), description);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + description, e);
        }
    }

    private String decodeUtf8(byte[] content, String description) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalStateException("Invalid UTF-8 in " + description, e);
        }
    }
}
