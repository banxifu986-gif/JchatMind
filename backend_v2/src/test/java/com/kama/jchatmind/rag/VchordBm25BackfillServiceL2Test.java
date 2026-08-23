package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.mapper.Bm25TokenDictionaryMapper;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.mapper.VchordBm25BackfillMapper;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.service.impl.VchordBm25BackfillService;
import com.kama.jchatmind.service.impl.VchordBm25ProjectionService;
import com.kama.jchatmind.service.impl.VchordBm25QueryService;
import com.kama.jchatmind.typehandler.PgVectorTypeHandler;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "g2.vchord.backfill.l2", matches = "true")
class VchordBm25BackfillServiceL2Test {

    private static final IsolatedPostgresContainer DATABASE = new IsolatedPostgresContainer(
            "g2-vchord-poc", "g2vchord", "sha256:8c106fde572fb799217dcacb01b6f869af693322069bc134dbd6341d0c175abd"
    );
    private static final String KB_ID = "00000000-0000-0000-0000-00000000c311";
    private static final String DOCUMENT_ID = "00000000-0000-0000-0000-00000000c321";
    private static final String LEGACY_CHUNK_ID = "00000000-0000-0000-0000-00000000c301";
    private static final String UNPROJECTED_CHUNK_ID = "00000000-0000-0000-0000-00000000c302";
    private static final long CONCURRENT_BACKFILL_LOCK = 84625020L;
    private static final String TEST_ROLE = "g2vchordbackfilll2";
    private static final String TEST_PASSWORD = UUID.randomUUID().toString();

    private AnnotationConfigApplicationContext context;

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
        DATABASE.assertIsolation();
        DATABASE.sql("DROP TABLE IF EXISTS public.chunk_bge_m3 CASCADE");
        DATABASE.sql("DROP TABLE IF EXISTS public.rag_bm25_token_dictionary CASCADE");
        DATABASE.sql("DROP FUNCTION IF EXISTS public.g2_pause_bm25_backfill_update()");
        DATABASE.sql("DROP FUNCTION IF EXISTS public.g2_reject_bm25_backfill_update()");
        DATABASE.sql("""
                DO $$
                BEGIN
                    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = '%s') THEN
                        DROP OWNED BY %s CASCADE;
                        DROP ROLE %s;
                    END IF;
                END $$;
                """.formatted(TEST_ROLE, TEST_ROLE, TEST_ROLE));
    }

    @Test
    void backfillsHistoricalProjectionBeforeNativeTitleAndContentSearch() throws Exception {
        prepareSchemaAndRole();
        insertLegacyChunk(LEGACY_CHUNK_ID, "RAG native backfill", "历史正文检索");
        insertLegacyChunk(UNPROJECTED_CHUNK_ID, "RAG unprojected", "未回填正文");
        context = new AnnotationConfigApplicationContext(BackfillL2Configuration.class);
        VchordBm25BackfillService backfillService = context.getBean(VchordBm25BackfillService.class);
        VchordBm25QueryService queryService = context.getBean(VchordBm25QueryService.class);

        assertEquals(1, backfillService.backfill(1));
        assertEquals(
                "1",
                DATABASE.sql("SELECT count(*) FROM public.chunk_bge_m3 WHERE bm25_index_version = 1").trim()
        );
        assertEquals(
                LEGACY_CHUNK_ID,
                queryService.searchTitle(List.of(KB_ID), "RAG", null, null, null, 3).get(0).getChunkId()
        );
        assertEquals(
                LEGACY_CHUNK_ID,
                queryService.searchContent(List.of(KB_ID), "正文", null, null, null, 3).get(0).getChunkId()
        );
        assertFalse(queryService.searchContent(List.of(KB_ID), "未回填", null, null, null, 3).stream()
                .map(RagRetrievalResult::getChunkId)
                .anyMatch(UNPROJECTED_CHUNK_ID::equals), "无投影历史 chunk 不得被原生 BM25 误命中");

        DATABASE.sql("DELETE FROM public.chunk_bge_m3 WHERE id = '" + LEGACY_CHUNK_ID + "'");
        DATABASE.sql("REINDEX INDEX public.idx_chunk_bge_m3_title_bm25");
        DATABASE.sql("REINDEX INDEX public.idx_chunk_bge_m3_content_bm25");
        assertFalse(queryService.searchTitle(List.of(KB_ID), "RAG", null, null, null, 3).stream()
                .map(RagRetrievalResult::getChunkId)
                .anyMatch(LEGACY_CHUNK_ID::equals), "删除并重建索引后不得保留陈旧命中");
    }

    @Test
    void letsConcurrentWorkersClaimTheSameLegacyChunkAtMostOnce() throws Exception {
        prepareSchemaAndRole();
        insertLegacyChunk(LEGACY_CHUNK_ID, "RAG native backfill", "历史正文检索");
        installUpdatePauseTrigger();
        context = new AnnotationConfigApplicationContext(BackfillL2Configuration.class);
        VchordBm25BackfillService backfillService = context.getBean(VchordBm25BackfillService.class);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection gateConnection = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:55436/g2vchord", "postgres", "postgres"
        )) {
            execute(gateConnection, "SELECT pg_advisory_lock(" + CONCURRENT_BACKFILL_LOCK + ")");
            Future<Integer> first = executor.submit(() -> backfillService.backfill(1));
            waitForBackfillUpdateLock();
            Future<Integer> second = executor.submit(() -> backfillService.backfill(1));
            assertEquals(0, second.get(5, TimeUnit.SECONDS));
            execute(gateConnection, "SELECT pg_advisory_unlock(" + CONCURRENT_BACKFILL_LOCK + ")");
            assertEquals(1, first.get(10, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }

        assertEquals(
                "1",
                DATABASE.sql("SELECT count(*) FROM public.chunk_bge_m3 WHERE bm25_index_version = 1").trim()
        );
    }

    @Test
    void rollsBackTokenDictionaryAndChunkProjectionWhenBackfillUpdateFails() throws Exception {
        prepareSchemaAndRole();
        insertLegacyChunk(LEGACY_CHUNK_ID, "RAG native backfill", "历史正文检索");
        DATABASE.sql("""
                CREATE OR REPLACE FUNCTION public.g2_reject_bm25_backfill_update() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'reject BM25 backfill update';
                END;
                $$;

                CREATE TRIGGER trg_g2_reject_bm25_backfill_update
                BEFORE UPDATE ON public.chunk_bge_m3
                FOR EACH ROW EXECUTE FUNCTION public.g2_reject_bm25_backfill_update()
                """);
        context = new AnnotationConfigApplicationContext(BackfillL2Configuration.class);
        VchordBm25BackfillService backfillService = context.getBean(VchordBm25BackfillService.class);

        RuntimeException exception = assertThrows(RuntimeException.class, () -> backfillService.backfill(1));
        assertTrue(hasCauseMessage(exception, "reject BM25 backfill update"));

        assertEquals("0", DATABASE.sql("SELECT count(*) FROM public.rag_bm25_token_dictionary").trim());
        assertEquals(
                "1",
                DATABASE.sql("SELECT count(*) FROM public.chunk_bge_m3 WHERE bm25_index_version IS NULL").trim()
        );
    }

    private void prepareSchemaAndRole() throws Exception {
        DATABASE.assertIsolation();
        DATABASE.sql("DROP TABLE IF EXISTS public.chunk_bge_m3 CASCADE");
        DATABASE.sql("DROP TABLE IF EXISTS public.rag_bm25_token_dictionary CASCADE");
        DATABASE.sql("""
                CREATE TABLE public.chunk_bge_m3 (
                    id UUID PRIMARY KEY,
                    kb_id UUID NOT NULL,
                    doc_id UUID NOT NULL,
                    content TEXT,
                    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
                    embedding REAL[],
                    created_at TIMESTAMP,
                    updated_at TIMESTAMP
                )
                """);
        DATABASE.restore(Files.readString(Path.of("..", "sql", "knowledge-base", "2026-08-22-add-vchord-bm25-index.sql")));
        DATABASE.sql("CREATE ROLE " + TEST_ROLE + " LOGIN PASSWORD '" + TEST_PASSWORD + "'");
        DATABASE.sql("GRANT CONNECT ON DATABASE g2vchord TO " + TEST_ROLE);
        DATABASE.sql("GRANT USAGE ON SCHEMA public, bm25_catalog TO " + TEST_ROLE);
        DATABASE.sql("GRANT SELECT, UPDATE ON public.chunk_bge_m3 TO " + TEST_ROLE);
        DATABASE.sql("GRANT SELECT, INSERT, UPDATE ON public.rag_bm25_token_dictionary TO " + TEST_ROLE);
        DATABASE.sql("GRANT USAGE, SELECT ON SEQUENCE public.rag_bm25_token_dictionary_token_id_seq TO " + TEST_ROLE);
    }

    private void insertLegacyChunk(String chunkId, String titleSearchText, String content) {
        DATABASE.sql("""
                INSERT INTO public.chunk_bge_m3
                (id, kb_id, doc_id, content, metadata, embedding, created_at, updated_at)
                VALUES ('%s', '%s', '%s', '%s', jsonb_build_object('retrievableTitleSearchText', '%s'),
                        ARRAY[0.1, 0.2, 0.3]::REAL[], CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.formatted(
                chunkId,
                KB_ID,
                DOCUMENT_ID,
                content.replace("'", "''"),
                titleSearchText.replace("'", "''")
        ));
    }

    private void installUpdatePauseTrigger() {
        DATABASE.sql("""
                CREATE OR REPLACE FUNCTION public.g2_pause_bm25_backfill_update() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                    PERFORM pg_advisory_lock(%d);
                    PERFORM pg_advisory_unlock(%d);
                    RETURN NEW;
                END;
                $$;

                CREATE TRIGGER trg_g2_pause_bm25_backfill_update
                BEFORE UPDATE ON public.chunk_bge_m3
                FOR EACH ROW EXECUTE FUNCTION public.g2_pause_bm25_backfill_update()
                """.formatted(CONCURRENT_BACKFILL_LOCK, CONCURRENT_BACKFILL_LOCK));
    }

    private void waitForBackfillUpdateLock() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            String waitingSessionCount = DATABASE.sql("""
                    SELECT count(*)
                    FROM pg_stat_activity
                    WHERE usename = '%s'
                      AND wait_event_type = 'Lock'
                    """.formatted(TEST_ROLE)).trim();
            if ("1".equals(waitingSessionCount)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("首个 BM25 回填 worker 未按预期持有历史分块锁");
    }

    private void execute(Connection connection, String sql) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private boolean hasCauseMessage(Throwable exception, String message) {
        Throwable current = exception;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains(message)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    @org.springframework.context.annotation.Configuration
    @EnableTransactionManagement
    static class BackfillL2Configuration {
        @Bean
        DataSource dataSource() {
            DriverManagerDataSource dataSource = new DriverManagerDataSource(
                    "jdbc:postgresql://127.0.0.1:55436/g2vchord", TEST_ROLE, TEST_PASSWORD
            );
            dataSource.setDriverClassName("org.postgresql.Driver");
            return dataSource;
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new org.springframework.jdbc.datasource.DataSourceTransactionManager(dataSource);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            Configuration configuration = new Configuration(new Environment(
                    "g2-vchord-backfill-l2", new SpringManagedTransactionFactory(), dataSource
            ));
            configuration.getTypeHandlerRegistry().register(float[].class, PgVectorTypeHandler.class);
            parseMapper(configuration, "mapper/Bm25TokenDictionaryMapper.xml");
            parseMapper(configuration, "mapper/VchordBm25BackfillMapper.xml");
            parseMapper(configuration, "mapper/ChunkBgeM3Mapper.xml");
            return new SqlSessionFactoryBuilder().build(configuration);
        }

        @Bean
        SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory sqlSessionFactory) {
            return new SqlSessionTemplate(sqlSessionFactory);
        }

        @Bean
        Bm25TokenDictionaryMapper bm25TokenDictionaryMapper(SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(Bm25TokenDictionaryMapper.class);
        }

        @Bean
        VchordBm25BackfillMapper vchordBm25BackfillMapper(SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(VchordBm25BackfillMapper.class);
        }

        @Bean
        ChunkBgeM3Mapper chunkBgeM3Mapper(SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(ChunkBgeM3Mapper.class);
        }

        @Bean
        VchordBm25ProjectionService vchordBm25ProjectionService(Bm25TokenDictionaryMapper tokenDictionaryMapper) {
            return new VchordBm25ProjectionService(tokenDictionaryMapper);
        }

        @Bean
        VchordBm25BackfillService vchordBm25BackfillService(
                VchordBm25BackfillMapper backfillMapper,
                VchordBm25ProjectionService projectionService
        ) {
            return new VchordBm25BackfillService(backfillMapper, projectionService, new ObjectMapper());
        }

        @Bean
        VchordBm25QueryService vchordBm25QueryService(
                Bm25TokenDictionaryMapper tokenDictionaryMapper,
                ChunkBgeM3Mapper chunkBgeM3Mapper,
                JdbcTemplate jdbcTemplate
        ) {
            return new VchordBm25QueryService(tokenDictionaryMapper, chunkBgeM3Mapper, jdbcTemplate);
        }

        private void parseMapper(Configuration configuration, String resource) throws Exception {
            try (InputStream mapper = Resources.getResourceAsStream(resource)) {
                new XMLMapperBuilder(mapper, configuration, resource, configuration.getSqlFragments()).parse();
            }
        }
    }
}
