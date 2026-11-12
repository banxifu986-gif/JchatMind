-- The 5 candidate indexes from Tier-1 task 4, applied to jchatmind_perf only.
-- Production DB gets them via the order-250 migration at release time, not here.

BEGIN;

CREATE INDEX idx_chunk_bge_m3_doc_id ON chunk_bge_m3 (doc_id);
CREATE INDEX idx_chunk_bge_m3_kb_id ON chunk_bge_m3 (kb_id);
CREATE INDEX idx_chat_message_session_id ON chat_message (session_id);
CREATE INDEX idx_chat_session_user_id ON chat_session (user_id);
CREATE INDEX idx_user_memory_user_id ON user_memory (user_id);

COMMIT;

ANALYZE chunk_bge_m3;
ANALYZE chat_message;
ANALYZE chat_session;
ANALYZE user_memory;
