BEGIN;

CREATE TABLE ingestion_task (
    task_id            UUID         PRIMARY KEY,
    owner_id           BIGINT       NOT NULL,
    kb_id              UUID         NOT NULL,
    document_id        UUID         NOT NULL,
    idempotency_key    VARCHAR(128) NOT NULL,
    task_type          VARCHAR(64)  NOT NULL DEFAULT 'DOCUMENT_INGESTION',
    status             VARCHAR(32)  NOT NULL DEFAULT 'QUEUED',
    attempt_count      INTEGER      NOT NULL DEFAULT 0,
    max_attempts       INTEGER      NOT NULL DEFAULT 3,
    error_summary      VARCHAR(500) DEFAULT NULL,
    created_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at         TIMESTAMP    DEFAULT NULL,
    completed_at       TIMESTAMP    DEFAULT NULL,
    CONSTRAINT uq_ingestion_task_owner_idempotency UNIQUE (owner_id, idempotency_key),
    CONSTRAINT fk_ingestion_task_owner
        FOREIGN KEY (owner_id) REFERENCES jchatmind_user(user_id),
    CONSTRAINT fk_ingestion_task_kb
        FOREIGN KEY (kb_id) REFERENCES knowledge_base(id) ON DELETE CASCADE,
    CONSTRAINT fk_ingestion_task_document
        FOREIGN KEY (document_id) REFERENCES document(id) ON DELETE CASCADE,
    CONSTRAINT chk_ingestion_task_status CHECK (
        status IN ('QUEUED', 'RUNNING', 'RETRYING', 'FAILED', 'DEAD_LETTER', 'CANCELLED', 'SUCCEEDED')
    ),
    CONSTRAINT chk_ingestion_task_attempt_count CHECK (attempt_count >= 0),
    CONSTRAINT chk_ingestion_task_max_attempts CHECK (max_attempts > 0)
);

CREATE INDEX idx_ingestion_task_owner_created_at
    ON ingestion_task(owner_id, created_at DESC);
CREATE INDEX idx_ingestion_task_status_created_at
    ON ingestion_task(status, created_at ASC);
CREATE INDEX idx_ingestion_task_document_id
    ON ingestion_task(document_id);

COMMIT;
