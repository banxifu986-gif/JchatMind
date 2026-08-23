package com.kama.jchatmind.rag;

import com.kama.jchatmind.mapper.Bm25TokenDictionaryMapper;
import com.kama.jchatmind.service.impl.VchordBm25ProjectionService;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "g2.vchord.projection.l2", matches = "true")
class VectorChordBm25ProjectionL2Test {

    private static final IsolatedPostgresContainer DATABASE = new IsolatedPostgresContainer(
            "g2-vchord-poc",
            "g2vchord",
            "sha256:8c106fde572fb799217dcacb01b6f869af693322069bc134dbd6341d0c175abd"
    );
    private static final String LEGACY_CHUNK_ID = "00000000-0000-0000-0000-00000000c101";
    private static final String WRITTEN_CHUNK_ID = "00000000-0000-0000-0000-00000000c103";
    private static final String KB_ID = "00000000-0000-0000-0000-00000000c111";
    private static final String DOCUMENT_ID = "00000000-0000-0000-0000-00000000c121";
    private static final String TEST_ROLE = "g2vchordprojectionl2";
    private static final String TEST_PASSWORD = UUID.randomUUID().toString();
    private static final long REVERSE_TOKEN_ORDER_LOCK = 84625019L;
    private static final String FIRST_PROJECTION_APPLICATION = "g2-vchord-projection-first";
    private static final String SECOND_PROJECTION_APPLICATION = "g2-vchord-projection-second";
    private static final Path MIGRATION = Path.of(
            "..", "sql", "knowledge-base", "2026-08-22-add-vchord-bm25-index.sql"
    );

    @BeforeEach
    void setUp() throws Exception {
        DATABASE.assertIsolation();
        dropSchema();
        DATABASE.sql("CREATE TABLE public.chunk_bge_m3 (id UUID PRIMARY KEY, kb_id UUID NOT NULL, doc_id UUID NOT NULL, content TEXT, metadata JSONB NOT NULL DEFAULT '{}'::jsonb, created_at TIMESTAMP, updated_at TIMESTAMP)");
        DATABASE.restore(Files.readString(MIGRATION));
        DATABASE.sql("CREATE ROLE " + TEST_ROLE + " LOGIN PASSWORD '" + TEST_PASSWORD + "'");
        DATABASE.sql("GRANT CONNECT ON DATABASE g2vchord TO " + TEST_ROLE);
        DATABASE.sql("GRANT USAGE ON SCHEMA public, bm25_catalog TO " + TEST_ROLE);
        DATABASE.sql("GRANT SELECT, INSERT, UPDATE ON rag_bm25_token_dictionary TO " + TEST_ROLE);
        DATABASE.sql("GRANT USAGE, SELECT ON SEQUENCE rag_bm25_token_dictionary_token_id_seq TO " + TEST_ROLE);
        DATABASE.sql("INSERT INTO public.chunk_bge_m3 (id, kb_id, doc_id, content, metadata, created_at, updated_at) VALUES ('" + LEGACY_CHUNK_ID + "', '" + KB_ID + "', '" + DOCUMENT_ID + "', 'legacy chunk', '{}'::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)");
    }

    @AfterEach
    void tearDown() {
        DATABASE.assertIsolation();
        dropSchema();
    }

    @Test
    void persistsStableTokenIdsAndQueryableChunkProjection() {
        Map<String, Integer> firstTokenIds = upsertAndReadTokenIds("rag", "接口", "向量", "量接");
        Map<String, Integer> secondTokenIds = upsertAndReadTokenIds("量接", "向量", "接口", "rag");
        assertEquals(firstTokenIds, secondTokenIds);

        String titleVector = toBm25Vector(firstTokenIds, "rag", "接口");
        String contentVector = toBm25Vector(firstTokenIds, "向量", "量接", "接口");
        DATABASE.sql("""
                INSERT INTO public.chunk_bge_m3
                (id, kb_id, doc_id, content, metadata, title_bm25_vector, content_bm25_vector, bm25_index_version, created_at, updated_at)
                VALUES ('%s', '%s', '%s', '向量接口', '{}'::jsonb, '%s'::bm25_catalog.bm25vector, '%s'::bm25_catalog.bm25vector, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.formatted(WRITTEN_CHUNK_ID, KB_ID, DOCUMENT_ID, titleVector, contentVector));

        assertEquals("4", DATABASE.sql("SELECT count(*) FROM rag_bm25_token_dictionary").trim());
        assertEquals("1", DATABASE.sql("SELECT count(*) FROM public.chunk_bge_m3 WHERE id = '" + LEGACY_CHUNK_ID + "' AND bm25_index_version IS NULL").trim());
        assertEquals("1", DATABASE.sql("SELECT count(*) FROM public.chunk_bge_m3 WHERE id = '" + WRITTEN_CHUNK_ID + "' AND title_bm25_vector IS NOT NULL AND content_bm25_vector IS NOT NULL AND bm25_index_version = 1").trim());
        assertEquals(WRITTEN_CHUNK_ID, DATABASE.sql("SET search_path = bm25_catalog, pg_catalog, public; " + """
                SELECT id::text
                FROM public.chunk_bge_m3
                ORDER BY content_bm25_vector <&> bm25_catalog.to_bm25query(
                    'public.idx_chunk_bge_m3_content_bm25'::regclass,
                    '%s'::bm25_catalog.bm25vector
                )
                LIMIT 1
                """.formatted(contentVector)).trim());
    }

    @Test
    void rollsBackTokenDictionaryAndNewChunkWhenIngestionTransactionFails() {
        DATABASE.sqlCommands(
                "BEGIN",
                upsertSql("rag", "接口", "向量", "量接"),
                """
                INSERT INTO public.chunk_bge_m3
                (id, kb_id, doc_id, content, metadata, title_bm25_vector, content_bm25_vector, bm25_index_version, created_at, updated_at)
                VALUES ('00000000-0000-0000-0000-00000000c102', '%s', '%s', 'new chunk', '{}'::jsonb, '{1:1}'::bm25_catalog.bm25vector, '{1:1}'::bm25_catalog.bm25vector, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.formatted(KB_ID, DOCUMENT_ID),
                "ROLLBACK"
        );

        assertEquals("0", DATABASE.sql("SELECT count(*) FROM rag_bm25_token_dictionary").trim());
        assertEquals("1", DATABASE.sql("SELECT count(*) FROM public.chunk_bge_m3").trim());
        assertEquals("1", DATABASE.sql("SELECT count(*) FROM public.chunk_bge_m3 WHERE id = '" + LEGACY_CHUNK_ID + "'").trim());
    }

    @Test
    void rejectsIncompleteBm25ProjectionCombinations() {
        assertRejectedProjection("00000000-0000-0000-0000-00000000c104", "title_bm25_vector", "'{1:1}'::bm25_catalog.bm25vector");
        assertRejectedProjection("00000000-0000-0000-0000-00000000c105", "content_bm25_vector", "'{1:1}'::bm25_catalog.bm25vector");
        assertRejectedProjection("00000000-0000-0000-0000-00000000c106", "bm25_index_version", "1");
    }

    @Test
    void completesConcurrentReverseTokenInputsWithoutDictionaryDeadlock() throws Exception {
        DATABASE.sql("INSERT INTO rag_bm25_token_dictionary (token) VALUES ('rag'), ('接口')");
        installReverseTokenOrderGate();
        SqlSessionFactory sessionFactory = newSessionFactory();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection gateConnection = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:55436/g2vchord",
                TEST_ROLE,
                TEST_PASSWORD
        )) {
            execute(gateConnection, "SELECT pg_advisory_lock(" + REVERSE_TOKEN_ORDER_LOCK + ")");
            Future<String> first = executor.submit(() -> projectInTransaction(
                    sessionFactory,
                    FIRST_PROJECTION_APPLICATION,
                    "接口 rag"
            ));
            waitForSessionLock(FIRST_PROJECTION_APPLICATION);
            Future<String> second = executor.submit(() -> projectInTransaction(
                    sessionFactory,
                    SECOND_PROJECTION_APPLICATION,
                    "rag 接口"
            ));
            waitForSessionLock(SECOND_PROJECTION_APPLICATION);
            execute(gateConnection, "SELECT pg_advisory_unlock(" + REVERSE_TOKEN_ORDER_LOCK + ")");

            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }

        assertEquals("2", DATABASE.sql("SELECT count(*) FROM rag_bm25_token_dictionary").trim());
        assertEquals("2", DATABASE.sql("SELECT count(*) FROM rag_bm25_token_dictionary WHERE token IN ('rag', '接口')").trim());
    }

    private void assertRejectedProjection(String chunkId, String field, String value) {
        AssertionError exception = assertThrows(AssertionError.class, () -> DATABASE.sql("""
                INSERT INTO public.chunk_bge_m3
                (id, kb_id, doc_id, content, metadata, %s, created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'incomplete projection', '{}'::jsonb, %s, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.formatted(field, chunkId, KB_ID, DOCUMENT_ID, value)));
        assertTrue(exception.getMessage().contains("约束") || exception.getMessage().contains("constraint"));
    }

    private Map<String, Integer> upsertAndReadTokenIds(String... tokens) {
        DATABASE.sql(upsertSql(tokens));
        Map<String, Integer> tokenIds = new HashMap<>();
        for (String row : DATABASE.sql("SELECT token || '|' || token_id FROM rag_bm25_token_dictionary ORDER BY token").lines().toList()) {
            int delimiter = row.indexOf('|');
            tokenIds.put(row.substring(0, delimiter), Integer.parseInt(row.substring(delimiter + 1)));
        }
        return tokenIds;
    }

    private String upsertSql(String... tokens) {
        String values = String.join(",", java.util.Arrays.stream(tokens)
                .map(token -> "('" + token.replace("'", "''") + "')")
                .toList());
        return """
                INSERT INTO rag_bm25_token_dictionary (token)
                VALUES %s
                ON CONFLICT (token) DO UPDATE
                SET token = rag_bm25_token_dictionary.token
                RETURNING token, token_id
                """.formatted(values);
    }

    private String projectInTransaction(
            SqlSessionFactory sessionFactory,
            String applicationName,
            String text
    ) throws Exception {
        try (SqlSession session = sessionFactory.openSession(false)) {
            execute(session.getConnection(), "SET application_name = '" + applicationName + "'");
            execute(session.getConnection(), "SET lock_timeout = '10s'");
            Bm25TokenDictionaryMapper mapper = session.getMapper(Bm25TokenDictionaryMapper.class);
            VchordBm25ProjectionService projectionService = new VchordBm25ProjectionService(mapper);
            String projection = projectionService.project(text, text).contentVector();
            session.commit();
            return projection;
        }
    }

    private SqlSessionFactory newSessionFactory() throws Exception {
        PooledDataSource dataSource = new PooledDataSource();
        dataSource.setDriver("org.postgresql.Driver");
        dataSource.setUrl("jdbc:postgresql://127.0.0.1:55436/g2vchord");
        dataSource.setUsername(TEST_ROLE);
        dataSource.setPassword(TEST_PASSWORD);

        Configuration configuration = new Configuration(new Environment(
                "g2-vchord-projection-l2",
                new JdbcTransactionFactory(),
                dataSource
        ));
        try (InputStream mapper = Resources.getResourceAsStream("mapper/Bm25TokenDictionaryMapper.xml")) {
            new XMLMapperBuilder(
                    mapper,
                    configuration,
                    "mapper/Bm25TokenDictionaryMapper.xml",
                    configuration.getSqlFragments()
            ).parse();
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private void installReverseTokenOrderGate() {
        DATABASE.sql("""
                CREATE OR REPLACE FUNCTION public.g2_pause_reverse_token_order() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                    IF NEW.token = '接口'
                       AND current_setting('application_name', true) = '%s' THEN
                        PERFORM pg_advisory_lock(%d);
                        PERFORM pg_advisory_unlock(%d);
                    END IF;
                    RETURN NEW;
                END;
                $$;

                CREATE TRIGGER trg_g2_pause_reverse_token_order
                BEFORE UPDATE ON rag_bm25_token_dictionary
                FOR EACH ROW EXECUTE FUNCTION public.g2_pause_reverse_token_order()
                """.formatted(
                FIRST_PROJECTION_APPLICATION,
                REVERSE_TOKEN_ORDER_LOCK,
                REVERSE_TOKEN_ORDER_LOCK
        ));
    }

    private void waitForSessionLock(String applicationName) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            String waitingSessionCount = DATABASE.sql("""
                    SELECT count(*)
                    FROM pg_stat_activity
                    WHERE application_name = '%s'
                      AND wait_event_type = 'Lock'
                    """.formatted(applicationName)).trim();
            if ("1".equals(waitingSessionCount)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("并发投影未按预期进入锁等待: " + applicationName);
    }

    private void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private String toBm25Vector(Map<String, Integer> tokenIds, String... tokens) {
        Map<Integer, Integer> frequencies = new TreeMap<>();
        for (String token : tokens) {
            Integer tokenId = tokenIds.get(token);
            assertTrue(tokenId != null, "缺少 token ID: " + token);
            frequencies.merge(tokenId, 1, Integer::sum);
        }
        return "{" + frequencies.entrySet().stream()
                .map(entry -> entry.getKey() + ":" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(",")) + "}";
    }

    private void dropSchema() {
        DATABASE.sql("""
                DO $$
                BEGIN
                    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '%s') THEN
                        DROP OWNED BY %s CASCADE;
                        DROP ROLE %s;
                    END IF;
                END $$;
                """.formatted(TEST_ROLE, TEST_ROLE, TEST_ROLE));
        DATABASE.sql("DROP TABLE IF EXISTS public.chunk_bge_m3 CASCADE");
        DATABASE.sql("DROP TABLE IF EXISTS public.rag_bm25_token_dictionary CASCADE");
        DATABASE.sql("DROP FUNCTION IF EXISTS public.g2_pause_reverse_token_order()");
    }
}
