package com.kama.jchatmind.rag;

import com.kama.jchatmind.mapper.Bm25TokenDictionaryMapper;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
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
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIfSystemProperty(named = "g2.vchord.query.l2", matches = "true")
class VchordBm25QueryServiceL2Test {

    private static final IsolatedPostgresContainer DATABASE = new IsolatedPostgresContainer(
            "g2-vchord-poc", "g2vchord", "sha256:8c106fde572fb799217dcacb01b6f869af693322069bc134dbd6341d0c175abd"
    );
    private static final String KB_ID = "00000000-0000-0000-0000-00000000c211";
    private static final String DOCUMENT_ID = "00000000-0000-0000-0000-00000000c221";
    private static final String TITLE_CHUNK_ID = "00000000-0000-0000-0000-00000000c201";
    private static final String CONTENT_CHUNK_ID = "00000000-0000-0000-0000-00000000c202";
    private static final String OUTSIDE_CHUNK_ID = "00000000-0000-0000-0000-00000000c203";
    private static final String WILDCARD_GOLD_CHUNK_ID = "00000000-0000-0000-0000-00000000c204";
    private static final String WILDCARD_DECOY_CHUNK_ID = "00000000-0000-0000-0000-00000000c205";
    private static final String TEST_ROLE = "g2vchordqueryl2";
    private static final String TEST_PASSWORD = UUID.randomUUID().toString();
    private static final String SEARCH_PATH = "SET LOCAL search_path = bm25_catalog, pg_catalog, public";

    private AnnotationConfigApplicationContext context;

    @AfterEach
    void tearDown() {
        if (context != null) {
            context.close();
        }
        DATABASE.assertIsolation();
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
    }

    @Test
    void executesScopedNativeTitleAndContentBm25OnTheTransactionConnectionWithoutDictionaryWrites() throws Exception {
        DATABASE.assertIsolation();
        prepareSchemaAndFixture();
        context = new AnnotationConfigApplicationContext(QueryServiceL2Configuration.class);
        VchordBm25QueryService queryService = context.getBean(VchordBm25QueryService.class);
        RecordingDataSource dataSource = context.getBean(RecordingDataSource.class);

        dataSource.clearExecutions();
        List<RagRetrievalResult> titleResults = queryService.searchTitle(
                List.of(KB_ID), "title", "architecture.md", "md", "rag >", 1
        );
        assertEquals(List.of(TITLE_CHUNK_ID), titleResults.stream().map(RagRetrievalResult::getChunkId).toList());
        assertEquals(1, titleResults.get(0).getTitleBm25Rank());
        assertTrue(titleResults.get(0).getDistance() == null, "原生 BM25 不能伪装为 pgvector distance");
        assertSameTransactionConnection(dataSource);

        assertEquals(WILDCARD_GOLD_CHUNK_ID, queryService.searchTitle(
                List.of(KB_ID), "title", "architecture.md", "md", "rag_100% >", 1
        ).get(0).getChunkId(), "HARD 路径前缀中的通配符必须按字面量过滤");
        assertEquals(WILDCARD_GOLD_CHUNK_ID, queryService.searchContent(
                List.of(KB_ID), "content", "architecture.md", "md", "rag_100% >", 1
        ).get(0).getChunkId(), "正文 HARD 路径前缀中的通配符必须按字面量过滤");

        dataSource.clearExecutions();
        List<RagRetrievalResult> contentResults = queryService.searchContent(
                List.of(KB_ID), "content", "architecture.md", "md", "rag >", 1
        );
        assertEquals(List.of(CONTENT_CHUNK_ID), contentResults.stream().map(RagRetrievalResult::getChunkId).toList());
        assertEquals(1, contentResults.get(0).getContentBm25Rank());
        assertSameTransactionConnection(dataSource);

        int dictionaryCountBeforeUnknownQuery = Integer.parseInt(
                DATABASE.sql("SELECT count(*) FROM rag_bm25_token_dictionary").trim()
        );
        dataSource.clearExecutions();
        assertTrue(queryService.searchTitle(List.of(KB_ID), "unknown", null, null, null, 3).isEmpty());
        assertEquals(
                dictionaryCountBeforeUnknownQuery,
                Integer.parseInt(DATABASE.sql("SELECT count(*) FROM rag_bm25_token_dictionary").trim()),
                "未知查询词不得写入词典"
        );
        assertFalse(dataSource.executedSql().stream().anyMatch(sql -> sql.contains("set local search_path")));

        String plan = DATABASE.sql("""
                SET search_path = bm25_catalog, pg_catalog, public;
                EXPLAIN (COSTS OFF)
                SELECT id
                FROM public.chunk_bge_m3
                WHERE kb_id = '%s'::uuid
                  AND bm25_index_version = 1
                  AND metadata->>'sourceName' = 'architecture.md'
                  AND metadata->>'sourceType' = 'md'
                  AND lower(regexp_replace(trim(COALESCE(metadata->>'contentPath', '')), '\\s+', ' ', 'g')) LIKE 'rag >%%'
                ORDER BY content_bm25_vector <&> bm25_catalog.to_bm25query(
                    'public.idx_chunk_bge_m3_content_bm25'::regclass,
                    '{2:1}'::bm25_catalog.bm25vector
                )
                LIMIT 3
                """.formatted(KB_ID));
        assertTrue(plan.contains("Limit"));
        assertTrue(plan.contains("kb_id = '" + KB_ID + "'::uuid"));
        assertTrue(plan.contains("sourceName"));
        assertTrue(plan.contains("sourceType"));
        assertTrue(plan.contains("contentPath"));

        DATABASE.sql("DELETE FROM public.chunk_bge_m3 WHERE id = '" + CONTENT_CHUNK_ID + "'");
        assertFalse(queryService.searchContent(List.of(KB_ID), "content", "architecture.md", "md", "rag >", 3).stream()
                .map(RagRetrievalResult::getChunkId)
                .anyMatch(CONTENT_CHUNK_ID::equals));
        DATABASE.sql("""
                INSERT INTO public.chunk_bge_m3
                (id, kb_id, doc_id, content, metadata, title_bm25_vector, content_bm25_vector, bm25_index_version, created_at, updated_at)
                VALUES ('%s', '%s', '%s', 'content body', %s, '{1:1}'::bm25_catalog.bm25vector, '{2:1}'::bm25_catalog.bm25vector, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.formatted(CONTENT_CHUNK_ID, KB_ID, DOCUMENT_ID, metadata("architecture.md", "md", "RAG > Body")));
        DATABASE.sql("REINDEX INDEX public.idx_chunk_bge_m3_content_bm25");
        assertEquals(CONTENT_CHUNK_ID, queryService.searchContent(
                List.of(KB_ID), "content", "architecture.md", "md", "rag >", 1
        ).get(0).getChunkId(), "删除并重建索引后应恢复当前投影，而非陈旧命中");
    }

    private void prepareSchemaAndFixture() throws Exception {
        DATABASE.sql("DROP TABLE IF EXISTS public.chunk_bge_m3 CASCADE");
        DATABASE.sql("DROP TABLE IF EXISTS public.rag_bm25_token_dictionary CASCADE");
        DATABASE.sql("CREATE TABLE public.chunk_bge_m3 (id UUID PRIMARY KEY, kb_id UUID NOT NULL, doc_id UUID NOT NULL, content TEXT, metadata JSONB NOT NULL DEFAULT '{}'::jsonb, created_at TIMESTAMP, updated_at TIMESTAMP)");
        DATABASE.restore(java.nio.file.Files.readString(java.nio.file.Path.of(
                "..", "sql", "knowledge-base", "2026-08-22-add-vchord-bm25-index.sql"
        )));
        DATABASE.sql("INSERT INTO public.rag_bm25_token_dictionary (token) VALUES ('title'), ('content'), ('outside')");
        DATABASE.sql("""
                INSERT INTO public.chunk_bge_m3
                (id, kb_id, doc_id, content, metadata, title_bm25_vector, content_bm25_vector, bm25_index_version, created_at, updated_at)
                VALUES
                ('%s', '%s', '%s', 'title body', %s, '{1:1}'::bm25_catalog.bm25vector, '{3:1}'::bm25_catalog.bm25vector, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                ('%s', '%s', '%s', 'content body', %s, '{3:1}'::bm25_catalog.bm25vector, '{2:1}'::bm25_catalog.bm25vector, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                ('%s', '%s', '%s', 'outside body', %s, '{1:9}'::bm25_catalog.bm25vector, '{2:9}'::bm25_catalog.bm25vector, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                ('%s', '%s', '%s', 'wildcard gold', %s, '{1:1}'::bm25_catalog.bm25vector, '{2:1}'::bm25_catalog.bm25vector, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
                ('%s', '%s', '%s', 'wildcard decoy', %s, '{1:9}'::bm25_catalog.bm25vector, '{2:9}'::bm25_catalog.bm25vector, 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.formatted(
                TITLE_CHUNK_ID, KB_ID, DOCUMENT_ID, metadata("architecture.md", "md", "RAG > Title"),
                CONTENT_CHUNK_ID, KB_ID, DOCUMENT_ID, metadata("architecture.md", "md", "RAG > Body"),
                OUTSIDE_CHUNK_ID, "00000000-0000-0000-0000-00000000c212", DOCUMENT_ID, metadata("other.md", "txt", "Other"),
                WILDCARD_GOLD_CHUNK_ID, KB_ID, DOCUMENT_ID, metadata("architecture.md", "md", "RAG_100% > Title"),
                WILDCARD_DECOY_CHUNK_ID, KB_ID, DOCUMENT_ID, metadata("architecture.md", "md", "RAGX100Y > Decoy")
        ));
        DATABASE.sql("CREATE ROLE " + TEST_ROLE + " LOGIN PASSWORD '" + TEST_PASSWORD + "'");
        DATABASE.sql("GRANT CONNECT ON DATABASE g2vchord TO " + TEST_ROLE);
        DATABASE.sql("GRANT USAGE ON SCHEMA public, bm25_catalog TO " + TEST_ROLE);
        DATABASE.sql("GRANT SELECT ON public.chunk_bge_m3, public.rag_bm25_token_dictionary TO " + TEST_ROLE);
    }

    private String metadata(String sourceName, String sourceType, String contentPath) {
        return "jsonb_build_object('sourceName', '%s', 'sourceType', '%s', 'contentPath', '%s')"
                .formatted(sourceName, sourceType, contentPath);
    }

    private void assertSameTransactionConnection(RecordingDataSource dataSource) {
        assertTrue(dataSource.connectionIdsFor(SEARCH_PATH).size() == 1);
        assertEquals(
                dataSource.connectionIdsFor(SEARCH_PATH),
                dataSource.connectionIdsFor("to_bm25query"),
                "SET LOCAL 与原生 BM25 查询必须使用同一连接"
        );
    }

    @org.springframework.context.annotation.Configuration
    @EnableTransactionManagement
    static class QueryServiceL2Configuration {
        @Bean
        RecordingDataSource dataSource() {
            DriverManagerDataSource delegate = new DriverManagerDataSource(
                    "jdbc:postgresql://127.0.0.1:55436/g2vchord", TEST_ROLE, TEST_PASSWORD
            );
            delegate.setDriverClassName("org.postgresql.Driver");
            return new RecordingDataSource(delegate);
        }

        @Bean
        PlatformTransactionManager transactionManager(DataSource dataSource) {
            return new DataSourceTransactionManager(dataSource);
        }

        @Bean
        JdbcTemplate jdbcTemplate(DataSource dataSource) {
            return new JdbcTemplate(dataSource);
        }

        @Bean
        SqlSessionFactory sqlSessionFactory(DataSource dataSource) throws Exception {
            Configuration configuration = new Configuration(new Environment(
                    "g2-vchord-query-l2", new SpringManagedTransactionFactory(), dataSource
            ));
            configuration.getTypeHandlerRegistry().register(float[].class, PgVectorTypeHandler.class);
            parseMapper(configuration, "mapper/Bm25TokenDictionaryMapper.xml");
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
        ChunkBgeM3Mapper chunkBgeM3Mapper(SqlSessionTemplate sqlSessionTemplate) {
            return sqlSessionTemplate.getMapper(ChunkBgeM3Mapper.class);
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

    private static final class RecordingDataSource extends AbstractDataSource {
        private final DataSource delegate;
        private final AtomicInteger nextConnectionId = new AtomicInteger();
        private final List<Execution> executions = new ArrayList<>();

        private RecordingDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection getConnection() throws java.sql.SQLException {
            return wrapConnection(delegate.getConnection(), nextConnectionId.incrementAndGet());
        }

        @Override
        public Connection getConnection(String username, String password) throws java.sql.SQLException {
            return wrapConnection(delegate.getConnection(username, password), nextConnectionId.incrementAndGet());
        }

        void clearExecutions() {
            executions.clear();
        }

        List<String> executedSql() {
            return executions.stream().map(Execution::sql).toList();
        }

        Set<Integer> connectionIdsFor(String fragment) {
            String normalizedFragment = fragment.toLowerCase();
            Set<Integer> connectionIds = new LinkedHashSet<>();
            for (Execution execution : executions) {
                if (execution.sql().contains(normalizedFragment)) {
                    connectionIds.add(execution.connectionId());
                }
            }
            return connectionIds;
        }

        private Connection wrapConnection(Connection connection, int connectionId) {
            return (Connection) Proxy.newProxyInstance(
                    Connection.class.getClassLoader(),
                    new Class[]{Connection.class},
                    (proxy, method, arguments) -> {
                        Object result = invoke(connection, method, arguments);
                        if ("prepareStatement".equals(method.getName()) && arguments != null && arguments.length > 0) {
                            record(connectionId, String.valueOf(arguments[0]));
                        }
                        if (result instanceof PreparedStatement preparedStatement) {
                            return wrapStatement(preparedStatement, connectionId, PreparedStatement.class);
                        }
                        if (result instanceof Statement statement) {
                            return wrapStatement(statement, connectionId, Statement.class);
                        }
                        return result;
                    }
            );
        }

        private Object wrapStatement(Statement statement, int connectionId, Class<?> statementType) {
            return Proxy.newProxyInstance(
                    Statement.class.getClassLoader(),
                    new Class[]{statementType},
                    (proxy, method, arguments) -> {
                        if (method.getName().startsWith("execute") && arguments != null && arguments.length > 0
                                && arguments[0] instanceof String sql) {
                            record(connectionId, sql);
                        }
                        return invoke(statement, method, arguments);
                    }
            );
        }

        private void record(int connectionId, String sql) {
            executions.add(new Execution(connectionId, sql.toLowerCase()));
        }

        private Object invoke(Object target, java.lang.reflect.Method method, Object[] arguments) throws Throwable {
            try {
                return method.invoke(target, arguments);
            } catch (InvocationTargetException exception) {
                throw exception.getCause();
            }
        }

        private record Execution(int connectionId, String sql) {
        }
    }
}
