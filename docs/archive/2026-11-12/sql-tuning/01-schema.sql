-- jchatmind perf sandbox schema
-- Mirrors production tables under test. Does NOT include the 5 candidate
-- indexes (idx_chunk_bge_m3_doc_id / _kb_id / idx_chat_message_session_id /
-- idx_chat_session_user_id / idx_user_memory_user_id) -- those are applied
-- later by 04-apply-indexes.sql to produce the "after" measurement.

BEGIN;

CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS vchord_bm25;

CREATE TABLE knowledge_base (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    owner_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE document (
    id UUID PRIMARY KEY,
    kb_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    filename VARCHAR(1024) NOT NULL,
    filetype VARCHAR(255) NOT NULL,
    size BIGINT NOT NULL DEFAULT 0,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE chunk_bge_m3 (
    id UUID PRIMARY KEY,
    kb_id UUID NOT NULL REFERENCES knowledge_base(id) ON DELETE CASCADE,
    doc_id UUID NOT NULL REFERENCES document(id) ON DELETE CASCADE,
    content TEXT NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    embedding VECTOR(1024) NOT NULL,
    title_bm25_vector bm25_catalog.bm25vector,
    content_bm25_vector bm25_catalog.bm25vector,
    bm25_index_version INTEGER,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX uq_chunk_bge_m3_id_doc ON chunk_bge_m3 (id, doc_id);
CREATE INDEX idx_chunk_bge_m3_embedding_cosine
    ON chunk_bge_m3 USING ivfflat (embedding vector_cosine_ops);
CREATE INDEX idx_chunk_bge_m3_title_bm25
    ON chunk_bge_m3 USING bm25 (title_bm25_vector bm25_catalog.bm25_ops);
CREATE INDEX idx_chunk_bge_m3_content_bm25
    ON chunk_bge_m3 USING bm25 (content_bm25_vector bm25_catalog.bm25_ops);

CREATE TABLE rag_kb_index_revision (
    kb_id TEXT PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 1,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE FUNCTION bump_rag_kb_index_revision_after_insert()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO rag_kb_index_revision (kb_id, revision)
    SELECT DISTINCT kb_id::text, 1
    FROM new_rows
    WHERE kb_id IS NOT NULL
      AND EXISTS (SELECT 1 FROM knowledge_base WHERE knowledge_base.id = new_rows.kb_id)
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

CREATE FUNCTION bump_rag_kb_index_revision_after_delete()
RETURNS TRIGGER AS $$
BEGIN
    INSERT INTO rag_kb_index_revision (kb_id, revision)
    SELECT DISTINCT kb_id::text, 1
    FROM old_rows
    WHERE kb_id IS NOT NULL
      AND EXISTS (SELECT 1 FROM knowledge_base WHERE knowledge_base.id = old_rows.kb_id)
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

CREATE TABLE chat_session (
    id UUID PRIMARY KEY,
    user_id TEXT NOT NULL,
    agent_id UUID,
    title VARCHAR(255),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE chat_message (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL,
    role VARCHAR(32) NOT NULL,
    content TEXT NOT NULL,
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE agent_execution_turn_message (
    id UUID PRIMARY KEY,
    session_id UUID NOT NULL,
    sequence_no BIGINT NOT NULL,
    chat_message_id UUID NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_agent_execution_turn_message_session_sequence
    ON agent_execution_turn_message (session_id, sequence_no, chat_message_id);

CREATE TABLE agent (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE agent_knowledge_base (
    agent_id UUID NOT NULL,
    kb_id UUID NOT NULL,
    bound_by_user_id BIGINT,
    bound_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (agent_id, kb_id)
);

CREATE TABLE user_memory (
    id UUID PRIMARY KEY,
    user_id VARCHAR(64) NOT NULL,
    session_id UUID,
    memory_type VARCHAR(64) NOT NULL,
    content TEXT NOT NULL,
    importance REAL,
    evidence_message_id UUID,
    evidence_text TEXT,
    superseded_by_memory_id UUID,
    expires_at TIMESTAMP,
    embedding VECTOR(1024) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_user_memory_embedding_cosine
    ON user_memory USING ivfflat (embedding vector_cosine_ops);

COMMIT;
