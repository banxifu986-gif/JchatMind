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

@SpringJUnitConfig(G2PdfAssetTransactionRuntimeL2TestConfig.class)
@EnabledIfSystemProperty(named = "g2.pdf.asset.transaction.l2", matches = "true")
class G2PdfAssetTransactionRuntimeL2Test {

    private static final String ISOLATED_DATABASE = "g2pdfassettx";
    private static final String KB_ID = "00000000-0000-0000-0000-00000000f101";
    private static final String DOCUMENT_ID = "00000000-0000-0000-0000-00000000f111";
    private static final String OLD_CHUNK_ID = "00000000-0000-0000-0000-00000000f121";
    private static final String OLD_ASSET_ID = "00000000-0000-0000-0000-00000000f131";
    private static final Path FIXTURE_PDF = Path.of("target", "g2-pdf-asset-transaction", "fixture.pdf")
            .toAbsolutePath();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DefaultIngestionTaskProcessor processor;

    @BeforeEach
    void setUp() throws Exception {
        assertIsolatedDatabase();
        dropSchema();
        Files.createDirectories(FIXTURE_PDF.getParent());
        Files.write(FIXTURE_PDF, new byte[]{'%', 'P', 'D', 'F'});

        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pgcrypto");
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbcTemplate.execute("CREATE TABLE document (id UUID PRIMARY KEY, kb_id UUID NOT NULL, filename VARCHAR(255), filetype VARCHAR(32), size BIGINT, metadata JSONB NOT NULL, created_at TIMESTAMP, updated_at TIMESTAMP)");
        jdbcTemplate.execute("CREATE TABLE chunk_bge_m3 (id UUID PRIMARY KEY DEFAULT gen_random_uuid(), kb_id UUID NOT NULL, doc_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE, content TEXT, metadata JSONB, embedding vector(1024), created_at TIMESTAMP, updated_at TIMESTAMP)");
        jdbcTemplate.execute(Files.readString(Path.of("..", "sql", "ingestion", "2026-08-22-create-document-asset.sql")));
        jdbcTemplate.update("INSERT INTO document VALUES (?::uuid, ?::uuid, ?, ?, ?, ?::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                DOCUMENT_ID, KB_ID, "rollback.pdf", "pdf", 4, "{\"filePath\":\"isolated/rollback.pdf\"}");
        jdbcTemplate.update("INSERT INTO chunk_bge_m3 (id, kb_id, doc_id, content, metadata, created_at, updated_at) VALUES (?::uuid, ?::uuid, ?::uuid, ?, '{}'::jsonb, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                OLD_CHUNK_ID, KB_ID, DOCUMENT_ID, "old PDF page text");
        jdbcTemplate.update("INSERT INTO document_asset (asset_id, document_id, asset_type, asset_key, page_number, locator, content_hash, parser_version, status, created_at, updated_at) VALUES (?::uuid, ?::uuid, 'PDF_PAGE_TEXT', 'page-1', 1, '{\"pageNumber\":1}'::jsonb, ?, 'pdf-text-v1', 'READY', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)",
                OLD_ASSET_ID, DOCUMENT_ID, "a".repeat(64));
        jdbcTemplate.update("INSERT INTO document_asset_chunk (asset_id, chunk_id, asset_document_id, chunk_document_id) VALUES (?::uuid, ?::uuid, ?::uuid, ?::uuid)",
                OLD_ASSET_ID, OLD_CHUNK_ID, DOCUMENT_ID, DOCUMENT_ID);
        jdbcTemplate.execute("CREATE FUNCTION reject_new_pdf_page_assets() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'controlled document asset failure'; END; $$");
        jdbcTemplate.execute("CREATE TRIGGER reject_new_pdf_page_assets BEFORE INSERT ON document_asset FOR EACH ROW EXECUTE FUNCTION reject_new_pdf_page_assets()");
    }

    @AfterEach
    void tearDown() {
        assertIsolatedDatabase();
        dropSchema();
    }

    @Test
    void restoresPriorPdfChunksAssetsAndRelationsWhenNewAssetWriteFails() {
        assertThat(AopUtils.isAopProxy(processor)).isTrue();

        assertThatThrownBy(() -> processor.process(IngestionTask.builder()
                .id("00000000-0000-0000-0000-00000000f141")
                .kbId(KB_ID)
                .documentId(DOCUMENT_ID)
                .build()))
                .hasStackTraceContaining("controlled document asset failure");

        assertThat(count("SELECT COUNT(*) FROM chunk_bge_m3 WHERE id = '" + OLD_CHUNK_ID + "'::uuid")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM document_asset WHERE asset_id = '" + OLD_ASSET_ID + "'::uuid")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM document_asset_chunk WHERE asset_id = '" + OLD_ASSET_ID + "'::uuid AND chunk_id = '" + OLD_CHUNK_ID + "'::uuid")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM chunk_bge_m3")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM document_asset")).isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM document_asset_chunk")).isEqualTo(1);
    }

    private void assertIsolatedDatabase() {
        assertThat(jdbcTemplate.queryForObject("SELECT current_database()", String.class))
                .isEqualTo(ISOLATED_DATABASE);
    }

    private int count(String sql) {
        return jdbcTemplate.queryForObject(sql, Integer.class);
    }

    private void dropSchema() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS document_asset_chunk, document_asset, chunk_bge_m3, document CASCADE");
        jdbcTemplate.execute("DROP FUNCTION IF EXISTS reject_new_pdf_page_assets() CASCADE");
    }
}
