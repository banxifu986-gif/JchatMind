BEGIN;

-- 前置条件：knowledge_base.owner_id 已存在，且所有需要保留的 KB 已人工确认 owner。
CREATE TABLE agent_knowledge_base (
    agent_id         UUID        NOT NULL,
    kb_id            UUID        NOT NULL,
    bound_by_user_id BIGINT      NOT NULL,
    bound_at         TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_agent_knowledge_base PRIMARY KEY (agent_id, kb_id),
    CONSTRAINT fk_agent_knowledge_base_agent
        FOREIGN KEY (agent_id) REFERENCES agent(id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_knowledge_base_kb
        FOREIGN KEY (kb_id) REFERENCES knowledge_base(id) ON DELETE CASCADE,
    CONSTRAINT fk_agent_knowledge_base_bound_by
        FOREIGN KEY (bound_by_user_id) REFERENCES jchatmind_user(user_id)
);

CREATE INDEX idx_agent_knowledge_base_kb_id ON agent_knowledge_base(kb_id);

-- 旧 JSONB 中只有 Agent owner 与 KB owner 一致的绑定可以进入新模型。
INSERT INTO agent_knowledge_base (agent_id, kb_id, bound_by_user_id, bound_at)
SELECT DISTINCT
    a.id,
    kb.id,
    a.user_id,
    COALESCE(a.updated_at, a.created_at, CURRENT_TIMESTAMP)
FROM agent a
CROSS JOIN LATERAL jsonb_array_elements_text(COALESCE(a.allowed_kbs, '[]'::jsonb)) binding(kb_id_text)
JOIN knowledge_base kb
    ON kb.id::text = binding.kb_id_text
    AND kb.owner_id = a.user_id
ON CONFLICT (agent_id, kb_id) DO NOTHING;

ALTER TABLE agent DROP COLUMN allowed_kbs;

COMMIT;
