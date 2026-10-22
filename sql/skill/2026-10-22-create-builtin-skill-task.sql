BEGIN;

CREATE TABLE builtin_skill_execution_task (
    task_id UUID PRIMARY KEY,
    requester_user_id BIGINT NOT NULL,
    task_type VARCHAR(64) NOT NULL DEFAULT 'BUILTIN_SKILL',
    agent_id UUID NOT NULL,
    session_id UUID NOT NULL,
    skill_id VARCHAR(128) NOT NULL,
    skill_version VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_sha256 VARCHAR(64) NOT NULL,
    input_snapshot JSONB NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    progress SMALLINT NOT NULL DEFAULT 0,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 5,
    dispatch_attempt_count INTEGER NOT NULL DEFAULT 0,
    error_summary VARCHAR(500),
    failure_type VARCHAR(128),
    result_json JSONB,
    result_reference TEXT,
    next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_dispatched_at TIMESTAMP,
    lease_expires_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    CONSTRAINT uq_builtin_skill_task_requester_idempotency
        UNIQUE (requester_user_id, idempotency_key),
    CONSTRAINT fk_builtin_skill_task_requester
        FOREIGN KEY (requester_user_id) REFERENCES jchatmind_user(user_id) ON DELETE CASCADE,
    CONSTRAINT fk_builtin_skill_task_agent
        FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE,
    CONSTRAINT fk_builtin_skill_task_session
        FOREIGN KEY (session_id) REFERENCES chat_session(id) ON DELETE CASCADE,
    CONSTRAINT chk_builtin_skill_task_type
        CHECK (task_type = 'BUILTIN_SKILL'),
    CONSTRAINT chk_builtin_skill_task_status
        CHECK (status IN (
            'PENDING', 'QUEUED', 'RUNNING', 'RETRYING', 'WAITING_APPROVAL',
            'SUCCEEDED', 'FAILED', 'CANCELLED', 'DEAD_LETTER'
        )),
    CONSTRAINT chk_builtin_skill_task_progress
        CHECK (progress BETWEEN 0 AND 100),
    CONSTRAINT chk_builtin_skill_task_attempt_count
        CHECK (attempt_count >= 0 AND attempt_count <= max_attempts),
    CONSTRAINT chk_builtin_skill_task_max_attempts
        CHECK (max_attempts BETWEEN 1 AND 10),
    CONSTRAINT chk_builtin_skill_task_dispatch_attempt_count
        CHECK (dispatch_attempt_count >= 0),
    CONSTRAINT chk_builtin_skill_task_request_sha256
        CHECK (request_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_builtin_skill_task_dispatch
    ON builtin_skill_execution_task (status, next_attempt_at, created_at)
    WHERE status IN ('PENDING', 'RETRYING');

CREATE INDEX idx_builtin_skill_task_requester_created_at
    ON builtin_skill_execution_task (requester_user_id, created_at DESC);

COMMIT;
