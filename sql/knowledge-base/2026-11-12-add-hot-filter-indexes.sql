BEGIN;

CREATE INDEX idx_chunk_bge_m3_doc_id
    ON chunk_bge_m3 (doc_id);
CREATE INDEX idx_chunk_bge_m3_kb_id
    ON chunk_bge_m3 (kb_id);
CREATE INDEX idx_chat_message_session_id
    ON chat_message (session_id);
CREATE INDEX idx_chat_session_user_id
    ON chat_session (user_id);
CREATE INDEX idx_user_memory_user_id
    ON user_memory (user_id);

COMMIT;
