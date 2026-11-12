#!/usr/bin/env bash
# Percentile benchmark over jchatmind_perf.
# Repeats each scenario N times in a single psql session, sums the per-statement
# \timing values of each repetition, and reports p50/p95/p99 per scenario.
# Usage: bash 05-pct-bench.sh [N=100] [out=pct-raw.txt]
set -euo pipefail

N="${1:-100}"
OUT="${2:-pct-raw.txt}"

emit() {
  local name="$1"
  local sql
  sql=$(cat)
  local i
  for ((i = 1; i <= N; i++)); do
    printf '%s\n' "\\echo 'REP $name $i'"
    printf '%s\n' "$sql"
    printf '\n'
  done
}

{
  printf '\\timing on\n'

  emit s1_selectBySessionId <<'EOS'
SELECT
    message.id, message.session_id, message.role, message.content,
    message.metadata::text AS metadata, message.created_at, message.updated_at
FROM chat_message message
LEFT JOIN agent_execution_turn_message turn ON turn.chat_message_id = message.id
WHERE message.session_id = md5('session-1')::uuid
ORDER BY turn.sequence_no ASC NULLS FIRST, message.created_at ASC, message.id ASC;
EOS

  emit s2_selectByUserId <<'EOS'
SELECT id, user_id, agent_id, title, metadata::text AS metadata, created_at, updated_at
FROM chat_session
WHERE user_id = 'perf-user-1'
ORDER BY updated_at DESC;
EOS

  emit s3_selectActiveByUserId <<'EOS'
SELECT id, user_id, session_id, memory_type, content, importance,
       evidence_message_id, evidence_text, superseded_by_memory_id, expires_at,
       embedding, created_at, updated_at
FROM user_memory
WHERE user_id = 'perf-muser-1'
  AND superseded_by_memory_id IS NULL
  AND (expires_at IS NULL OR expires_at > NOW())
ORDER BY updated_at DESC;
EOS

  emit s3b_typedLimit1 <<'EOS'
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
EOS

  emit s4_old_documentsLoop <<'EOS'
SELECT id, kb_id, filename, filetype, size, metadata::text AS metadata, created_at, updated_at FROM document WHERE kb_id = md5('kb-1')::uuid ORDER BY updated_at DESC;
SELECT id, kb_id, filename, filetype, size, metadata::text AS metadata, created_at, updated_at FROM document WHERE kb_id = md5('kb-2')::uuid ORDER BY updated_at DESC;
SELECT id, kb_id, filename, filetype, size, metadata::text AS metadata, created_at, updated_at FROM document WHERE kb_id = md5('kb-3')::uuid ORDER BY updated_at DESC;
SELECT id, kb_id, filename, filetype, size, metadata::text AS metadata, created_at, updated_at FROM document WHERE kb_id = md5('kb-4')::uuid ORDER BY updated_at DESC;
SELECT id, kb_id, filename, filetype, size, metadata::text AS metadata, created_at, updated_at FROM document WHERE kb_id = md5('kb-5')::uuid ORDER BY updated_at DESC;
SELECT id, kb_id, filename, filetype, size, metadata::text AS metadata, created_at, updated_at FROM document WHERE kb_id = md5('kb-6')::uuid ORDER BY updated_at DESC;
SELECT id, kb_id, filename, filetype, size, metadata::text AS metadata, created_at, updated_at FROM document WHERE kb_id = md5('kb-7')::uuid ORDER BY updated_at DESC;
SELECT id, kb_id, filename, filetype, size, metadata::text AS metadata, created_at, updated_at FROM document WHERE kb_id = md5('kb-8')::uuid ORDER BY updated_at DESC;
SELECT id, kb_id, filename, filetype, size, metadata::text AS metadata, created_at, updated_at FROM document WHERE kb_id = md5('kb-9')::uuid ORDER BY updated_at DESC;
SELECT id, kb_id, filename, filetype, size, metadata::text AS metadata, created_at, updated_at FROM document WHERE kb_id = md5('kb-10')::uuid ORDER BY updated_at DESC;
EOS

  emit s4_new_documentsSingleIn <<'EOS'
SELECT id, kb_id, filename, filetype, size, metadata::text AS metadata, created_at, updated_at
FROM document
WHERE kb_id IN (md5('kb-1')::uuid, md5('kb-2')::uuid, md5('kb-3')::uuid, md5('kb-4')::uuid, md5('kb-5')::uuid,
                md5('kb-6')::uuid, md5('kb-7')::uuid, md5('kb-8')::uuid, md5('kb-9')::uuid, md5('kb-10')::uuid)
ORDER BY updated_at DESC;
EOS

  emit s5_old_bindingsLoop <<'EOS'
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
EOS

  emit s5_new_bindingsSingleIn <<'EOS'
SELECT agent_id, kb_id
FROM agent_knowledge_base
WHERE agent_id IN (md5('agent-1')::uuid, md5('agent-2')::uuid, md5('agent-3')::uuid, md5('agent-4')::uuid, md5('agent-5')::uuid,
                   md5('agent-6')::uuid, md5('agent-7')::uuid, md5('agent-8')::uuid, md5('agent-9')::uuid, md5('agent-10')::uuid,
                   md5('agent-11')::uuid, md5('agent-12')::uuid, md5('agent-13')::uuid, md5('agent-14')::uuid, md5('agent-15')::uuid,
                   md5('agent-16')::uuid, md5('agent-17')::uuid, md5('agent-18')::uuid, md5('agent-19')::uuid, md5('agent-20')::uuid,
                   md5('agent-21')::uuid, md5('agent-22')::uuid, md5('agent-23')::uuid, md5('agent-24')::uuid, md5('agent-25')::uuid)
ORDER BY bound_at, kb_id;
EOS

  emit s6_old_accessLoop <<'EOS'
SELECT id, name, description, metadata::text AS metadata, owner_id, created_at, updated_at FROM knowledge_base WHERE id = md5('kb-1')::uuid;
SELECT id, name, description, metadata::text AS metadata, owner_id, created_at, updated_at FROM knowledge_base WHERE id = md5('kb-2')::uuid;
SELECT id, name, description, metadata::text AS metadata, owner_id, created_at, updated_at FROM knowledge_base WHERE id = md5('kb-3')::uuid;
SELECT id, name, description, metadata::text AS metadata, owner_id, created_at, updated_at FROM knowledge_base WHERE id = md5('kb-4')::uuid;
SELECT id, name, description, metadata::text AS metadata, owner_id, created_at, updated_at FROM knowledge_base WHERE id = md5('kb-5')::uuid;
EOS

  emit s6_new_accessBatchIn <<'EOS'
SELECT id, name, description, metadata::text AS metadata, owner_id, created_at, updated_at
FROM knowledge_base
WHERE id IN (md5('kb-1')::uuid, md5('kb-2')::uuid, md5('kb-3')::uuid, md5('kb-4')::uuid, md5('kb-5')::uuid);
EOS

  emit s7_old_titlePathFull <<'EOS'
SELECT id AS chunk_id, kb_id, doc_id, content, metadata::text AS metadata,
       4::double precision AS distance,
       ROW_NUMBER() OVER (ORDER BY created_at ASC, id ASC) AS rank
FROM chunk_bge_m3
WHERE kb_id IN (md5('kb-1')::uuid)
  AND COALESCE(metadata->>'retrievableTitle', metadata->>'title', '') <> ''
  AND COALESCE(metadata->>'contentPath', '') <> '';
EOS

  emit s7b_new_titlePathLimit500 <<'EOS'
SELECT id AS chunk_id, kb_id, doc_id, content, metadata::text AS metadata,
       4::double precision AS distance,
       ROW_NUMBER() OVER (ORDER BY created_at ASC, id ASC) AS rank
FROM chunk_bge_m3
WHERE kb_id IN (md5('kb-1')::uuid)
  AND COALESCE(metadata->>'retrievableTitle', metadata->>'title', '') <> ''
  AND COALESCE(metadata->>'contentPath', '') <> ''
ORDER BY created_at ASC, id ASC
LIMIT 500;
EOS

  emit s9_old_selectPlus20Deletes <<'EOS'
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
EOS

  emit s9_new_singleDelete <<'EOS'
BEGIN;
DELETE FROM chunk_bge_m3 WHERE doc_id = md5('doc-1-1')::uuid;
ROLLBACK;
EOS

  emit s10_kbFilterCount <<'EOS'
SELECT count(*) FROM chunk_bge_m3 WHERE kb_id = md5('kb-1')::uuid;
EOS

  emit s8_docFilterSelect <<'EOS'
SELECT id FROM chunk_bge_m3 WHERE doc_id = md5('doc-1-1')::uuid;
EOS
} > pct-bench.sql

echo "generating ${N} reps per scenario -> pct-bench.sql"

docker exec -i jchatmind-postgres psql -U postgres -d jchatmind_perf -f - < pct-bench.sql 2>&1 \
  | grep -E "^REP |^Time:" > "$OUT"

echo "raw output -> $OUT ($(grep -c '^REP ' "$OUT") reps)"

awk '
  /^REP /  {
    if (name != "") printf "%s %d %.4f\n", name, idx, sum
    name = $2; idx = $3; sum = 0; next
  }
  /^Time:/ { sum += $2 }
  END      { if (name != "") printf "%s %d %.4f\n", name, idx, sum }
' "$OUT" | sort -k1,1 -k3,3n | awk '
  { cnt[$1]++; val[$1, cnt[$1]] = $3 }
  function ceil(f, n) { i = f * n; return (i == int(i)) ? i : int(i) + 1 }
  END {
    for (k in cnt) {
      n = cnt[k]
      printf "%-30s n=%d  p50=%.3f  p95=%.3f  p99=%.3f  min=%.3f  max=%.3f\n",
        k, n, val[k, ceil(0.50, n)], val[k, ceil(0.95, n)], val[k, ceil(0.99, n)], val[k, 1], val[k, n]
    }
  }
' | sort
