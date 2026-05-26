BEGIN;

ALTER TABLE IF EXISTS user_memory_candidate
    ADD COLUMN IF NOT EXISTS status VARCHAR(16) DEFAULT 'PENDING';

UPDATE user_memory_candidate
SET status = 'PERSISTED'
WHERE status IS NULL;

COMMIT;
