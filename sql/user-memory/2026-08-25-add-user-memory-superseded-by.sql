BEGIN;

ALTER TABLE IF EXISTS user_memory
    ADD COLUMN IF NOT EXISTS superseded_by_memory_id UUID;

DO $$
BEGIN
    IF to_regclass('user_memory') IS NOT NULL
        AND NOT EXISTS (
            SELECT 1
            FROM pg_constraint
            WHERE conname = 'fk_user_memory_superseded_by'
              AND conrelid = 'user_memory'::regclass
        ) THEN
        ALTER TABLE user_memory
            ADD CONSTRAINT fk_user_memory_superseded_by
            FOREIGN KEY (superseded_by_memory_id)
            REFERENCES user_memory(id)
            ON DELETE CASCADE;
    END IF;
END
$$;

COMMIT;
