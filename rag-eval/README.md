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

The historical `regression-v1-candidate` L2 mapping is retired and is not a
current evaluation prerequisite. Its candidate JSON, source anchors and
release manifest remain only for audit; do not restore missing internal
Markdown sources or generate a runtime mapping for that retired dataset.
Current local RAG evaluation uses the frozen
`mMARCO-zh-sampled-v3-local-diagnostic` inputs and the repository-owned
`fixture-fast-v1` fixture. The `g2-pre-bm25-v1` fixture remains the
development-only three-branch structure fixture. These
tracks have separate reports and must not be combined into a product release
claim: mMARCO covers public retrieval/rerank diagnostics, while the project
fixture covers Router, authorization, refusal, citation and multimodal
contracts.

The current fixture profile and replay-level Router comparison are covered by
`backend_v2/target/rag-eval/router/fixture-fast-v1-router-ablation.json`.
Its `executionMode` is `fixture-replay-router-contract`; its latency and token
fields are replay/contract values, not production measurements. The isolated
runtime retrieval baseline is written to
`backend_v2/target/rag-eval/current/fixture-fast-v1-runtime.json` and uses the
explicit database `127.0.0.1:55432/jchatmind_rag_eval`. It does not yet provide
Router timing, citation correctness, answer quality or an end-to-end cost
measurement.

In this profile, `expectedRoute` describes the pre-retrieval Router decision,
while `shouldAbstain` describes the final retrieval/answer outcome. Therefore,
the two no-answer/permission-boundary cases may route to `PRIVATE_RAG` before
evidence is known; owner and cross-KB permission refusal is covered separately
by the isolated PostgreSQL L2 test.

To remove the isolated data after a completed evaluation run:

```powershell
docker compose -f docker-compose.rag-eval.yml down -v
```
