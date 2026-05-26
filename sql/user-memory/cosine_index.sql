-- chunk_bge_m3
CREATE INDEX IF NOT EXISTS idx_chunk_bge_m3_embedding_cosine
    ON chunk_bge_m3 USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

-- user_memory
CREATE INDEX IF NOT EXISTS idx_user_memory_embedding_cosine
    ON user_memory USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);