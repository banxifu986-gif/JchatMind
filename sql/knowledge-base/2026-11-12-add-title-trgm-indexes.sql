BEGIN;

CREATE EXTENSION pg_trgm;

CREATE INDEX idx_chunk_bge_m3_title_trgm
    ON chunk_bge_m3 USING gin (lower(regexp_replace(trim(COALESCE(metadata->>'retrievableTitle', metadata->>'title')), '\s+', ' ', 'g')) gin_trgm_ops);
CREATE INDEX idx_chunk_bge_m3_content_path_trgm
    ON chunk_bge_m3 USING gin (lower(regexp_replace(trim(COALESCE(metadata->>'contentPath', '')), '\s+', ' ', 'g')) gin_trgm_ops);

COMMIT;
