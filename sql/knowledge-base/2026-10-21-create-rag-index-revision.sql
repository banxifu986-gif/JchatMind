BEGIN;

CREATE TABLE rag_kb_index_revision (
    kb_id TEXT PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_rag_kb_index_revision_positive CHECK (revision > 0)
);

INSERT INTO rag_kb_index_revision (kb_id, revision)
SELECT DISTINCT kb_id::text, 1
FROM chunk_bge_m3
WHERE kb_id IS NOT NULL
ON CONFLICT (kb_id) DO NOTHING;

CREATE FUNCTION bump_rag_kb_index_revision_after_insert()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO rag_kb_index_revision (kb_id, revision)
    SELECT DISTINCT kb_id::text, 1
    FROM new_rows
    WHERE kb_id IS NOT NULL
      AND EXISTS (
          SELECT 1 FROM knowledge_base WHERE knowledge_base.id = new_rows.kb_id
      )
    ON CONFLICT (kb_id) DO UPDATE
    SET revision = rag_kb_index_revision.revision + 1,
        updated_at = CURRENT_TIMESTAMP;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_chunk_bge_m3_rag_index_revision_insert
    AFTER INSERT ON chunk_bge_m3
    REFERENCING NEW TABLE AS new_rows
    FOR EACH STATEMENT
    EXECUTE FUNCTION bump_rag_kb_index_revision_after_insert();

CREATE FUNCTION bump_rag_kb_index_revision_after_update()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO rag_kb_index_revision (kb_id, revision)
    SELECT changed.kb_id, 1
    FROM (
        SELECT kb_id::text AS kb_id FROM old_rows WHERE kb_id IS NOT NULL
        UNION
        SELECT kb_id::text AS kb_id FROM new_rows WHERE kb_id IS NOT NULL
    ) AS changed
    WHERE EXISTS (
        SELECT 1 FROM knowledge_base WHERE knowledge_base.id::text = changed.kb_id
    )
    ON CONFLICT (kb_id) DO UPDATE
    SET revision = rag_kb_index_revision.revision + 1,
        updated_at = CURRENT_TIMESTAMP;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_chunk_bge_m3_rag_index_revision_update
    AFTER UPDATE ON chunk_bge_m3
    REFERENCING OLD TABLE AS old_rows NEW TABLE AS new_rows
    FOR EACH STATEMENT
    EXECUTE FUNCTION bump_rag_kb_index_revision_after_update();

CREATE FUNCTION bump_rag_kb_index_revision_after_delete()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO rag_kb_index_revision (kb_id, revision)
    SELECT DISTINCT kb_id::text, 1
    FROM old_rows
    WHERE kb_id IS NOT NULL
      AND EXISTS (
          SELECT 1 FROM knowledge_base WHERE knowledge_base.id = old_rows.kb_id
      )
    ON CONFLICT (kb_id) DO UPDATE
    SET revision = rag_kb_index_revision.revision + 1,
        updated_at = CURRENT_TIMESTAMP;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_chunk_bge_m3_rag_index_revision_delete
    AFTER DELETE ON chunk_bge_m3
    REFERENCING OLD TABLE AS old_rows
    FOR EACH STATEMENT
    EXECUTE FUNCTION bump_rag_kb_index_revision_after_delete();

CREATE FUNCTION bump_rag_kb_index_revision_from_document_asset()
RETURNS TRIGGER AS $$
DECLARE
    affected_document_ids UUID[];
BEGIN
    IF TG_OP = 'INSERT' THEN
        affected_document_ids := ARRAY[NEW.document_id];
    ELSIF TG_OP = 'DELETE' THEN
        affected_document_ids := ARRAY[OLD.document_id];
    ELSE
        affected_document_ids := ARRAY[OLD.document_id, NEW.document_id];
    END IF;

    INSERT INTO rag_kb_index_revision (kb_id, revision)
    SELECT DISTINCT document.kb_id::text, 1
    FROM document
    WHERE document.id = ANY(affected_document_ids)
      AND document.kb_id IS NOT NULL
      AND EXISTS (
          SELECT 1 FROM knowledge_base WHERE knowledge_base.id = document.kb_id
      )
    ON CONFLICT (kb_id) DO UPDATE
    SET revision = rag_kb_index_revision.revision + 1,
        updated_at = CURRENT_TIMESTAMP;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_document_asset_rag_index_revision
    AFTER INSERT OR UPDATE OR DELETE ON document_asset
    FOR EACH ROW
    EXECUTE FUNCTION bump_rag_kb_index_revision_from_document_asset();

CREATE FUNCTION bump_rag_kb_index_revision_from_document_asset_chunk()
RETURNS TRIGGER AS $$
DECLARE
    affected_document_ids UUID[];
BEGIN
    IF TG_OP = 'INSERT' THEN
        affected_document_ids := ARRAY[NEW.asset_document_id, NEW.chunk_document_id];
    ELSIF TG_OP = 'DELETE' THEN
        affected_document_ids := ARRAY[OLD.asset_document_id, OLD.chunk_document_id];
    ELSE
        affected_document_ids := ARRAY[
            OLD.asset_document_id,
            OLD.chunk_document_id,
            NEW.asset_document_id,
            NEW.chunk_document_id
        ];
    END IF;

    INSERT INTO rag_kb_index_revision (kb_id, revision)
    SELECT DISTINCT document.kb_id::text, 1
    FROM document
    WHERE document.id = ANY(affected_document_ids)
      AND document.kb_id IS NOT NULL
      AND EXISTS (
          SELECT 1 FROM knowledge_base WHERE knowledge_base.id = document.kb_id
      )
    ON CONFLICT (kb_id) DO UPDATE
    SET revision = rag_kb_index_revision.revision + 1,
        updated_at = CURRENT_TIMESTAMP;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_document_asset_chunk_rag_index_revision
    AFTER INSERT OR UPDATE OR DELETE ON document_asset_chunk
    FOR EACH ROW
    EXECUTE FUNCTION bump_rag_kb_index_revision_from_document_asset_chunk();

CREATE FUNCTION bump_rag_kb_index_revision_from_document()
RETURNS TRIGGER AS $$
DECLARE
    affected_kb_ids TEXT[];
BEGIN
    IF TG_OP = 'DELETE' THEN
        affected_kb_ids := ARRAY[OLD.kb_id::text];
    ELSE
        affected_kb_ids := ARRAY[OLD.kb_id::text, NEW.kb_id::text];
    END IF;

    INSERT INTO rag_kb_index_revision (kb_id, revision)
    SELECT DISTINCT affected_kb.value, 1
    FROM unnest(affected_kb_ids) AS affected_kb(value)
    WHERE affected_kb.value IS NOT NULL
      AND EXISTS (
          SELECT 1 FROM knowledge_base WHERE knowledge_base.id::text = affected_kb.value
      )
    ON CONFLICT (kb_id) DO UPDATE
    SET revision = rag_kb_index_revision.revision + 1,
        updated_at = CURRENT_TIMESTAMP;
    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_document_rag_index_revision
    AFTER UPDATE OR DELETE ON document
    FOR EACH ROW
    EXECUTE FUNCTION bump_rag_kb_index_revision_from_document();

CREATE FUNCTION cleanup_rag_kb_index_revision_after_delete()
RETURNS TRIGGER AS $$
BEGIN
    DELETE FROM rag_kb_index_revision WHERE kb_id = OLD.id::text;
    RETURN OLD;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_knowledge_base_rag_index_revision_delete
    AFTER DELETE ON knowledge_base
    FOR EACH ROW
    EXECUTE FUNCTION cleanup_rag_kb_index_revision_after_delete();

COMMIT;
