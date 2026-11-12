BEGIN;

CREATE INDEX idx_user_memory_candidate_user_id
    ON user_memory_candidate (user_id);
CREATE INDEX idx_document_kb_id
    ON document (kb_id);

COMMIT;
