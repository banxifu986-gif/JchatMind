-- jchatmind perf measurement
-- Run: docker exec -i jchatmind-postgres psql -U postgres -d jchatmind_perf -f - < 03-measure.sql
-- Same script before and after 04-apply-indexes.sql; psql \timing prints per-statement ms.

\timing on

\echo '=== WARMUP ==='
SELECT count(*) FROM chat_message WHERE session_id = md5('session-1')::uuid;
SELECT count(*) FROM chat_session WHERE user_id = 'perf-user-1';
SELECT count(*) FROM user_memory WHERE user_id = 'perf-muser-1';

\echo '=== S1 selectBySessionId (chat_message JOIN turn, real mapper SQL) rep1 ==='
SELECT
    message.id,
    message.session_id,
    message.role,
    message.content,
    message.metadata::text AS metadata,
    message.created_at,
    message.updated_at
FROM chat_message message
LEFT JOIN agent_execution_turn_message turn ON turn.chat_message_id = message.id
WHERE message.session_id = md5('session-1')::uuid
ORDER BY turn.sequence_no ASC NULLS FIRST, message.created_at ASC, message.id ASC;

\echo '=== S1 rep2 ==='
SELECT
    message.id,
    message.session_id,
    message.role,
    message.content,
    message.metadata::text AS metadata,
    message.created_at,
    message.updated_at
FROM chat_message message
LEFT JOIN agent_execution_turn_message turn ON turn.chat_message_id = message.id
WHERE message.session_id = md5('session-1')::uuid
ORDER BY turn.sequence_no ASC NULLS FIRST, message.created_at ASC, message.id ASC;

\echo '=== S1 EXPLAIN ==='
EXPLAIN (ANALYZE, COSTS OFF)
SELECT message.id FROM chat_message message
LEFT JOIN agent_execution_turn_message turn ON turn.chat_message_id = message.id
WHERE message.session_id = md5('session-1')::uuid
ORDER BY turn.sequence_no ASC NULLS FIRST, message.created_at ASC, message.id ASC;

\echo '=== S2 chat_session selectByUserId rep1 ==='
SELECT id, user_id, agent_id, title, metadata::text AS metadata, created_at, updated_at
FROM chat_session
WHERE user_id = 'perf-user-1'
ORDER BY updated_at DESC;

\echo '=== S2 rep2 ==='
SELECT id, user_id, agent_id, title, metadata::text AS metadata, created_at, updated_at
FROM chat_session
WHERE user_id = 'perf-user-1'
ORDER BY updated_at DESC;

\echo '=== S2 EXPLAIN ==='
EXPLAIN (ANALYZE, COSTS OFF)
SELECT id FROM chat_session WHERE user_id = 'perf-user-1' ORDER BY updated_at DESC;

\echo '=== S3 user_memory selectActiveByUserId rep1 ==='
SELECT id, user_id, session_id, memory_type, content, importance,
       evidence_message_id, evidence_text, superseded_by_memory_id, expires_at,
       embedding, created_at, updated_at
FROM user_memory
WHERE user_id = 'perf-muser-1'
  AND superseded_by_memory_id IS NULL
  AND (expires_at IS NULL OR expires_at > NOW())
ORDER BY updated_at DESC;

\echo '=== S3 rep2 ==='
SELECT id, user_id, session_id, memory_type, content, importance,
       evidence_message_id, evidence_text, superseded_by_memory_id, expires_at,
       embedding, created_at, updated_at
FROM user_memory
WHERE user_id = 'perf-muser-1'
  AND superseded_by_memory_id IS NULL
  AND (expires_at IS NULL OR expires_at > NOW())
ORDER BY updated_at DESC;

\echo '=== S3 EXPLAIN ==='
EXPLAIN (ANALYZE, COSTS OFF)
SELECT id FROM user_memory
WHERE user_id = 'perf-muser-1'
  AND superseded_by_memory_id IS NULL
  AND (expires_at IS NULL OR expires_at > NOW())
ORDER BY updated_at DESC;

\echo '=== S3b handleConflictUpdate new pattern: typed LIMIT 1 ==='
SELECT id, user_id, session_id, memory_type, content, importance,
       evidence_message_id, evidence_text, superseded_by_memory_id, expires_at,
       embedding, created_at, updated_at
FROM user_memory
WHERE user_id = 'perf-muser-1'
  AND memory_type = 'preference'
  AND superseded_by_memory_id IS NULL
  AND (expires_at IS NULL OR expires_at > NOW())
ORDER BY updated_at DESC
LIMIT 1;

\echo '=== S4 getDocuments OLD pattern: 10 x selectByKbId ==='
SELECT id, kb_id, filename, filetype, size, metadata::text AS metadata, created_at, updated_at
FROM document WHERE kb_id = md5('kb-1')::uuid ORDER BY updated_at DESC;
SELECT id, kb_id, filename, filetype, size, metadata::text AS metadata, created_at, updated_at
FROM document WHERE kb_id = md5('kb-2')::uuid ORDER BY updated_at DESC;
SELECT id, kb_id, filename, filetype, size, metadata::text AS metadata, created_at, updated_at
FROM document WHERE kb_id = md5('kb-3')::uuid ORDER BY updated_at DESC;
SELECT id, kb_id, filename, filetype, size, metadata::text AS metadata, created_at, updated_at
FROM document WHERE kb_id = md5('kb-4')::uuid ORDER BY updated_at DESC;
SELECT id, kb_id, filename, filetype, size, metadata::text AS metadata, created_at, updated_at
FROM document WHERE kb_id = md5('kb-5')::uuid ORDER BY updated_at DESC;
SELECT id, kb_id, filename, filetype, size, metadata::text AS metadata, created_at, updated_at
FROM document WHERE kb_id = md5('kb-6')::uuid ORDER BY updated_at DESC;
SELECT id, kb_id, filename, filetype, size, metadata::text AS metadata, created_at, updated_at
FROM document WHERE kb_id = md5('kb-7')::uuid ORDER BY updated_at DESC;
SELECT id, kb_id, filename, filetype, size, metadata::text AS metadata, created_at, updated_at
FROM document WHERE kb_id = md5('kb-8')::uuid ORDER BY updated_at DESC;
SELECT id, kb_id, filename, filetype, size, metadata::text AS metadata, created_at, updated_at
FROM document WHERE kb_id = md5('kb-9')::uuid ORDER BY updated_at DESC;
SELECT id, kb_id, filename, filetype, size, metadata::text AS metadata, created_at, updated_at
FROM document WHERE kb_id = md5('kb-10')::uuid ORDER BY updated_at DESC;

\echo '=== S4 NEW pattern: single IN query ==='
SELECT id, kb_id, filename, filetype, size, metadata::text AS metadata, created_at, updated_at
FROM document
WHERE kb_id IN (
    md5('kb-1')::uuid, md5('kb-2')::uuid, md5('kb-3')::uuid, md5('kb-4')::uuid, md5('kb-5')::uuid,
    md5('kb-6')::uuid, md5('kb-7')::uuid, md5('kb-8')::uuid, md5('kb-9')::uuid, md5('kb-10')::uuid
)
ORDER BY updated_at DESC;

\echo '=== S5 getAgents OLD pattern: 25 x selectKbIdsByAgentId ==='
SELECT kb_id FROM agent_knowledge_base WHERE agent_id = md5('agent-1')::uuid ORDER BY bound_at, kb_id;
SELECT kb_id FROM agent_knowledge_base WHERE agent_id = md5('agent-2')::uuid ORDER BY bound_at, kb_id;
SELECT kb_id FROM agent_knowledge_base WHERE agent_id = md5('agent-3')::uuid ORDER BY bound_at, kb_id;
SELECT kb_id FROM agent_knowledge_base WHERE agent_id = md5('agent-4')::uuid ORDER BY bound_at, kb_id;
SELECT kb_id FROM agent_knowledge_base WHERE agent_id = md5('agent-5')::uuid ORDER BY bound_at, kb_id;
SELECT kb_id FROM agent_knowledge_base WHERE agent_id = md5('agent-6')::uuid ORDER BY bound_at, kb_id;
SELECT kb_id FROM agent_knowledge_base WHERE agent_id = md5('agent-7')::uuid ORDER BY bound_at, kb_id;
SELECT kb_id FROM agent_knowledge_base WHERE agent_id = md5('agent-8')::uuid ORDER BY bound_at, kb_id;
SELECT kb_id FROM agent_knowledge_base WHERE agent_id = md5('agent-9')::uuid ORDER BY bound_at, kb_id;
SELECT kb_id FROM agent_knowledge_base WHERE agent_id = md5('agent-10')::uuid ORDER BY bound_at, kb_id;
SELECT kb_id FROM agent_knowledge_base WHERE agent_id = md5('agent-11')::uuid ORDER BY bound_at, kb_id;
SELECT kb_id FROM agent_knowledge_base WHERE agent_id = md5('agent-12')::uuid ORDER BY bound_at, kb_id;
SELECT kb_id FROM agent_knowledge_base WHERE agent_id = md5('agent-13')::uuid ORDER BY bound_at, kb_id;
SELECT kb_id FROM agent_knowledge_base WHERE agent_id = md5('agent-14')::uuid ORDER BY bound_at, kb_id;
SELECT kb_id FROM agent_knowledge_base WHERE agent_id = md5('agent-15')::uuid ORDER BY bound_at, kb_id;
SELECT kb_id FROM agent_knowledge_base WHERE agent_id = md5('agent-16')::uuid ORDER BY bound_at, kb_id;
SELECT kb_id FROM agent_knowledge_base WHERE agent_id = md5('agent-17')::uuid ORDER BY bound_at, kb_id;
SELECT kb_id FROM agent_knowledge_base WHERE agent_id = md5('agent-18')::uuid ORDER BY bound_at, kb_id;
SELECT kb_id FROM agent_knowledge_base WHERE agent_id = md5('agent-19')::uuid ORDER BY bound_at, kb_id;
SELECT kb_id FROM agent_knowledge_base WHERE agent_id = md5('agent-20')::uuid ORDER BY bound_at, kb_id;
SELECT kb_id FROM agent_knowledge_base WHERE agent_id = md5('agent-21')::uuid ORDER BY bound_at, kb_id;
SELECT kb_id FROM agent_knowledge_base WHERE agent_id = md5('agent-22')::uuid ORDER BY bound_at, kb_id;
SELECT kb_id FROM agent_knowledge_base WHERE agent_id = md5('agent-23')::uuid ORDER BY bound_at, kb_id;
SELECT kb_id FROM agent_knowledge_base WHERE agent_id = md5('agent-24')::uuid ORDER BY bound_at, kb_id;
SELECT kb_id FROM agent_knowledge_base WHERE agent_id = md5('agent-25')::uuid ORDER BY bound_at, kb_id;

\echo '=== S5 NEW pattern: single IN query (25 agents) ==='
SELECT agent_id, kb_id
FROM agent_knowledge_base
WHERE agent_id IN (
    md5('agent-1')::uuid, md5('agent-2')::uuid, md5('agent-3')::uuid, md5('agent-4')::uuid, md5('agent-5')::uuid,
    md5('agent-6')::uuid, md5('agent-7')::uuid, md5('agent-8')::uuid, md5('agent-9')::uuid, md5('agent-10')::uuid,
    md5('agent-11')::uuid, md5('agent-12')::uuid, md5('agent-13')::uuid, md5('agent-14')::uuid, md5('agent-15')::uuid,
    md5('agent-16')::uuid, md5('agent-17')::uuid, md5('agent-18')::uuid, md5('agent-19')::uuid, md5('agent-20')::uuid,
    md5('agent-21')::uuid, md5('agent-22')::uuid, md5('agent-23')::uuid, md5('agent-24')::uuid, md5('agent-25')::uuid
)
ORDER BY bound_at, kb_id;

\echo '=== S6 KnowledgeBaseAccessService OLD pattern: 5 x selectById ==='
SELECT id, name, description, metadata::text AS metadata, owner_id, created_at, updated_at
FROM knowledge_base WHERE id = md5('kb-1')::uuid;
SELECT id, name, description, metadata::text AS metadata, owner_id, created_at, updated_at
FROM knowledge_base WHERE id = md5('kb-2')::uuid;
SELECT id, name, description, metadata::text AS metadata, owner_id, created_at, updated_at
FROM knowledge_base WHERE id = md5('kb-3')::uuid;
SELECT id, name, description, metadata::text AS metadata, owner_id, created_at, updated_at
FROM knowledge_base WHERE id = md5('kb-4')::uuid;
SELECT id, name, description, metadata::text AS metadata, owner_id, created_at, updated_at
FROM knowledge_base WHERE id = md5('kb-5')::uuid;

\echo '=== S6 NEW pattern: selectByIdBatch single IN ==='
SELECT id, name, description, metadata::text AS metadata, owner_id, created_at, updated_at
FROM knowledge_base
WHERE id IN (md5('kb-1')::uuid, md5('kb-2')::uuid, md5('kb-3')::uuid, md5('kb-4')::uuid, md5('kb-5')::uuid);

\echo '=== S7 title-path candidates FULL (current production SQL) ==='
SELECT id AS chunk_id, kb_id, doc_id, content, metadata::text AS metadata,
       4::double precision AS distance,
       ROW_NUMBER() OVER (ORDER BY created_at ASC, id ASC) AS rank
FROM chunk_bge_m3
WHERE kb_id IN (md5('kb-1')::uuid)
  AND COALESCE(metadata->>'retrievableTitle', metadata->>'title', '') <> ''
  AND COALESCE(metadata->>'contentPath', '') <> '';

\echo '=== S7 EXPLAIN ==='
EXPLAIN (ANALYZE, COSTS OFF)
SELECT id FROM chunk_bge_m3
WHERE kb_id IN (md5('kb-1')::uuid)
  AND COALESCE(metadata->>'retrievableTitle', metadata->>'title', '') <> ''
  AND COALESCE(metadata->>'contentPath', '') <> '';

\echo '=== S7b title-path candidates NEW: ORDER BY + LIMIT 500 ==='
SELECT id AS chunk_id, kb_id, doc_id, content, metadata::text AS metadata,
       4::double precision AS distance,
       ROW_NUMBER() OVER (ORDER BY created_at ASC, id ASC) AS rank
FROM chunk_bge_m3
WHERE kb_id IN (md5('kb-1')::uuid)
  AND COALESCE(metadata->>'retrievableTitle', metadata->>'title', '') <> ''
  AND COALESCE(metadata->>'contentPath', '') <> ''
ORDER BY created_at ASC, id ASC
LIMIT 500;

\echo '=== S8 chunk_bge_m3 doc filter EXPLAIN (no index) ==='
EXPLAIN (ANALYZE, COSTS OFF)
SELECT id FROM chunk_bge_m3 WHERE doc_id = md5('doc-1-1')::uuid;

\echo '=== S9 DELETE OLD pattern: select 20 chunks + 20 x deleteById (rolled back) ==='
BEGIN;
SELECT id, kb_id, doc_id, content, metadata::text AS metadata, embedding, created_at, updated_at
FROM chunk_bge_m3
WHERE doc_id = md5('doc-1-1')::uuid
ORDER BY created_at ASC;
DELETE FROM chunk_bge_m3 WHERE id = md5('chunk-1-1-1')::uuid;
DELETE FROM chunk_bge_m3 WHERE id = md5('chunk-1-1-2')::uuid;
DELETE FROM chunk_bge_m3 WHERE id = md5('chunk-1-1-3')::uuid;
DELETE FROM chunk_bge_m3 WHERE id = md5('chunk-1-1-4')::uuid;
DELETE FROM chunk_bge_m3 WHERE id = md5('chunk-1-1-5')::uuid;
DELETE FROM chunk_bge_m3 WHERE id = md5('chunk-1-1-6')::uuid;
DELETE FROM chunk_bge_m3 WHERE id = md5('chunk-1-1-7')::uuid;
DELETE FROM chunk_bge_m3 WHERE id = md5('chunk-1-1-8')::uuid;
DELETE FROM chunk_bge_m3 WHERE id = md5('chunk-1-1-9')::uuid;
DELETE FROM chunk_bge_m3 WHERE id = md5('chunk-1-1-10')::uuid;
DELETE FROM chunk_bge_m3 WHERE id = md5('chunk-1-1-11')::uuid;
DELETE FROM chunk_bge_m3 WHERE id = md5('chunk-1-1-12')::uuid;
DELETE FROM chunk_bge_m3 WHERE id = md5('chunk-1-1-13')::uuid;
DELETE FROM chunk_bge_m3 WHERE id = md5('chunk-1-1-14')::uuid;
DELETE FROM chunk_bge_m3 WHERE id = md5('chunk-1-1-15')::uuid;
DELETE FROM chunk_bge_m3 WHERE id = md5('chunk-1-1-16')::uuid;
DELETE FROM chunk_bge_m3 WHERE id = md5('chunk-1-1-17')::uuid;
DELETE FROM chunk_bge_m3 WHERE id = md5('chunk-1-1-18')::uuid;
DELETE FROM chunk_bge_m3 WHERE id = md5('chunk-1-1-19')::uuid;
DELETE FROM chunk_bge_m3 WHERE id = md5('chunk-1-1-20')::uuid;
ROLLBACK;

\echo '=== S9 DELETE NEW pattern: single DELETE by doc_id (rolled back) ==='
BEGIN;
DELETE FROM chunk_bge_m3 WHERE doc_id = md5('doc-1-1')::uuid;
ROLLBACK;

\echo '=== S10 chunk kb_id filter cost (RAG KbIdsWhereClause proxy) ==='
SELECT count(*) FROM chunk_bge_m3 WHERE kb_id = md5('kb-1')::uuid;

\echo '=== DONE ==='
