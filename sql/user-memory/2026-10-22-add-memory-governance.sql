DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM user_memory_candidate
        WHERE evidence_message_id IS NOT NULL
        GROUP BY user_id, memory_type, evidence_message_id, content
        HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'duplicate user memory candidates prevent idempotency index creation';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM user_memory_candidate
        WHERE evidence_message_id IS NOT NULL
          AND octet_length(content) > 1500
    ) THEN
        RAISE EXCEPTION 'oversized user memory candidates prevent idempotency index creation';
    END IF;
END;
$$;

CREATE UNIQUE INDEX user_memory_candidate_idempotency_uq
    ON user_memory_candidate (user_id, memory_type, evidence_message_id, content)
    WHERE evidence_message_id IS NOT NULL;

CREATE TABLE user_memory_automation_settings (
    user_id VARCHAR(64) PRIMARY KEY,
    automation_mode VARCHAR(16) NOT NULL DEFAULT 'BALANCED',
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT user_memory_automation_mode_chk
        CHECK (automation_mode IN ('REVIEW_ALL', 'BALANCED', 'AUTOMATIC'))
);

CREATE TABLE user_memory_extraction_state (
    session_id UUID PRIMARY KEY REFERENCES chat_session(id) ON DELETE CASCADE,
    user_id VARCHAR(64) NOT NULL,
    last_extracted_user_message_count INTEGER NOT NULL DEFAULT 0,
    last_attempt_at TIMESTAMP,
    last_success_at TIMESTAMP,
    last_failure_type VARCHAR(80),
    failure_count INTEGER NOT NULL DEFAULT 0,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE user_memory_consolidation_proposal (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id VARCHAR(64) NOT NULL,
    source_memory_ids JSONB NOT NULL,
    source_fingerprint VARCHAR(64) NOT NULL,
    proposed_type VARCHAR(32) NOT NULL,
    proposed_content VARCHAR(300) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    applied_memory_id UUID,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT user_memory_consolidation_status_chk
        CHECK (status IN ('PENDING', 'APPLIED', 'DISCARDED', 'STALE'))
);

CREATE INDEX user_memory_consolidation_pending_idx
    ON user_memory_consolidation_proposal (user_id, created_at DESC)
    WHERE status = 'PENDING';
