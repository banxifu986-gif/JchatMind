BEGIN;

CREATE TABLE workflow_execution_audit (
    audit_id UUID PRIMARY KEY,
    trace_id VARCHAR(128) NOT NULL,
    owner_id BIGINT DEFAULT NULL,
    request_sha256 CHAR(64) NOT NULL,
    plan_sha256 CHAR(64) DEFAULT NULL,
    outcome VARCHAR(32) NOT NULL,
    step_metadata JSONB NOT NULL DEFAULT '[]'::JSONB,
    failure_code VARCHAR(64) DEFAULT NULL,
    duration_ms BIGINT DEFAULT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL DEFAULT (CURRENT_TIMESTAMP + INTERVAL '90 days'),
    CONSTRAINT chk_workflow_execution_audit_request_sha256 CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_workflow_execution_audit_plan_sha256 CHECK (plan_sha256 IS NULL OR plan_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_workflow_execution_audit_outcome CHECK (
        outcome IN ('SUCCEEDED', 'REJECTED', 'FAILED', 'TIMED_OUT', 'FALLBACK_SUCCEEDED', 'FALLBACK_FAILED')
    ),
    CONSTRAINT chk_workflow_execution_audit_step_metadata_array CHECK (jsonb_typeof(step_metadata) = 'array'),
    CONSTRAINT chk_workflow_execution_audit_duration CHECK (duration_ms IS NULL OR duration_ms >= 0),
    CONSTRAINT chk_workflow_execution_audit_expiry CHECK (expires_at > created_at)
);

CREATE INDEX idx_workflow_execution_audit_trace_created_at
    ON workflow_execution_audit(trace_id, created_at DESC);
CREATE INDEX idx_workflow_execution_audit_expires_at
    ON workflow_execution_audit(expires_at ASC);

COMMIT;
