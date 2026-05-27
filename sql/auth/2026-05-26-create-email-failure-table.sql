-- 创建邮件发送失败审计表（PostgreSQL）
CREATE TABLE IF NOT EXISTS email_send_failure (
    id              BIGSERIAL       PRIMARY KEY,
    task_id         VARCHAR(36)     NOT NULL,
    email           VARCHAR(255)    NOT NULL,
    type            VARCHAR(20)     NOT NULL,
    retry_count     INT             NOT NULL DEFAULT 0,
    failure_reason  VARCHAR(500),
    trace_id        VARCHAR(64),
    expired_flag    SMALLINT        NOT NULL DEFAULT 0,
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    failed_at       TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_email_failure_task_id ON email_send_failure (task_id);
CREATE INDEX IF NOT EXISTS idx_email_failure_email ON email_send_failure (email);
