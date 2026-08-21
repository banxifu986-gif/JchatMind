BEGIN;

ALTER TABLE knowledge_base
    ADD COLUMN owner_id BIGINT;

ALTER TABLE knowledge_base
    ADD CONSTRAINT fk_knowledge_base_owner
        FOREIGN KEY (owner_id) REFERENCES jchatmind_user(user_id) NOT VALID;

ALTER TABLE knowledge_base
    ADD CONSTRAINT chk_knowledge_base_owner_required
        CHECK (owner_id IS NOT NULL) NOT VALID;

CREATE INDEX idx_knowledge_base_owner_id ON knowledge_base(owner_id);

COMMIT;

-- 历史 owner_id 为空的行不在本迁移中自动认领；应用层会拒绝访问。
-- 完成人工归属核验后，另行校验约束并将 owner_id 收紧为 NOT NULL。
