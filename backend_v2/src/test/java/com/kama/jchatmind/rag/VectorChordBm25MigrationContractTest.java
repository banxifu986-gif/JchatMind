package com.kama.jchatmind.rag;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class VectorChordBm25MigrationContractTest {

    private static final Path MIGRATION = Path.of(
            "..", "sql", "knowledge-base", "2026-08-22-add-vchord-bm25-index.sql"
    );

    @Test
    void shouldDefineStableTokenDictionaryAndVersionedChunkProjection() throws Exception {
        assertThat(Files.exists(MIGRATION)).isTrue();

        String migration = Files.readString(MIGRATION)
                .toLowerCase()
                .replaceAll("\\s+", " ");

        assertThat(migration)
                .contains("create extension if not exists vchord_bm25")
                .contains("create table rag_bm25_token_dictionary")
                .contains("token_id bigint generated always as identity primary key")
                .contains("token text not null unique")
                .contains("token <> ''")
                .contains("alter table chunk_bge_m3")
                .contains("title_bm25_vector bm25_catalog.bm25vector")
                .contains("content_bm25_vector bm25_catalog.bm25vector")
                .contains("bm25_index_version integer")
                .contains("bm25_index_version is null or bm25_index_version > 0")
                .contains("title_bm25_vector is null and content_bm25_vector is null and bm25_index_version is null")
                .contains("title_bm25_vector is not null and content_bm25_vector is not null and bm25_index_version > 0")
                .contains("create index idx_chunk_bge_m3_title_bm25")
                .contains("using bm25 (title_bm25_vector bm25_catalog.bm25_ops)")
                .contains("create index idx_chunk_bge_m3_content_bm25")
                .contains("using bm25 (content_bm25_vector bm25_catalog.bm25_ops)");
    }
}
