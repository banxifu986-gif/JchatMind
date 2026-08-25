package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.typehandler.PgVectorTypeHandler;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

@EnabledIfSystemProperty(named = "g2.table.asset.mapper.l2", matches = "true")
class G2MarkdownTableAssetCandidateMapperRuntimeL2Test {

    private static final String ISOLATED_DATABASE = "g2pdfassettx";
    private static final String JDBC_URL = "jdbc:postgresql://127.0.0.1:55434/g2pdfassettx";
    private static final String KB_ID = "00000000-0000-0000-0000-00000000d101";
    private static final String OUTSIDE_KB_ID = "00000000-0000-0000-0000-00000000d102";
    private static final String DOCUMENT_ID = "00000000-0000-0000-0000-00000000d111";
    private static final String GOLD_CHUNK_ID = "00000000-0000-0000-0000-00000000d121";
    private static final String OUTSIDE_PATH_CHUNK_ID = "00000000-0000-0000-0000-00000000d122";
    private static final String OUTSIDE_KB_CHUNK_ID = "00000000-0000-0000-0000-00000000d123";
    private static final String TABLE_ONE_ASSET_ID = "00000000-0000-0000-0000-00000000d131";
    private static final String TABLE_TWO_ASSET_ID = "00000000-0000-0000-0000-00000000d132";
    private static final String HARD_CONTENT_PATH_PREFIX = "rag_100%_api > sources\\api";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private DataSource dataSource;
    private JdbcTemplate jdbcTemplate;
    private SqlSession sqlSession;
    private ChunkBgeM3Mapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = isolatedDataSource();
        jdbcTemplate = new JdbcTemplate(dataSource);
        assertIsolatedDatabase();
        dropSchema();
        prepareSchemaAndFixture();
        mapper = createMapper();
    }

    @AfterEach
    void tearDown() {
        if (sqlSession != null) {
            sqlSession.close();
        }
        assertIsolatedDatabase();
        dropSchema();
    }

    @Test
    void appliesHardKbAndLiteralContentPathFiltersBeforeTableCandidateLimit() {
        List<RagRetrievalResult> results = mapper.similaritySearchMarkdownTableAssets(
                List.of(KB_ID), "[1,0,0]", "architecture.md", "md", HARD_CONTENT_PATH_PREFIX, 1
        );

        assertEquals(List.of(GOLD_CHUNK_ID), results.stream().map(RagRetrievalResult::getChunkId).toList());
        assertEquals(List.of(TABLE_ONE_ASSET_ID), assetIds(results));
    }

    @Test
    void projectsEachCurrentReadyTableAssetMetadataForTheSameChunk() {
        List<RagRetrievalResult> results = mapper.similaritySearchMarkdownTableAssets(
                List.of(KB_ID), "[1,0,0]", "architecture.md", "md", HARD_CONTENT_PATH_PREFIX, 2
        );

        assertEquals(List.of(GOLD_CHUNK_ID, GOLD_CHUNK_ID), results.stream()
                .map(RagRetrievalResult::getChunkId)
                .toList());
        assertEquals(List.of(TABLE_ONE_ASSET_ID, TABLE_TWO_ASSET_ID), assetIds(results));
        assertEquals(List.of(5, 10), assetStartLines(results));
        assertEquals(List.of("TABLE", "TABLE"), assetTypes(results));
    }

    private ChunkBgeM3Mapper createMapper() throws Exception {
        Configuration configuration = new Configuration(new Environment(
                "g2-table-asset-mapper-l2", new JdbcTransactionFactory(), dataSource
        ));
        configuration.getTypeHandlerRegistry().register(float[].class, PgVectorTypeHandler.class);
        try (InputStream mapperXml = Resources.getResourceAsStream("mapper/ChunkBgeM3Mapper.xml")) {
            new XMLMapperBuilder(mapperXml, configuration, "mapper/ChunkBgeM3Mapper.xml",
                    configuration.getSqlFragments()).parse();
        }
        SqlSessionFactory factory = new SqlSessionFactoryBuilder().build(configuration);
        sqlSession = factory.openSession();
        return sqlSession.getMapper(ChunkBgeM3Mapper.class);
    }

    private void prepareSchemaAndFixture() throws Exception {
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbcTemplate.execute("""
                CREATE TABLE document (
                    id UUID PRIMARY KEY,
                    kb_id UUID NOT NULL,
                    filename VARCHAR(255),
                    filetype VARCHAR(32),
                    size BIGINT,
                    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
                    created_at TIMESTAMP,
                    updated_at TIMESTAMP
                )
                """);
        jdbcTemplate.execute("""
                CREATE TABLE chunk_bge_m3 (
                    id UUID PRIMARY KEY,
                    kb_id UUID NOT NULL,
                    doc_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE,
                    content TEXT,
                    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
                    embedding vector(3),
                    created_at TIMESTAMP,
                    updated_at TIMESTAMP
                )
                """);
        jdbcTemplate.execute(Files.readString(Path.of(
                "..", "sql", "ingestion", "2026-08-22-create-document-asset.sql"
        )));
        jdbcTemplate.execute("""
                INSERT INTO document (id, kb_id, filename, filetype, size, metadata, created_at, updated_at)
                VALUES ('%s'::uuid, '%s'::uuid, 'architecture.md', 'md', 0, '{}'::jsonb,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.formatted(DOCUMENT_ID, KB_ID));
        insertChunk(GOLD_CHUNK_ID, KB_ID, " RAG_100%_API   > Sources\\API > Table ", "[0.7,0.7,0]");
        insertChunk(OUTSIDE_PATH_CHUNK_ID, KB_ID, "RAGX100YAAPI > SourcesXAPI > Table", "[1,0,0]");
        insertChunk(OUTSIDE_KB_CHUNK_ID, OUTSIDE_KB_ID, "RAG_100%_API > Sources\\API > Table", "[1,0,0]");

        insertAsset(TABLE_ONE_ASSET_ID, GOLD_CHUNK_ID, "table-1", "TABLE", "READY", "{\"startLine\":5,\"endLine\":8}");
        insertAsset(TABLE_TWO_ASSET_ID, GOLD_CHUNK_ID, "table-2", "TABLE", "READY", "{\"startLine\":10,\"endLine\":13}");
        insertAsset("00000000-0000-0000-0000-00000000d133", GOLD_CHUNK_ID, "page-1", "PDF_PAGE_TEXT", "READY", "{\"pageNumber\":1}");
        insertAsset("00000000-0000-0000-0000-00000000d134", GOLD_CHUNK_ID, "table-pending", "TABLE", "PENDING", "{\"startLine\":15,\"endLine\":18}");
        insertAsset("00000000-0000-0000-0000-00000000d135", OUTSIDE_PATH_CHUNK_ID, "table-decoy-path", "TABLE", "READY", "{\"startLine\":1,\"endLine\":3}");
        insertAsset("00000000-0000-0000-0000-00000000d136", OUTSIDE_KB_CHUNK_ID, "table-decoy-kb", "TABLE", "READY", "{\"startLine\":1,\"endLine\":3}");
    }

    private void insertChunk(String chunkId, String kbId, String contentPath, String embedding) {
        jdbcTemplate.execute("""
                INSERT INTO chunk_bge_m3 (id, kb_id, doc_id, content, metadata, embedding, created_at, updated_at)
                VALUES ('%s'::uuid, '%s'::uuid, '%s'::uuid, 'table chunk',
                        jsonb_build_object('sourceName', 'architecture.md', 'sourceType', 'md', 'contentPath', '%s'),
                        '%s'::vector, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.formatted(chunkId, kbId, DOCUMENT_ID, contentPath, embedding));
    }

    private void insertAsset(String assetId, String chunkId, String assetKey, String assetType, String status, String locator) {
        jdbcTemplate.execute("""
                INSERT INTO document_asset
                (asset_id, document_id, asset_type, asset_key, page_number, locator, content_hash, parser_version, status, created_at, updated_at)
                VALUES ('%s'::uuid, '%s'::uuid, '%s', '%s', NULL, '%s'::jsonb, '%s', 'mapper-l2', '%s', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """.formatted(assetId, DOCUMENT_ID, assetType, assetKey, locator, assetId.replace("-", "").substring(0, 32).repeat(2), status));
        jdbcTemplate.execute("""
                INSERT INTO document_asset_chunk (asset_id, chunk_id, asset_document_id, chunk_document_id)
                VALUES ('%s'::uuid, '%s'::uuid, '%s'::uuid, '%s'::uuid)
                """.formatted(assetId, chunkId, DOCUMENT_ID, DOCUMENT_ID));
    }

    private List<String> assetIds(List<RagRetrievalResult> results) {
        List<String> ids = new ArrayList<>();
        for (RagRetrievalResult result : results) {
            ids.add(asset(result).path("id").asText());
        }
        return ids;
    }

    private List<Integer> assetStartLines(List<RagRetrievalResult> results) {
        List<Integer> startLines = new ArrayList<>();
        for (RagRetrievalResult result : results) {
            startLines.add(asset(result).path("locator").path("startLine").asInt());
        }
        return startLines;
    }

    private List<String> assetTypes(List<RagRetrievalResult> results) {
        List<String> types = new ArrayList<>();
        for (RagRetrievalResult result : results) {
            types.add(asset(result).path("type").asText());
        }
        return types;
    }

    private JsonNode asset(RagRetrievalResult result) {
        try {
            return OBJECT_MAPPER.readTree(result.getMetadata()).path("asset");
        } catch (Exception exception) {
            throw new AssertionError("TABLE 候选 metadata 必须为包含 asset 的 JSON", exception);
        }
    }

    private void dropSchema() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS document_asset_chunk, document_asset, chunk_bge_m3, document CASCADE");
    }

    private DataSource isolatedDataSource() {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.postgresql.Driver");
        source.setUrl(JDBC_URL);
        source.setUsername("postgres");
        source.setPassword(requiredSystemProperty("g2.pdf.asset.transaction.pg.password"));
        return source;
    }

    private void assertIsolatedDatabase() {
        assertEquals(ISOLATED_DATABASE, jdbcTemplate.queryForObject("SELECT current_database()", String.class));
    }

    private String requiredSystemProperty(String name) {
        String value = System.getProperty(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("缺少测试属性: " + name);
        }
        return value;
    }
}
