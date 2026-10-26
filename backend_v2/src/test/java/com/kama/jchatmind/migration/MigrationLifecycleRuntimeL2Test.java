package com.kama.jchatmind.migration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.mapper.UserMemoryCandidateMapper;
import com.kama.jchatmind.mapper.UserMemoryConsolidationProposalMapper;
import com.kama.jchatmind.mapper.UserMemoryMapper;
import com.kama.jchatmind.model.dto.RagKbIndexRevision;
import com.kama.jchatmind.model.entity.UserMemory;
import com.kama.jchatmind.model.entity.UserMemoryCandidate;
import com.kama.jchatmind.model.entity.UserMemoryConsolidationProposal;
import com.kama.jchatmind.typehandler.PgVectorTypeHandler;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
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
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDateTime;
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
    private static boolean catalogVerified;
    private static String catalogFingerprint = "not_completed";
    private static final Path RELEASE_REPORT = Path.of(
            "target", "migration-l2", "migration-release-l2.json"
    );

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
            new ObjectMapper().writeValue(report.toFile(), Map.ofEntries(
                    Map.entry("executionMode", "isolated_postgresql_runtime"),
                    Map.entry("testClass", MigrationLifecycleRuntimeL2Test.class.getName()),
                    Map.entry("manifestPath", "sql/migrations/manifest.json"),
                    Map.entry("schemaVersion", "2026-10-22-g9"),
                    Map.entry("migrationCount", 23),
                    Map.entry("containerImage", IMAGE),
                    Map.entry("containerName", CONTAINER),
                    Map.entry("result", executionResult),
                    Map.entry("catalogVerified", catalogVerified),
                    Map.entry("catalogFingerprint", catalogFingerprint),
                    Map.entry("releaseReportPath", RELEASE_REPORT.toString()),
                    Map.entry("databaseIsolation", "random_database_per_test"),
                    Map.entry("productionDatabaseTouched", false)
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
            JdbcMigrationStore migrationStore = new JdbcMigrationStore(dataSource);
            JdbcMigrationCatalogVerifier catalogVerifier = new JdbcMigrationCatalogVerifier(
                    migrationStore,
                    MigrationCatalogContract.load(PROJECT_ROOT.resolve("sql/migrations/catalog-contract.json"))
            );
            SchemaMigrationReleaseEntry.ReleaseResult release = new SchemaMigrationReleaseEntry(
                    runner(dataSource, MANIFEST, migrationStore)::migrate,
                    catalogVerifier::verify,
                    migrationStore::withMigrationLock,
                    new SchemaMigrationReleaseEntry.ReleaseMetadata(
                            sha256(Files.readAllBytes(MANIFEST)),
                            baselineSha256,
                            List.of("manual.owner-review"),
                            "migration-l2",
                            "test-revision",
                            "manual_restore_and_rebuild_required",
                            "isolated runtime evidence only"
                    ),
                    RELEASE_REPORT
            ).run();
            SchemaMigrationExecutor.MigrationRunResult result = release.migration();
            MigrationCatalogVerifier.VerificationResult catalog = release.catalog();

            assertThat(release.status()).isEqualTo(SchemaMigrationReleaseEntry.ReleaseStatus.SUCCEEDED);
            assertThat(result.cleanInstall()).isTrue();
            assertThat(result.schemaVersion()).isEqualTo("2026-10-22-g9");
            assertThat(result.appliedMigrationIds()).hasSize(23);
            assertThat(catalog.verified())
                    .withFailMessage("迁移后 catalog 对账失败: missing=%s, forbidden=%s",
                            catalog.missingObjects(), catalog.forbiddenObjects())
                    .isTrue();
            catalogVerified = true;
            catalogFingerprint = catalog.observedFingerprint();
            assertThat(queryForLong(dataSource,
                    "SELECT COUNT(*) FROM public.jchatmind_schema_migration_ledger")).isEqualTo(24);
            assertThat(queryForLong(dataSource,
                    "SELECT COUNT(*) FROM public.jchatmind_schema_migration_ledger WHERE status = 'APPLIED'"))
                    .isEqualTo(24);
            assertThat(queryForLong(dataSource,
                    "SELECT COUNT(*) FROM public.builtin_skill_execution_task")).isZero();
            assertThat(queryForLong(dataSource,
                    "SELECT COUNT(*) FROM public.user_memory_extraction_state")).isZero();
            verifyRagIndexRevisionTriggers(dataSource);
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

            execute(dataSource, "CREATE TABLE unexpected_release_drift (id INTEGER)");
            MigrationCatalogVerifier.VerificationResult unexpected = catalogVerifier.verify();
            assertThat(unexpected.verified()).isFalse();
            assertThat(unexpected.unexpectedObjects())
                    .contains("table:public::unexpected_release_drift:");

            execute(dataSource, "ALTER TABLE agent ADD COLUMN unexpected_release_column TEXT");
            execute(dataSource, "ALTER TABLE agent ADD CONSTRAINT chk_unexpected_release CHECK (1 = 1)");
            MigrationCatalogVerifier.VerificationResult unexpectedColumnAndConstraint = catalogVerifier.verify();
            assertThat(unexpectedColumnAndConstraint.verified()).isFalse();
            assertThat(unexpectedColumnAndConstraint.unexpectedObjects()).contains(
                    "column:public:agent:unexpected_release_column:",
                    "constraint:public:agent:chk_unexpected_release:c:true"
            );

            execute(dataSource, "ALTER TABLE agent ADD COLUMN allowed_kbs JSONB");
            MigrationCatalogVerifier.VerificationResult forbidden = catalogVerifier.verify();
            assertThat(forbidden.verified()).isFalse();
            assertThat(forbidden.forbiddenObjects()).contains("column:public:agent:allowed_kbs:");

            execute(dataSource, "ALTER TABLE user_memory ALTER COLUMN importance DROP DEFAULT");
            execute(dataSource, "ALTER TABLE user_memory ALTER COLUMN importance TYPE TEXT");
            MigrationCatalogVerifier.VerificationResult definitionDrift = catalogVerifier.verify();
            assertThat(definitionDrift.verified()).isFalse();
            assertThat(definitionDrift.definitionMismatches().stream())
                    .anyMatch(value -> value.contains("user_memory:importance"));

            execute(dataSource, "ALTER TABLE agent DROP CONSTRAINT chk_unexpected_release");
            execute(dataSource, "ALTER TABLE agent DROP COLUMN unexpected_release_column");
            execute(dataSource, "ALTER TABLE agent DROP COLUMN allowed_kbs");
            execute(dataSource, "DROP TABLE unexpected_release_drift");
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
            assertThat(upgrade.appliedMigrationIds()).hasSize(15);
            assertThat(replay.appliedMigrationIds()).isEmpty();
            assertThat(queryForLong(dataSource,
                    "SELECT COUNT(*) FROM public.jchatmind_schema_migration_ledger"))
                    .isEqualTo(24);
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
            JdbcMigrationStore migrationStore = new JdbcMigrationStore(dataSource);
            SchemaMigrationExecutor brokenRunner = runner(dataSource, brokenManifest, migrationStore);
            Path failureReport = Path.of("target", "migration-l2", "migration-release-failure-l2.json");

            assertThatThrownBy(() -> new SchemaMigrationReleaseEntry(
                    brokenRunner::migrate,
                    new JdbcMigrationCatalogVerifier(
                            migrationStore,
                            MigrationCatalogContract.load(PROJECT_ROOT.resolve("sql/migrations/catalog-contract.json"))
                    )::verify,
                    migrationStore::withMigrationLock,
                    failureReport
            ).run())
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("Migration script failed");
            JsonNode failure = new ObjectMapper().readTree(Files.readString(failureReport));
            assertThat(failure.path("status").asText()).isEqualTo("FAILED");
            assertThat(failure.path("errorType").asText()).isEqualTo(IllegalStateException.class.getName());
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

    @Test
    @Order(4)
    void shouldKeepCompletedAtAfterStartedAtIfClockMovesBackward() throws Exception {
        String database = createDatabase();
        Path futureStartedSql = Files.createTempFile(
                PROJECT_ROOT.resolve("sql/migrations"), "migration-l2-clock-skew-", ".sql"
        );
        Path clockSkewManifest = Files.createTempFile("jchatmind-clock-skew-manifest-", ".json");
        String migrationId = "migration-l2.force-future-started-at";
        try {
            Files.writeString(futureStartedSql,
                    "UPDATE public.jchatmind_schema_migration_ledger "
                            + "SET started_at = CURRENT_TIMESTAMP + INTERVAL '1 minute' "
                            + "WHERE migration_id = '" + migrationId + "';",
                    StandardCharsets.UTF_8);
            ObjectMapper objectMapper = new ObjectMapper();
            ObjectNode manifest = (ObjectNode) objectMapper.readTree(Files.readString(MANIFEST, StandardCharsets.UTF_8));
            manifest.put("schemaVersion", "migration-l2-clock-skew-v1");
            var migrations = manifest.withArray("migrations");
            migrations.removeAll();
            ObjectNode migration = migrations.addObject();
            migration.put("order", 10);
            migration.put("id", migrationId);
            migration.put("path", PROJECT_ROOT.relativize(futureStartedSql).toString().replace('\\', '/'));
            migration.put("sha256", sha256(Files.readAllBytes(futureStartedSql)));
            migration.put("transactional", true);
            migration.putArray("requires").add("baseline.application-schema");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(clockSkewManifest.toFile(), manifest);

            DataSource dataSource = dataSource(database);
            SchemaMigrationExecutor.MigrationRunResult firstRun = runner(dataSource, clockSkewManifest).migrate();
            assertThat(firstRun.appliedMigrationIds()).containsExactly(migrationId);

            SchemaMigrationExecutor.MigrationRunResult replay = runner(dataSource, clockSkewManifest).migrate();
            assertThat(replay.appliedMigrationIds()).isEmpty();
            assertThat(queryForLong(dataSource,
                    "SELECT COUNT(*) FROM public.jchatmind_schema_migration_ledger "
                            + "WHERE completed_at < started_at")).isZero();
        } finally {
            dropDatabase(database);
            Files.deleteIfExists(clockSkewManifest);
            Files.deleteIfExists(futureStartedSql);
        }
    }

    @Test
    @Order(5)
    void shouldExerciseMemoryGovernanceMappersInIsolatedPostgres() throws Exception {
        String database = createDatabase();
        String userId = "governance-l2-" + UUID.randomUUID();
        String sessionId = UUID.randomUUID().toString();
        try {
            DataSource dataSource = dataSource(database);
            runner(dataSource, MANIFEST).migrate();
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(
                         "INSERT INTO public.chat_session (id, user_id) VALUES (?::uuid, ?)")) {
                statement.setString(1, sessionId);
                statement.setString(2, userId);
                statement.executeUpdate();
            }

            SqlSessionFactory sessionFactory = memoryGovernanceSqlSessionFactory(dataSource);
            try (SqlSession session = sessionFactory.openSession(false)) {
                UserMemoryMapper memoryMapper = session.getMapper(UserMemoryMapper.class);
                UserMemoryCandidateMapper candidateMapper = session.getMapper(UserMemoryCandidateMapper.class);
                UserMemoryConsolidationProposalMapper proposalMapper =
                        session.getMapper(UserMemoryConsolidationProposalMapper.class);

                assertThat(memoryMapper.selectAutomationMode(userId)).isNull();
                assertThat(memoryMapper.upsertAutomationMode(userId, "BALANCED")).isEqualTo(1);
                assertThat(memoryMapper.selectAutomationMode(userId)).isEqualTo("BALANCED");
                assertThat(memoryMapper.selectLastExtractedUserMessageCount(userId, sessionId)).isZero();
                assertThat(memoryMapper.markExtractionAttempt(userId, sessionId)).isEqualTo(1);
                assertThat(memoryMapper.markExtractionFailure(userId, sessionId, "IllegalStateException")).isEqualTo(1);
                assertThat(memoryMapper.markExtractionSuccess(userId, sessionId, 3)).isEqualTo(1);
                assertThat(memoryMapper.selectLastExtractedUserMessageCount(userId, sessionId)).isEqualTo(3);

                String evidenceId = UUID.randomUUID().toString();
                UserMemoryCandidate candidate = UserMemoryCandidate.builder()
                        .userId(userId)
                        .sessionId(sessionId)
                        .memoryType("PREFERENCE")
                        .content("明确偏好简洁回答")
                        .evidence("我长期偏好简洁回答")
                        .importance("medium")
                        .evidenceMessageId(evidenceId)
                        .status(UserMemoryCandidate.STATUS_PENDING)
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build();
                assertThat(candidateMapper.insert(candidate)).isEqualTo(1);
                assertThat(candidateMapper.insert(UserMemoryCandidate.builder()
                        .userId(candidate.getUserId())
                        .sessionId(candidate.getSessionId())
                        .memoryType(candidate.getMemoryType())
                        .content(candidate.getContent())
                        .evidence(candidate.getEvidence())
                        .importance(candidate.getImportance())
                        .evidenceMessageId(candidate.getEvidenceMessageId())
                        .status(UserMemoryCandidate.STATUS_PENDING)
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build())).isZero();
                assertThat(candidateMapper.selectByUserId(userId)).hasSize(1);

                List<String> sourceIds = new java.util.ArrayList<>();
                for (int index = 0; index < 2; index++) {
                    UserMemory source = UserMemory.builder()
                            .userId(userId)
                            .sessionId(sessionId)
                            .memoryType("PREFERENCE")
                            .content("来源偏好 " + index)
                            .importance("medium")
                            .expiresAt(LocalDateTime.now().plusDays(30))
                            .createdAt(LocalDateTime.now())
                            .updatedAt(LocalDateTime.now())
                            .build();
                    assertThat(memoryMapper.insert(source)).isEqualTo(1);
                    sourceIds.add(source.getId());
                }
                assertThat(memoryMapper.selectActiveByIdsForUpdate(userId, sourceIds)).hasSize(2);

                UserMemoryConsolidationProposal proposal = UserMemoryConsolidationProposal.builder()
                        .userId(userId)
                        .sourceMemoryIdsJson("[\"" + sourceIds.get(0) + "\",\"" + sourceIds.get(1) + "\"]")
                        .sourceFingerprint("a".repeat(64))
                        .proposedType("PREFERENCE")
                        .proposedContent("整理后的偏好")
                        .build();
                assertThat(proposalMapper.insert(proposal)).isEqualTo(1);
                assertThat(proposal.getId()).isNotBlank();
                assertThat(proposalMapper.selectPendingByIdAndUserId(proposal.getId(), userId)).isNotNull();

                UserMemory consolidated = UserMemory.builder()
                        .userId(userId)
                        .memoryType("PREFERENCE")
                        .content("整理后的偏好")
                        .importance("medium")
                        .expiresAt(LocalDateTime.now().plusDays(365))
                        .createdAt(LocalDateTime.now())
                        .updatedAt(LocalDateTime.now())
                        .build();
                assertThat(memoryMapper.insert(consolidated)).isEqualTo(1);
                assertThat(memoryMapper.markSupersededByIds(sourceIds, userId, consolidated.getId())).isEqualTo(2);
                assertThat(memoryMapper.selectActiveByUserId(userId)).hasSize(1)
                        .extracting(UserMemory::getId)
                        .containsExactly(consolidated.getId());
                assertThat(proposalMapper.markStatus(proposal.getId(), userId, "APPLIED", consolidated.getId()))
                        .isEqualTo(1);
                assertThat(proposalMapper.selectPendingByUserId(userId)).isEmpty();
                session.rollback();
            }
        } finally {
            dropDatabase(database);
        }
    }

    private SqlSessionFactory memoryGovernanceSqlSessionFactory(DataSource dataSource) throws IOException {
        Configuration configuration = new Configuration(new Environment(
                "memory-governance-l2",
                new JdbcTransactionFactory(),
                dataSource
        ));
        configuration.getTypeHandlerRegistry().register(float[].class, PgVectorTypeHandler.class);
        for (String resource : List.of(
                "mapper/UserMemoryMapper.xml",
                "mapper/UserMemoryCandidateMapper.xml",
                "mapper/UserMemoryConsolidationProposalMapper.xml"
        )) {
            try (InputStream input = org.apache.ibatis.io.Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private static SchemaMigrationExecutor runner(DataSource dataSource, Path manifest) {
        return runner(dataSource, manifest, new JdbcMigrationStore(dataSource));
    }

    private static SchemaMigrationExecutor runner(
            DataSource dataSource,
            Path manifest,
            JdbcMigrationStore migrationStore
    ) {
        return new SchemaMigrationExecutor(
                PROJECT_ROOT,
                manifest,
                baselinePath,
                baselineSha256,
                migrationStore,
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

    private static void execute(DataSource dataSource, String sql) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static List<RagKbIndexRevision> selectKbIndexRevisions(DataSource dataSource, List<String> kbIds)
            throws IOException {
        String resource = "mapper/ChunkBgeM3Mapper.xml";
        Configuration configuration = new Configuration(new Environment(
                "rag-index-revision-l2",
                new JdbcTransactionFactory(),
                dataSource
        ));
        configuration.getTypeHandlerRegistry().register(float[].class, PgVectorTypeHandler.class);
        try (InputStream input = org.apache.ibatis.io.Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        SqlSessionFactory sessionFactory = new SqlSessionFactoryBuilder().build(configuration);
        try (SqlSession session = sessionFactory.openSession()) {
            return session.getMapper(ChunkBgeM3Mapper.class).selectKbIndexRevisions(kbIds);
        }
    }

    private static void verifyRagIndexRevisionTriggers(DataSource dataSource) throws Exception {
        String account = "rag-index-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        execute(dataSource, "INSERT INTO jchatmind_user (account, username, password) VALUES ('"
                + account + "', 'RAG index test', 'test-hash')");
        long ownerId = queryForLong(dataSource,
                "SELECT user_id FROM jchatmind_user WHERE account = '" + account + "'");
        String firstKbId = UUID.randomUUID().toString();
        String secondKbId = UUID.randomUUID().toString();
        String firstDocumentId = UUID.randomUUID().toString();
        String secondDocumentId = UUID.randomUUID().toString();
        String firstChunkId = UUID.randomUUID().toString();

        execute(dataSource, "INSERT INTO knowledge_base (id, name, owner_id) VALUES ('"
                + firstKbId + "'::uuid, 'RAG index test 1', " + ownerId + ")");
        execute(dataSource, "INSERT INTO knowledge_base (id, name, owner_id) VALUES ('"
                + secondKbId + "'::uuid, 'RAG index test 2', " + ownerId + ")");
        execute(dataSource, "INSERT INTO document (id, kb_id, filename) VALUES ('"
                + firstDocumentId + "'::uuid, '" + firstKbId + "'::uuid, 'first.md')");
        execute(dataSource, "INSERT INTO document (id, kb_id, filename) VALUES ('"
                + secondDocumentId + "'::uuid, '" + secondKbId + "'::uuid, 'second.md')");
        execute(dataSource, "INSERT INTO chunk_bge_m3 (id, kb_id, doc_id, content) VALUES ('"
                + firstChunkId + "'::uuid, '" + firstKbId + "'::uuid, '" + firstDocumentId
                + "'::uuid, 'first')");

        long firstRevision = queryForLong(dataSource, "SELECT revision FROM public.rag_kb_index_revision "
                + "WHERE kb_id = '" + firstKbId + "'");
        assertThat(firstRevision).isEqualTo(1);
        List<RagKbIndexRevision> mappedRevisions = selectKbIndexRevisions(
                dataSource,
                List.of(firstKbId, secondKbId)
        );
        assertThat(mappedRevisions).singleElement().satisfies(revision -> {
            assertThat(revision.getKbId()).isEqualTo(firstKbId);
            assertThat(revision.getRevision()).isEqualTo(firstRevision);
        });

        execute(dataSource, "UPDATE document SET filename = 'renamed.md' WHERE id = '"
                + firstDocumentId + "'::uuid");
        long documentRevision = queryForLong(dataSource, "SELECT revision FROM public.rag_kb_index_revision "
                + "WHERE kb_id = '" + firstKbId + "'");
        assertThat(documentRevision).isGreaterThan(firstRevision);

        execute(dataSource, "UPDATE chunk_bge_m3 SET content = 'updated' WHERE id = '"
                + firstChunkId + "'::uuid");
        long updatedRevision = queryForLong(dataSource, "SELECT revision FROM public.rag_kb_index_revision "
                + "WHERE kb_id = '" + firstKbId + "'");
        assertThat(updatedRevision).isGreaterThan(documentRevision);

        String assetId = UUID.randomUUID().toString();
        execute(dataSource, "INSERT INTO document_asset "
                + "(asset_id, document_id, asset_type, asset_key, content_hash, parser_version) VALUES ('"
                + assetId + "'::uuid, '" + firstDocumentId
                + "'::uuid, 'PDF_PAGE_TEXT', 'page-1', 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa', 'test')");
        long assetRevision = queryForLong(dataSource, "SELECT revision FROM public.rag_kb_index_revision "
                + "WHERE kb_id = '" + firstKbId + "'");
        assertThat(assetRevision).isGreaterThan(updatedRevision);

        execute(dataSource, "INSERT INTO document_asset_chunk "
                + "(asset_id, chunk_id, asset_document_id, chunk_document_id) VALUES ('"
                + assetId + "'::uuid, '" + firstChunkId + "'::uuid, '" + firstDocumentId + "'::uuid, '"
                + firstDocumentId + "'::uuid)");
        long relationRevision = queryForLong(dataSource, "SELECT revision FROM public.rag_kb_index_revision "
                + "WHERE kb_id = '" + firstKbId + "'");
        assertThat(relationRevision).isGreaterThan(assetRevision);

        execute(dataSource, "UPDATE document_asset SET status = 'READY' WHERE asset_id = '"
                + assetId + "'::uuid");
        long assetStatusRevision = queryForLong(dataSource, "SELECT revision FROM public.rag_kb_index_revision "
                + "WHERE kb_id = '" + firstKbId + "'");
        assertThat(assetStatusRevision).isGreaterThan(relationRevision);

        execute(dataSource, "DELETE FROM document_asset_chunk WHERE asset_id = '" + assetId + "'::uuid");
        long relationDeleteRevision = queryForLong(dataSource, "SELECT revision FROM public.rag_kb_index_revision "
                + "WHERE kb_id = '" + firstKbId + "'");
        assertThat(relationDeleteRevision).isGreaterThan(assetStatusRevision);

        execute(dataSource, "DELETE FROM chunk_bge_m3 WHERE id = '" + firstChunkId + "'::uuid");
        long deletedRevision = queryForLong(dataSource, "SELECT revision FROM public.rag_kb_index_revision "
                + "WHERE kb_id = '" + firstKbId + "'");
        assertThat(deletedRevision).isGreaterThan(relationDeleteRevision);

        execute(dataSource, "DELETE FROM knowledge_base WHERE id = '" + firstKbId + "'::uuid");
        assertThat(queryForLong(dataSource, "SELECT COUNT(*) FROM public.rag_kb_index_revision "
                + "WHERE kb_id = '" + firstKbId + "'")).isZero();
        assertThat(queryForLong(dataSource, "SELECT COUNT(*) FROM public.rag_kb_index_revision "
                + "WHERE kb_id = '" + secondKbId + "'")).isZero();
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

            CREATE TABLE chat_session (
                id UUID PRIMARY KEY,
                user_id TEXT NOT NULL,
                agent_id UUID,
                title TEXT,
                metadata JSONB,
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );

            CREATE TABLE chat_message (
                id UUID PRIMARY KEY,
                session_id UUID NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE,
                role VARCHAR(32) NOT NULL,
                content TEXT,
                metadata JSONB,
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
                id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
                user_id VARCHAR(64) NOT NULL,
                session_id UUID,
                memory_type VARCHAR(32),
                content TEXT NOT NULL,
                embedding vector(3),
                created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            );

            CREATE TABLE user_memory_candidate (
                id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
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
