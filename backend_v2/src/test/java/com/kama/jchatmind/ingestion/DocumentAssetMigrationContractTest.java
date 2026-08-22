package com.kama.jchatmind.ingestion;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentAssetMigrationContractTest {

    private static final Path MIGRATION = Path.of(
            "..", "sql", "ingestion", "2026-08-22-create-document-asset.sql"
    );

    @Test
    void shouldDefineTraceableDocumentAssetsAndChunkRelations() throws Exception {
        assertThat(Files.exists(MIGRATION)).isTrue();

        String migration = Files.readString(MIGRATION).toLowerCase();

        assertThat(migration)
                .contains("create table document_asset")
                .contains("asset_id")
                .contains("document_id")
                .contains("asset_type")
                .contains("asset_key")
                .contains("page_number")
                .contains("locator")
                .contains("content_hash")
                .contains("content_hash ~ '^[0-9a-f]{64}$'")
                .contains("parser_version")
                .contains("status")
                .contains("foreign key (document_id) references document(id) on delete cascade")
                .contains("unique (document_id, asset_type, asset_key)")
                .contains("pdf_page_text")
                .contains("image")
                .contains("table")
                .contains("formula")
                .contains("pending")
                .contains("ready")
                .contains("failed")
                .contains("create table document_asset_chunk")
                .contains("asset_document_id")
                .contains("chunk_document_id")
                .contains("unique (asset_id, document_id)")
                .contains("unique (id, doc_id)")
                .contains("foreign key (asset_id, asset_document_id)")
                .contains("references document_asset(asset_id, document_id) on delete cascade")
                .contains("foreign key (chunk_id, chunk_document_id)")
                .contains("references chunk_bge_m3(id, doc_id) on delete cascade")
                .contains("asset_document_id = chunk_document_id")
                .contains("primary key (asset_id, chunk_id)")
                .contains("create index idx_document_asset_document_id")
                .contains("create index idx_document_asset_chunk_chunk_id");
    }
}
