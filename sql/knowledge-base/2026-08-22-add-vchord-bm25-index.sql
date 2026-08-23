BEGIN;

CREATE EXTENSION IF NOT EXISTS vchord_bm25;

-- 词典只保存原生 BM25 投影的稳定 token ID，不承载独立业务 chunk 数据。
CREATE TABLE rag_bm25_token_dictionary (
    token_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    token    TEXT NOT NULL UNIQUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_rag_bm25_token_dictionary_token_not_blank CHECK (token <> '')
);

ALTER TABLE chunk_bge_m3
    ADD COLUMN title_bm25_vector bm25_catalog.bm25vector,
    ADD COLUMN content_bm25_vector bm25_catalog.bm25vector,
    ADD COLUMN bm25_index_version INTEGER,
    ADD CONSTRAINT chk_chunk_bge_m3_bm25_index_version
        CHECK (bm25_index_version IS NULL OR bm25_index_version > 0),
    ADD CONSTRAINT chk_chunk_bge_m3_bm25_projection_complete
        CHECK (
            (title_bm25_vector IS NULL AND content_bm25_vector IS NULL AND bm25_index_version IS NULL)
            OR
            (title_bm25_vector IS NOT NULL AND content_bm25_vector IS NOT NULL AND bm25_index_version > 0)
        );

CREATE INDEX idx_chunk_bge_m3_title_bm25
    ON chunk_bge_m3
    USING bm25 (title_bm25_vector bm25_catalog.bm25_ops);

CREATE INDEX idx_chunk_bge_m3_content_bm25
    ON chunk_bge_m3
    USING bm25 (content_bm25_vector bm25_catalog.bm25_ops);

COMMIT;
