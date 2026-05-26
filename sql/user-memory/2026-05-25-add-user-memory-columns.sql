BEGIN;

ALTER TABLE IF EXISTS user_memory
    ADD COLUMN IF NOT EXISTS importance VARCHAR(16) DEFAULT 'medium';

ALTER TABLE IF EXISTS user_memory
    ADD COLUMN IF NOT EXISTS evidence_message_id UUID;

ALTER TABLE IF EXISTS user_memory
    ADD COLUMN IF NOT EXISTS evidence_text TEXT;

ALTER TABLE IF EXISTS user_memory_candidate
    ADD COLUMN IF NOT EXISTS importance VARCHAR(16) DEFAULT 'medium';

ALTER TABLE IF EXISTS user_memory_candidate
    ADD COLUMN IF NOT EXISTS evidence_message_id UUID;

COMMIT;
