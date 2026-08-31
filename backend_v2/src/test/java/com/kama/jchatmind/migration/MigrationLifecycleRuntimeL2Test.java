package com.kama.jchatmind.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfSystemProperty(named = "migration.lifecycle.l2", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MigrationLifecycleRuntimeL2Test {

    private static final String IMAGE = "jchatmind-postgres-vchord-bm25:l2";
    private static final String CONTAINER = "jchatmind-migration-l2-"
            + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private static final String ADMIN_DATABASE = "postgres";
    private static final Path PROJECT_ROOT = Path.of("..").toAbsolutePath().normalize();
    private static final Path MANIFEST = PROJECT_ROOT.resolve("sql/migrations/manifest.json");
    private static Path baselinePath;
    private static String baselineSha256;
    private static int hostPort;
    private static boolean containerStarted;
    private static String executionResult = "not_completed";

    @BeforeAll
    static void setUpRuntime() throws Exception {
        hostPort = findFreePort();
        baselinePath = Files.createTempFile("jchatmind-approved-baseline-", ".sql");
        Files.writeString(baselinePath, BASELINE_SQL, StandardCharsets.UTF_8);
        baselineSha256 = sha256(Files.readAllBytes(baselinePath));

        run(List.of(
                "docker", "run", "--rm", "-d", "--name", CONTAINER,
                "-e", "POSTGRES_DB=" + ADMIN_DATABASE,
                "-e", "POSTGRES_USER=postgres",
                "-e", "POSTGRES_HOST_AUTH_METHOD=trust",
                "-p", "127.0.0.1:" + hostPort + ":5432",
                IMAGE
        ));
        containerStarted = true;
        waitForPostgres();
    }

    @AfterAll
    static void tearDownRuntime() throws Exception {
        if (containerStarted) {
            run(List.of("docker", "stop", CONTAINER));
        }
        writeEvidenceReport();
        if (baselinePath != null) {
            Files.deleteIfExists(baselinePath);
        }
    }

    private static void writeEvidenceReport() {
        try {
            Path report = Path.of("target", "migration-l2", "migration-lifecycle-l2.json");
            Files.createDirectories(report.getParent());
            new ObjectMapper().writeValue(report.toFile(), Map.of(
                    "executionMode", "isolated_postgresql_runtime",
                    "testClass", MigrationLifecycleRuntimeL2Test.class.getName(),
                    "manifestPath", "sql/migrations/manifest.json",
                    "schemaVersion", "2026-08-30",
                    "migrationCount", 16,
                    "containerImage", IMAGE,
                    "containerName", CONTAINER,
                    "result", executionResult,
                    "databaseIsolation", "random_database_per_test",
                    "productionDatabaseTouched", false
            ));
        } catch (Exception exception) {
            throw new AssertionError("无法写入迁移 L2 证据报告", exception);
        }
    }

    @Test
    @Order(1)
    void shouldInstallAllManifestMigrationsAndVerifyLedgerCatalog() throws Exception {
        String database = createDatabase();
        try {
            DataSource dataSource = dataSource(database);
            SchemaMigrationExecutor.MigrationRunResult result = runner(dataSource, MANIFEST).migrate();

            assertThat(result.cleanInstall()).isTrue();
            assertThat(result.schemaVersion()).isEqualTo("2026-08-30");
            assertThat(result.appliedMigrationIds()).hasSize(16);
            assertThat(queryForLong(dataSource,
                    "SELECT COUNT(*) FROM public.jchatmind_schema_migration_ledger")).isEqualTo(17);
            assertThat(queryForLong(dataSource,
                    "SELECT COUNT(*) FROM public.jchatmind_schema_migration_ledger WHERE status = 'APPLIED'"))
                    .isEqualTo(17);
            assertThat(queryForLong(dataSource,
                    "SELECT COUNT(*) FROM pg_catalog.pg_constraint "
                            + "WHERE conrelid = 'public.jchatmind_schema_migration_ledger'::regclass "
                            + "AND contype = 'c'"))
                    .isEqualTo(2);
            assertThat(queryForLong(dataSource,
                    "SELECT COUNT(*) FROM pg_catalog.pg_class c "
                            + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
                            + "WHERE n.nspname = 'public' AND c.relname IN "
                            + "('agent_knowledge_base', 'document_asset', 'document_asset_chunk', "
                            + "'knowledge_base_deletion_task', 'knowledge_base_deletion_audit')"))
                    .isEqualTo(5);
            executionResult = "passed";
        } finally {
            dropDatabase(database);
        }
    }

    @Test
    @Order(2)
    void shouldUpgradeFromManifestPrefixAndSkipVerifiedReplay() throws Exception {
        String database = createDatabase();
        Path partialManifest = Files.createTempFile("jchatmind-partial-manifest-", ".json");
        try {
            DataSource dataSource = dataSource(database);
            ObjectMapper objectMapper = new ObjectMapper();
            ObjectNode partial = (ObjectNode) objectMapper.readTree(Files.readString(MANIFEST, StandardCharsets.UTF_8));
            JsonNode migrations = partial.get("migrations");
            var partialMigrations = objectMapper.createArrayNode();
            for (int index = 0; index < 8; index++) {
                partialMigrations.add(migrations.get(index));
            }
            partial.set("migrations", partialMigrations);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(partialManifest.toFile(), partial);

            SchemaMigrationExecutor.MigrationRunResult prefix = runner(dataSource, partialManifest).migrate();
            SchemaMigrationExecutor.MigrationRunResult upgrade = runner(dataSource, MANIFEST).migrate();
            SchemaMigrationExecutor.MigrationRunResult replay = runner(dataSource, MANIFEST).migrate();

            assertThat(prefix.cleanInstall()).isTrue();
            assertThat(prefix.appliedMigrationIds()).hasSize(8);
            assertThat(upgrade.cleanInstall()).isFalse();
            assertThat(upgrade.appliedMigrationIds()).hasSize(8);
            assertThat(replay.appliedMigrationIds()).isEmpty();
            assertThat(queryForLong(dataSource,
                    "SELECT COUNT(*) FROM public.jchatmind_schema_migration_ledger"))
                    .isEqualTo(17);
            executionResult = "passed";
        } finally {
            dropDatabase(database);
            Files.deleteIfExists(partialManifest);
        }
    }

    @Test
    @Order(3)
    void shouldPreserveRunningLedgerWhenMigrationFailsAndRejectReplay() throws Exception {
        String database = createDatabase();
        Path brokenSql = Files.createTempFile(PROJECT_ROOT.resolve("sql/migrations"), "migration-l2-broken-", ".sql");
        Path brokenManifest = Files.createTempFile("jchatmind-broken-manifest-", ".json");
        try {
            Files.writeString(brokenSql, "BROKEN SQL", StandardCharsets.UTF_8);
            ObjectMapper objectMapper = new ObjectMapper();
            ObjectNode manifest = (ObjectNode) objectMapper.readTree(Files.readString(MANIFEST, StandardCharsets.UTF_8));
            JsonNode migrations = manifest.withArray("migrations");
            ObjectNode lastMigration = (ObjectNode) migrations.get(migrations.size() - 1);
            String relativeBrokenPath = PROJECT_ROOT.relativize(brokenSql).toString().replace('\\', '/');
            lastMigration.put("path", relativeBrokenPath);
            lastMigration.put("sha256", sha256(Files.readAllBytes(brokenSql)));
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(brokenManifest.toFile(), manifest);

            DataSource dataSource = dataSource(database);
            SchemaMigrationExecutor brokenRunner = runner(dataSource, brokenManifest);

            assertThatThrownBy(brokenRunner::migrate)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Migration script failed");
            assertThat(queryForLong(dataSource,
                    "SELECT COUNT(*) FROM public.jchatmind_schema_migration_ledger WHERE status = 'RUNNING'"))
                    .isEqualTo(1);
            assertThatThrownBy(() -> runner(dataSource, brokenManifest).migrate())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("RUNNING");
            executionResult = "passed";
        } finally {
            dropDatabase(database);
            Files.deleteIfExists(brokenManifest);
            Files.deleteIfExists(brokenSql);
        }
    }

    private static SchemaMigrationExecutor runner(DataSource dataSource, Path manifest) {
        return new SchemaMigrationExecutor(
                PROJECT_ROOT,
                manifest,
                baselinePath,
                baselineSha256,
                new JdbcMigrationStore(dataSource),
                Set.of("manual.owner-review")
        );
    }

    private static DataSource dataSource(String database) {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl("jdbc:postgresql://127.0.0.1:" + hostPort + "/" + database);
        dataSource.setUsername("postgres");
        dataSource.setPassword("");
        return dataSource;
    }

    private static String createDatabase() throws SQLException {
        String database = "migration_l2_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        try (Connection connection = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:" + hostPort + "/" + ADMIN_DATABASE,
                "postgres",
                ""
        ); Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
        }
        return database;
    }

    private static void dropDatabase(String database) throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:" + hostPort + "/" + ADMIN_DATABASE,
                "postgres",
                ""
        ); Statement statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + database);
        }
    }

    private static long queryForLong(DataSource dataSource, String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             var resultSet = statement.executeQuery(sql)) {
            if (!resultSet.next()) {
                throw new AssertionError("查询未返回结果: " + sql);
            }
            return resultSet.getLong(1);
        }
    }

    private static void waitForPostgres() throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        SQLException lastFailure = null;
        while (System.nanoTime() < deadline) {
            try (Connection ignored = DriverManager.getConnection(
                    "jdbc:postgresql://127.0.0.1:" + hostPort + "/" + ADMIN_DATABASE,
                    "postgres",
                    ""
            )) {
                return;
            } catch (SQLException failure) {
                lastFailure = failure;
                Thread.sleep(500);
            }
        }
        throw new AssertionError("隔离 PostgreSQL 未在 30 秒内就绪", lastFailure);
    }

    private static int findFreePort() throws IOException {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    private static String sha256(byte[] content) throws Exception {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(content)
        );
    }

    private static String run(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try (OutputStream ignored = process.getOutputStream()) {
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new AssertionError("隔离迁移 Docker 命令失败: " + String.join(" ", command) + "\n" + output);
        }
        return output;
    }

    private static final String BASELINE_SQL = """
            CREATE EXTENSION vector;

            CREATE TABLE knowledge_base (
                id UUID PRIMARY KEY,
                name VARCHAR(128) NOT NULL,
                description VARCHAR(255),
                metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );

            CREATE TABLE agent (
                id UUID PRIMARY KEY,
                user_id BIGINT NOT NULL,
                name VARCHAR(128) NOT NULL,
                description VARCHAR(255),
                system_prompt TEXT,
                model VARCHAR(128) NOT NULL,
                allowed_tools JSONB NOT NULL DEFAULT '[]'::jsonb,
                allowed_kbs JSONB NOT NULL DEFAULT '[]'::jsonb,
                chat_options JSONB NOT NULL DEFAULT '{}'::jsonb,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );

            CREATE TABLE document (
                id UUID PRIMARY KEY,
                kb_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
                filename VARCHAR(255) NOT NULL,
                filetype VARCHAR(32),
                size BIGINT,
                metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );

            CREATE TABLE chunk_bge_m3 (
                id UUID PRIMARY KEY,
                kb_id UUID,
                doc_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE,
                content TEXT,
                metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
                embedding vector(3),
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );

            CREATE TABLE user_memory (
                id UUID PRIMARY KEY,
                user_id VARCHAR(64) NOT NULL,
                session_id UUID,
                memory_type VARCHAR(32),
                content TEXT NOT NULL,
                embedding vector(3),
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );

            CREATE TABLE user_memory_candidate (
                id UUID PRIMARY KEY,
                user_id VARCHAR(64) NOT NULL,
                session_id UUID,
                memory_type VARCHAR(32),
                content TEXT NOT NULL,
                evidence TEXT,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );
            """;
}
