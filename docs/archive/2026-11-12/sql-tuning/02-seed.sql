-- jchatmind perf sandbox seed
-- Deterministic ids: md5('kb-'||i), md5('doc-'||kb||'-'||j), md5('chunk-'||kb||'-'||j||'-'||c),
-- md5('session-'||i), md5('msg-'||s||'-'||m), md5('agent-'||i), md5('memory-'||u||'-'||i)
-- Volumes: 20 KBs / 200 docs / 4000 chunks; 5000 sessions / 6000 messages;
-- 25 agents x 3 kb bindings; 5 memory users x 200 memories.

BEGIN;

DROP TABLE IF EXISTS perf_vec;
CREATE TABLE perf_vec (vid INT PRIMARY KEY, v VECTOR(1024));
DO $$
DECLARE
  lit TEXT;
BEGIN
  FOR i IN 1..10 LOOP
    SELECT string_agg(round(random()::numeric, 4)::text, ',') INTO lit
    FROM generate_series(1, 1024);
    INSERT INTO perf_vec VALUES (i, ('[' || lit || ']')::vector);
  END LOOP;
END $$;

INSERT INTO knowledge_base (id, name, owner_id)
SELECT md5('kb-' || i)::uuid, 'perf-kb-' || i, 100 + i
FROM generate_series(1, 20) i;

INSERT INTO document (id, kb_id, filename, filetype, size)
SELECT md5('doc-' || d || '-' || j)::uuid,
       md5('kb-' || d)::uuid,
       'doc-' || d || '-' || j || '.md',
       'md', 2048
FROM generate_series(1, 20) d, generate_series(1, 10) j;

INSERT INTO chunk_bge_m3 (id, kb_id, doc_id, content, metadata, embedding, created_at, updated_at)
SELECT
    md5('chunk-' || d || '-' || j || '-' || c)::uuid,
    md5('kb-' || d)::uuid,
    md5('doc-' || d || '-' || j)::uuid,
    repeat(md5(d || '-' || j || '-' || c), 16),
    CASE WHEN c % 5 = 0
         THEN jsonb_build_object(
                  'retrievableTitle', 'Section ' || d || '-' || j || '-' || c,
                  'contentPath', '/doc/' || d || '/' || j || '/part-' || c)
         ELSE '{}'::jsonb
    END,
    (SELECT v FROM perf_vec WHERE vid = 1 + (d * 20 + j * 20 + c) % 10),
    CURRENT_TIMESTAMP - (random() * interval '30 days'),
    CURRENT_TIMESTAMP - (random() * interval '30 days')
FROM generate_series(1, 20) d, generate_series(1, 10) j, generate_series(1, 20) c;

INSERT INTO chat_session (id, user_id, agent_id, title)
SELECT md5('session-' || i)::uuid,
       'perf-user-' || (1 + (i % 200)),
       md5('agent-' || (1 + (i % 25)))::uuid,
       'perf-session-' || i
FROM generate_series(1, 5000) i;

INSERT INTO chat_message (id, session_id, role, content)
SELECT
    md5('msg-' || s || '-' || m)::uuid,
    md5('session-' || s)::uuid,
    (ARRAY['user', 'assistant', 'tool'])[1 + (m % 3)],
    repeat(md5(s || '-' || m), 3)
FROM generate_series(1, 100) s, generate_series(1, 60) m;

INSERT INTO agent_execution_turn_message (id, session_id, sequence_no, chat_message_id)
SELECT
    md5('turn-' || s || '-' || m)::uuid,
    md5('session-' || s)::uuid,
    row_number() OVER (PARTITION BY s ORDER BY m),
    md5('msg-' || s || '-' || m)::uuid
FROM generate_series(1, 100) s, generate_series(1, 60) m
WHERE m % 3 = 2;

INSERT INTO agent (id, name)
SELECT md5('agent-' || i)::uuid, 'perf-agent-' || i
FROM generate_series(1, 25) i;

INSERT INTO agent_knowledge_base (agent_id, kb_id, bound_by_user_id, bound_at)
SELECT
    md5('agent-' || a)::uuid,
    md5('kb-' || ((a + k - 1) % 20 + 1))::uuid,
    100 + a,
    CURRENT_TIMESTAMP - (random() * interval '10 days')
FROM generate_series(1, 25) a, generate_series(1, 3) k;

INSERT INTO user_memory (id, user_id, session_id, memory_type, content, importance, embedding)
SELECT
    md5('memory-' || u || '-' || i)::uuid,
    'perf-muser-' || u,
    md5('session-' || (1 + (i % 100)))::uuid,
    (ARRAY['preference', 'fact', 'goal', 'habit'])[1 + (i % 4)],
    'memory content ' || u || '-' || i || ': ' || repeat(md5(u || '-' || i), 4),
    0.5,
    (SELECT v FROM perf_vec WHERE vid = 1 + (u * 200 + i) % 10)
FROM generate_series(1, 5) u, generate_series(1, 200) i;

DROP TABLE IF EXISTS perf_vec;

COMMIT;

ANALYZE knowledge_base;
ANALYZE document;
ANALYZE chunk_bge_m3;
ANALYZE chat_session;
ANALYZE chat_message;
ANALYZE agent_execution_turn_message;
ANALYZE agent_knowledge_base;
ANALYZE user_memory;
