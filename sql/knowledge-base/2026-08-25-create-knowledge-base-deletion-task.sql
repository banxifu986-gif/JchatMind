BEGIN;

CREATE TABLE knowledge_base_deletion_task (
    task_id UUID PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    knowledge_base_id UUID NOT NULL,
    task_type VARCHAR(64) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    input_snapshot JSONB NOT NULL,
    skill_version VARCHAR(64) DEFAULT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'QUEUED',
    progress INTEGER NOT NULL DEFAULT 0,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 3,
    error_summary VARCHAR(500) DEFAULT NULL,
    result_ref VARCHAR(500) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP DEFAULT NULL,
    completed_at TIMESTAMP DEFAULT NULL,
    CONSTRAINT fk_knowledge_base_deletion_task_owner
        FOREIGN KEY (owner_id) REFERENCES jchatmind_user(user_id),
    CONSTRAINT uq_knowledge_base_deletion_task_owner_idempotency
        UNIQUE (owner_id, idempotency_key),
    CONSTRAINT chk_knowledge_base_deletion_task_status CHECK (
        status IN ('QUEUED', 'RUNNING', 'RETRYING', 'DEAD_LETTER', 'SUCCEEDED')
    ),
    CONSTRAINT chk_knowledge_base_deletion_task_progress CHECK (progress BETWEEN 0 AND 100),
    CONSTRAINT chk_knowledge_base_deletion_task_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT chk_knowledge_base_deletion_task_max_attempts CHECK (max_attempts > 0)
);

CREATE INDEX idx_knowledge_base_deletion_task_owner_created_at
    ON knowledge_base_deletion_task(owner_id, created_at DESC);
CREATE INDEX idx_knowledge_base_deletion_task_status_created_at
    ON knowledge_base_deletion_task(status, created_at ASC);

CREATE TABLE knowledge_base_deletion_audit (
    audit_id UUID PRIMARY KEY,
    task_id UUID NOT NULL,
    owner_id BIGINT NOT NULL,
    knowledge_base_id UUID NOT NULL,
    action VARCHAR(32) NOT NULL,
    task_status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_knowledge_base_deletion_audit_task
        FOREIGN KEY (task_id) REFERENCES knowledge_base_deletion_task(task_id)
);

CREATE INDEX idx_knowledge_base_deletion_audit_task_created_at
    ON knowledge_base_deletion_audit(task_id, created_at ASC);

COMMIT;
