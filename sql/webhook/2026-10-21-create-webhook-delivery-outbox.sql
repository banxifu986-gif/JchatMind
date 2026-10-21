BEGIN;

CREATE TABLE webhook_inbox_event (
    inbox_event_id UUID PRIMARY KEY,
    source_id VARCHAR(128) NOT NULL,
    source_event_sha256 VARCHAR(64) NOT NULL,
    owner_id BIGINT NOT NULL,
    task_id UUID NOT NULL,
    payload_sha256 VARCHAR(64) NOT NULL,
    received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_webhook_inbox_source_event UNIQUE (source_id, source_event_sha256),
    CONSTRAINT fk_webhook_inbox_owner FOREIGN KEY (owner_id) REFERENCES jchatmind_user(user_id),
    CONSTRAINT fk_webhook_inbox_task FOREIGN KEY (task_id) REFERENCES ingestion_task(task_id),
    CONSTRAINT chk_webhook_inbox_event_hash CHECK (source_event_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_webhook_inbox_payload_hash CHECK (payload_sha256 ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_webhook_inbox_task_received_at
    ON webhook_inbox_event(task_id, received_at ASC);

CREATE TABLE webhook_delivery_outbox (
    outbox_id UUID PRIMARY KEY,
    callback_event_id UUID NOT NULL UNIQUE,
    source_id VARCHAR(128) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    aggregate_id UUID NOT NULL,
    event_payload TEXT NOT NULL,
    payload_sha256 VARCHAR(64) NOT NULL,
    delivery_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    attempt_count INTEGER NOT NULL DEFAULT 0,
    max_attempts INTEGER NOT NULL DEFAULT 5,
    next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_http_status INTEGER DEFAULT NULL,
    last_error_code VARCHAR(64) DEFAULT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    delivered_at TIMESTAMP DEFAULT NULL,
    dead_lettered_at TIMESTAMP DEFAULT NULL,
    CONSTRAINT chk_webhook_delivery_event_type CHECK (
        event_type IN ('INGESTION_SUCCEEDED', 'INGESTION_FAILED', 'APPROVAL_PENDING')
    ),
    CONSTRAINT chk_webhook_delivery_status CHECK (
        delivery_status IN ('PENDING', 'RETRYING', 'DELIVERED', 'DEAD_LETTER')
    ),
    CONSTRAINT chk_webhook_delivery_payload_hash CHECK (payload_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_webhook_delivery_attempts CHECK (
        attempt_count >= 0 AND max_attempts BETWEEN 1 AND 10 AND attempt_count <= max_attempts
    ),
    CONSTRAINT chk_webhook_delivery_http_status CHECK (
        last_http_status IS NULL OR last_http_status BETWEEN 100 AND 599
    ),
    CONSTRAINT uq_webhook_delivery_source_aggregate_event UNIQUE (source_id, aggregate_id, event_type)
);

CREATE INDEX idx_webhook_delivery_pending_next_attempt
    ON webhook_delivery_outbox(next_attempt_at ASC, created_at ASC)
    WHERE delivery_status IN ('PENDING', 'RETRYING');
CREATE INDEX idx_webhook_delivery_dead_letter_created_at
    ON webhook_delivery_outbox(created_at ASC)
    WHERE delivery_status = 'DEAD_LETTER';

COMMIT;
