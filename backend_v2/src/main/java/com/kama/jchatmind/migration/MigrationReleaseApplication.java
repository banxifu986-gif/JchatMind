package com.kama.jchatmind.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class MigrationReleaseApplication {

    private record ManifestCatalogReference(String path, String sha256) {
    }

    private MigrationReleaseApplication() {
    }

    public static void main(String[] args) {
        try {
            run(args);
        } catch (RuntimeException failure) {
            System.err.println("迁移发布失败: " + failure.getClass().getSimpleName());
            System.exit(1);
        }
    }

    static SchemaMigrationReleaseEntry.ReleaseResult run(String[] args) {
        Arguments options = Arguments.parse(args);
        if (!options.flags.contains("confirm-schema-release")) {
            throw new IllegalArgumentException("--confirm-schema-release is required");
        }

        Path projectRoot = Path.of(options.valueOrDefault("project-root", "."))
                .toAbsolutePath().normalize();
        Path canonicalManifestPath = projectRoot.resolve("sql/migrations/manifest.json").normalize();
        Path manifestPath = options.values.containsKey("manifest")
                ? projectPath(projectRoot, options.requiredValue("manifest"))
                : canonicalManifestPath;
        if (!manifestPath.equals(canonicalManifestPath)) {
            throw new IllegalArgumentException("Release manifest override is not allowed");
        }
        ManifestCatalogReference catalogReference = manifestCatalogContractPath(manifestPath);
        Path declaredCatalogPath = projectPath(projectRoot, catalogReference.path());
        Path catalogPath = options.values.containsKey("catalog")
                ? projectPath(projectRoot, options.requiredValue("catalog"))
                : declaredCatalogPath;
        if (!catalogPath.equals(declaredCatalogPath)) {
            throw new IllegalArgumentException("Catalog override does not match manifest catalogContract");
        }
        MigrationCatalogContract catalogContract = MigrationCatalogContract.load(catalogPath);
        if (!catalogContract.sha256().equals(catalogReference.sha256())) {
            throw new IllegalArgumentException("Catalog contract hash does not match manifest");
        }
        Path baselinePath = Path.of(options.requiredValue("baseline"))
                .toAbsolutePath().normalize();
        Path reportPath = Path.of(options.valueOrDefault(
                "release-record", "target/migration-release/release.json"
        )).toAbsolutePath().normalize();
        String approvedManifestSha256 = options.requiredValue("manifest-sha256");
        String actualManifestSha256 = sha256(manifestPath);
        if (!actualManifestSha256.equals(approvedManifestSha256)) {
            throw new IllegalArgumentException("Approved manifest hash does not match manifest");
        }
        String baselineSha256 = options.requiredValue("baseline-sha256");
        List<String> approvedPrerequisites = List.copyOf(options.values.getOrDefault("approve", List.of()));
        long lockTimeoutMillis = options.longValueOrDefault("lock-timeout-ms", 30_000L);

        String passwordEnvironmentVariable = options.requiredValue("password-env");
        String password = System.getenv(passwordEnvironmentVariable);
        if (password == null) {
            throw new IllegalArgumentException("Password environment variable is missing");
        }

        DataSource dataSource = dataSource(
                options.requiredValue("jdbc-url"),
                options.requiredValue("username"),
                password
        );
        JdbcMigrationStore migrationStore = new JdbcMigrationStore(
                dataSource,
                Duration.ofMillis(lockTimeoutMillis)
        );
        SchemaMigrationExecutor executor = new SchemaMigrationExecutor(
                projectRoot,
                manifestPath,
                baselinePath,
                baselineSha256,
                migrationStore,
                Set.copyOf(approvedPrerequisites)
        );
        JdbcMigrationCatalogVerifier catalogVerifier = new JdbcMigrationCatalogVerifier(
                migrationStore,
                catalogContract
        );
        return new SchemaMigrationReleaseEntry(
                executor::migrate,
                catalogVerifier::verify,
                migrationStore::withMigrationLock,
                new SchemaMigrationReleaseEntry.ReleaseMetadata(
                        approvedManifestSha256,
                        baselineSha256,
                        approvedPrerequisites,
                        options.valueOrDefault("release-id", "unspecified"),
                        options.valueOrDefault("code-revision", "unspecified"),
                        "manual_restore_and_rebuild_required",
                        "maintenance-window execution only; no automatic application-start migration"
                ),
                reportPath
        ).run();
    }

    private static DataSource dataSource(String url, String username, String password) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(url);
        dataSource.setUsername(username);
        dataSource.setPassword(password);
        return dataSource;
    }

    private static Path projectPath(Path projectRoot, String pathValue) {
        Path path = Path.of(pathValue);
        Path resolved = path.isAbsolute() ? path.normalize() : projectRoot.resolve(path).normalize();
        if (!resolved.startsWith(projectRoot)) {
            throw new IllegalArgumentException("Release input path escapes project root");
        }
        return resolved;
    }

    private static String sha256(Path path) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
            );
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot hash release input", e);
        }
    }

    private static ManifestCatalogReference manifestCatalogContractPath(Path manifestPath) {
        try {
            JsonNode root = new ObjectMapper().readTree(Files.readString(manifestPath));
            JsonNode catalogContract = root == null ? null : root.get("catalogContract");
            if (catalogContract == null || !catalogContract.isTextual() || catalogContract.asText().isBlank()) {
                throw new IllegalArgumentException("Migration manifest does not declare catalogContract");
            }
            JsonNode catalogContractSha256 = root.get("catalogContractSha256");
            if (catalogContractSha256 == null
                    || !catalogContractSha256.isTextual()
                    || !catalogContractSha256.asText().matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Migration manifest does not declare catalogContractSha256");
            }
            return new ManifestCatalogReference(catalogContract.asText(), catalogContractSha256.asText());
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot read migration manifest", e);
        }
    }

    private static final class Arguments {

        private static final Set<String> VALUE_OPTIONS = Set.of(
                "project-root",
                "manifest",
                "catalog",
                "baseline",
                "baseline-sha256",
                "manifest-sha256",
                "jdbc-url",
                "username",
                "password-env",
                "lock-timeout-ms",
                "release-record",
                "release-id",
                "code-revision",
                "approve"
        );

        private final Map<String, List<String>> values = new HashMap<>();
        private final Set<String> flags = new HashSet<>();

        private static Arguments parse(String[] args) {
            Arguments parsed = new Arguments();
            for (int index = 0; index < args.length; index++) {
                String argument = args[index];
                if (!argument.startsWith("--") || argument.length() == 2) {
                    throw new IllegalArgumentException("Invalid release argument");
                }
                String name = argument.substring(2);
                if ("confirm-schema-release".equals(name)) {
                    parsed.flags.add(name);
                    continue;
                }
                if (!VALUE_OPTIONS.contains(name)) {
                    throw new IllegalArgumentException("Unknown release argument: --" + name);
                }
                if (++index >= args.length || args[index].startsWith("--")) {
                    throw new IllegalArgumentException("Release argument requires a value: --" + name);
                }
                parsed.values.computeIfAbsent(name, ignored -> new ArrayList<>()).add(args[index]);
            }
            return parsed;
        }

        private String requiredValue(String name) {
            List<String> entries = values.get(name);
            if (entries == null || entries.size() != 1 || entries.get(0).isBlank()) {
                throw new IllegalArgumentException("Release argument is required: --" + name);
            }
            return entries.get(0);
        }

        private String valueOrDefault(String name, String defaultValue) {
            List<String> entries = values.get(name);
            if (entries == null) {
                return defaultValue;
            }
            if (entries.size() != 1 || entries.get(0).isBlank()) {
                throw new IllegalArgumentException("Release argument must have one value: --" + name);
            }
            return entries.get(0);
        }

        private long longValueOrDefault(String name, long defaultValue) {
            String value = valueOrDefault(name, Long.toString(defaultValue));
            try {
                long parsed = Long.parseLong(value);
                if (parsed < 0) {
                    throw new IllegalArgumentException("Release argument must not be negative: --" + name);
                }
                return parsed;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Release argument must be an integer: --" + name, e);
            }
        }
    }
}
