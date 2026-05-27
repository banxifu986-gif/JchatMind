-- 创建用户表（PostgreSQL）
BEGIN;

CREATE TABLE IF NOT EXISTS jchatmind_user (
    user_id         BIGSERIAL       PRIMARY KEY,
    account         VARCHAR(32)     NOT NULL,
    username        VARCHAR(16)     NOT NULL,
    password        VARCHAR(255)    NOT NULL,  -- BCrypt 哈希
    gender          SMALLINT        DEFAULT NULL,
    birthday        DATE            DEFAULT NULL,
    avatar_url      VARCHAR(255)    DEFAULT NULL,
    email           VARCHAR(255)    DEFAULT NULL,
    school          VARCHAR(255)    DEFAULT NULL,
    signature       VARCHAR(255)    DEFAULT NULL,
    is_banned       SMALLINT        NOT NULL DEFAULT 0,
    is_admin        SMALLINT        NOT NULL DEFAULT 0,
    last_login_at   TIMESTAMP       DEFAULT NULL,
    created_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 自动更新 updated_at 的触发器
CREATE OR REPLACE FUNCTION update_jchatmind_user_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

DROP TRIGGER IF EXISTS trg_jchatmind_user_updated_at ON jchatmind_user;
CREATE TRIGGER trg_jchatmind_user_updated_at
    BEFORE UPDATE ON jchatmind_user
    FOR EACH ROW EXECUTE FUNCTION update_jchatmind_user_updated_at();

-- 唯一索引
CREATE UNIQUE INDEX IF NOT EXISTS idx_user_account ON jchatmind_user (account);
CREATE UNIQUE INDEX IF NOT EXISTS idx_user_email ON jchatmind_user (email);

COMMIT;
