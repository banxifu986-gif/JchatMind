BEGIN;

CREATE TABLE mcp_principal (
    principal_id      BIGSERIAL       PRIMARY KEY,
    provider          VARCHAR(64)     NOT NULL,
    external_subject  VARCHAR(255)    NOT NULL,
    status            VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
    created_at        TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    disabled_at       TIMESTAMP       DEFAULT NULL,
    CONSTRAINT uq_mcp_principal_provider_subject UNIQUE (provider, external_subject),
    CONSTRAINT chk_mcp_principal_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE TABLE mcp_principal_credential (
    credential_id          BIGSERIAL       PRIMARY KEY,
    principal_id           BIGINT          NOT NULL,
    credential_fingerprint CHAR(64)        NOT NULL,
    credential_version     VARCHAR(64)     NOT NULL,
    status                 VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',
    valid_from             TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at             TIMESTAMP       DEFAULT NULL,
    revoked_at             TIMESTAMP       DEFAULT NULL,
    created_at             TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_mcp_principal_credential_fingerprint UNIQUE (credential_fingerprint),
    CONSTRAINT fk_mcp_principal_credential_principal
        FOREIGN KEY (principal_id) REFERENCES mcp_principal(principal_id),
    CONSTRAINT chk_mcp_principal_credential_status CHECK (status IN ('ACTIVE', 'DISABLED'))
);

CREATE TABLE mcp_principal_user_grant (
    grant_id             BIGSERIAL       PRIMARY KEY,
    principal_id         BIGINT          NOT NULL,
    user_id              BIGINT          NOT NULL,
    granted_by_user_id   BIGINT          NOT NULL,
    grant_reason         VARCHAR(500)    NOT NULL,
    granted_at           TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at           TIMESTAMP       DEFAULT NULL,
    revoked_by_user_id   BIGINT          DEFAULT NULL,
    revocation_reason    VARCHAR(500)    DEFAULT NULL,
    CONSTRAINT fk_mcp_principal_user_grant_principal
        FOREIGN KEY (principal_id) REFERENCES mcp_principal(principal_id),
    CONSTRAINT fk_mcp_principal_user_grant_user
        FOREIGN KEY (user_id) REFERENCES jchatmind_user(user_id),
    CONSTRAINT fk_mcp_principal_user_grant_granted_by
        FOREIGN KEY (granted_by_user_id) REFERENCES jchatmind_user(user_id),
    CONSTRAINT fk_mcp_principal_user_grant_revoked_by
        FOREIGN KEY (revoked_by_user_id) REFERENCES jchatmind_user(user_id)
);

CREATE UNIQUE INDEX uq_mcp_principal_single_active_user_grant
    ON mcp_principal_user_grant(principal_id)
    WHERE revoked_at IS NULL;

CREATE TABLE mcp_access_audit (
    audit_id          BIGSERIAL       PRIMARY KEY,
    principal_id      BIGINT          DEFAULT NULL,
    user_id           BIGINT          DEFAULT NULL,
    action            VARCHAR(64)     NOT NULL,
    decision          VARCHAR(16)     NOT NULL,
    target_kb_ids     JSONB           NOT NULL DEFAULT '[]'::jsonb,
    correlation_id    VARCHAR(64)     NOT NULL,
    reason_code       VARCHAR(64)     DEFAULT NULL,
    request_metadata  JSONB           NOT NULL DEFAULT '{}'::jsonb,
    created_at        TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_mcp_access_audit_principal
        FOREIGN KEY (principal_id) REFERENCES mcp_principal(principal_id),
    CONSTRAINT fk_mcp_access_audit_user
        FOREIGN KEY (user_id) REFERENCES jchatmind_user(user_id),
    CONSTRAINT chk_mcp_access_audit_decision CHECK (decision IN ('ALLOW', 'DENY'))
);

CREATE INDEX idx_mcp_principal_credential_principal_id
    ON mcp_principal_credential(principal_id);
CREATE INDEX idx_mcp_access_audit_principal_created_at
    ON mcp_access_audit(principal_id, created_at DESC);
CREATE INDEX idx_mcp_access_audit_user_created_at
    ON mcp_access_audit(user_id, created_at DESC);

COMMIT;
