BEGIN;

-- 仅在历史 KB 已人工认领或清理后执行。
ALTER TABLE knowledge_base
    VALIDATE CONSTRAINT fk_knowledge_base_owner;

ALTER TABLE knowledge_base
    VALIDATE CONSTRAINT chk_knowledge_base_owner_required;

ALTER TABLE knowledge_base
    ALTER COLUMN owner_id SET NOT NULL;

COMMIT;
