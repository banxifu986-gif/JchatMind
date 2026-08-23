package com.kama.jchatmind.ingestion;

import com.kama.jchatmind.model.entity.IngestionTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringJUnitConfig(G2VchordBm25ProjectionTransactionRuntimeL2TestConfig.class)
@EnabledIfSystemProperty(named = "g2.vchord.ingestion.transaction.l2", matches = "true")
class G2VchordBm25ProjectionTransactionRuntimeL2Test {

    private static final String ISOLATED_DATABASE = "g2vchord";
    private static final String KB_ID = "00000000-0000-0000-0000-00000000d101";
    private static final String DOCUMENT_ID = "00000000-0000-0000-0000-00000000d111";
    private static final String OLD_CHUNK_ID = "00000000-0000-0000-0000-00000000d121";

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DefaultIngestionTaskProcessor processor;

    @BeforeEach
    void setUp() throws Exception {
        assertIsolatedDatabase();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT extversion FROM pg_extension WHERE extname = 'vchord_bm25'", String.class
        )).isEqualTo("0.3.0");
        dropSchema();
        Path fixture = G2VchordBm25ProjectionTransactionRuntimeL2TestConfig.FIXTURE;
        Files.createDirectories(fixture.getParent());
        Files.writeString(fixture, "# BM25 事务\nstable token rollback");

        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
        jdbcTemplate.execute("CREATE DOMAIN vector AS TEXT");
        jdbcTemplate.execute("CREATE TABLE document (id UUID PRIMARY KEY, kb_id UUID NOT NULL, filename VARCHAR(255), filetype VARCHAR(32), size BIGINT, metadata JSONB NOT NULL, created_at TIMESTAMP, updated_at TIMESTAMP)");
        jdbcTemplate.execute("CREATE TABLE chunk_bge_m3 (id UUID PRIMARY KEY DEFAULT gen_random_uuid(), kb_id UUID NOT NULL, doc_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE, content TEXT, metadata JSONB NOT NULL DEFAULT '{}'::jsonb, embedding vector, created_at TIMESTAMP, updated_at TIMESTAMP)");
        jdbcTemplate.execute(Files.readString(Path.of("..", "sql", "knowledge-base", "2026-08-22-add-vchord-bm25-index.sql")));
        jdbcTemplate.update("INSERT INTO document VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                DOCUMENT_ID, KB_ID, "rollback.md", "md", 32, "{\"filePath\":\"isolated/rollback.md\"}");
        jdbcTemplate.update("INSERT INTO chunk_bge_m3 (id, kb_id, doc_id, content, metadata, created_at, updated_at) VALUES (?::uuid, ?::uuid, ?::uuid, ?, '{}'::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                OLD_CHUNK_ID, KB_ID, DOCUMENT_ID, "old chunk");
        jdbcTemplate.execute("CREATE FUNCTION reject_new_bm25_chunk() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled BM25 chunk failure'; END; $$");
        jdbcTemplate.execute("CREATE TRIGGER reject_new_bm25_chunk BEFORE INSERT ON chunk_bge_m3 FOR EACH ROW EXECUTE FUNCTION reject_new_bm25_chunk()");
    }

    @AfterEach
    void tearDown() {
        assertIsolatedDatabase();
        dropSchema();
    }

    @Test
    void rollsBackRealProjectionDictionaryAndChunkWhenChunkWriteFails() {
        assertThat(AopUtils.isAopProxy(processor)).isTrue();

        assertThatThrownBy(() -> processor.process(IngestionTask.builder()
                .id("00000000-0000-0000-0000-00000000d141")
                .kbId(KB_ID)
                .documentId(DOCUMENT_ID)
                .build()))
                .hasStackTraceContaining("controlled BM25 chunk failure");

        assertThat(count("SELECT COUNT(*) FROM chunk_bge_m3 WHERE id = '" + OLD_CHUNK_ID + "'::uuid")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM chunk_bge_m3")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM rag_bm25_token_dictionary")).isZero();
        assertThat(count("SELECT COUNT(*) FROM chunk_bge_m3 WHERE bm25_index_version IS NOT NULL")).isZero();
    }

    private void assertIsolatedDatabase() {
        assertThat(jdbcTemplate.queryForObject("SELECT current_database()", String.class))
                .isEqualTo(ISOLATED_DATABASE);
    }

    private int count(String sql) {
        return jdbcTemplate.queryForObject(sql, Integer.class);
    }

    private void dropSchema() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS rag_bm25_token_dictionary CASCADE");
        jdbcTemplate.execute("DROP TABLE IF EXISTS chunk_bge_m3 CASCADE");
        jdbcTemplate.execute("DROP TABLE IF EXISTS document CASCADE");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS reject_new_bm25_chunk() CASCADE");
        jdbcTemplate.execute("DROP DOMAIN IF EXISTS vector");
        jdbcTemplate.execute("DROP EXTENSION IF EXISTS pgcrypto CASCADE");
    }
}
