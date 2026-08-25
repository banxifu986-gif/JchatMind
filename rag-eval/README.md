# Isolated RAG Evaluation Runtime

This directory is the only local runtime scope for external RAG benchmarks.
It does not share a database, Docker volume, port, or upload directory with
the application environment.

Start the runtime from the repository root:

```powershell
docker compose -f docker-compose.rag-eval.yml up -d --build
```

The runtime uses PostgreSQL at `127.0.0.1:55432`, database
`jchatmind_rag_eval`, and the VectorChord `vchord_bm25` extension. Benchmark
files belong under `rag-eval/uploads/`; application imports must point their
document storage path there. Knowledge base, document, and chunk records are
therefore isolated by this database and must carry the `rag-eval` provenance
in their import manifest and report.

`init/002-evaluation-schema.sql` creates the evaluation-only `knowledge_base`,
`document`, and `chunk_bge_m3` tables. Each record is constrained to the
single `rag-eval` namespace; chunks use `vector(1024)` and have both native
VectorChord BM25 indexes. On an already initialized local volume, run the
bootstrap once and then verify it:

```powershell
Get-Content -Raw rag-eval/init/002-evaluation-schema.sql |
  docker exec -i jchatmind-rag-eval-postgres psql -U rag_eval -d jchatmind_rag_eval -v ON_ERROR_STOP=1
& ./rag-eval/verify-isolated-schema.ps1
```

Do not place source data in this repository before its exact SHA-256 is
recorded in `external-benchmark-registry.json`. The registry is authoritative
for official URLs, revisions, license evidence, redistribution status, and
the required artifact checksums.

The current `regression-v1-candidate` L2 mapping can run only after restoring
the exact original Markdown sources whose hashes are recorded in
`backend_v2/src/test/resources/rag-eval/datasets/candidates/` and importing
them into this isolated runtime. The mapping test verifies source hashes and
headings before it accepts any runtime chunk UUID mapping.

To remove the isolated data after a completed evaluation run:

```powershell
docker compose -f docker-compose.rag-eval.yml down -v
```
