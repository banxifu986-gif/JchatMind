# RAG 检索与排序

> 页面定位：从 query 到 Top-K 的完整检索链路——查询改写、三路召回、RRF 融合、HARD 过滤、rerank、资产通道、结果缓存与双入口授权收敛。动检索通道/融合/排序前必读。
>
> 最后核对：2026-11-14（按源码核验）。

## 1. 定位

检索核心是 `RagServiceImpl`（1055 行），职责已按变化原因拆分：`RagEmbeddingClient`（embedding+LRU）、`RagFusion`（去重+RRF+provenance）、`RagRetrievalSignals`（信号合并）、`RagReranker`（本地规则/BGE 重排）、`RagRetrievalContextPolicy`（上下文归一化+HARD 过滤）、`RagRetrievalMetadata`（metadata 解析）——内部协作类包可见，不新增对外 API。检索有两个生产入口：`KnowledgeTools`（Agent 内）与 `McpKnowledgeTool`（MCP 外部客户端），都必须先过授权收敛与 `RagRouter`。

## 2. 关键文件索引

包根 `backend_v2/src/main/java/com/kama/jchatmind/`：

| 文件 | 职责 | 行数 |
| --- | --- | --- |
| [service/impl/RagServiceImpl.java](../../backend_v2/src/main/java/com/kama/jchatmind/service/impl/RagServiceImpl.java) | 检索编排：三路召回 + 资产通道 | 1055 |
| [service/impl/RagFusion.java](../../backend_v2/src/main/java/com/kama/jchatmind/service/impl/RagFusion.java) | 通道折叠、RRF、provenance | 228 |
| [service/impl/RagReranker.java](../../backend_v2/src/main/java/com/kama/jchatmind/service/impl/RagReranker.java) | 本地规则评分 / BGE 重排 | 518 |
| [service/impl/QueryRewriteServiceImpl.java](../../backend_v2/src/main/java/com/kama/jchatmind/service/impl/QueryRewriteServiceImpl.java) | 意图检测、改写、auto-context | 759 |
| [service/impl/RagRetrievalContextPolicy.java](../../backend_v2/src/main/java/com/kama/jchatmind/service/impl/RagRetrievalContextPolicy.java) | RetrievalContext 归一化、HARD 过滤 | 116 |
| [rag/RagRouter.java](../../backend_v2/src/main/java/com/kama/jchatmind/rag/RagRouter.java) | 确定性路由（6 种 route） | 148 |
| [rag/RagRetrievalResultCache.java](../../backend_v2/src/main/java/com/kama/jchatmind/rag/RagRetrievalResultCache.java) | 每入口有界结果缓存（256 条） | 161 |
| [agent/tools/KnowledgeTools.java](../../backend_v2/src/main/java/com/kama/jchatmind/agent/tools/KnowledgeTools.java) | Agent 检索工具（授权收窄） | 555 |
| [mcp/McpKnowledgeTool.java](../../backend_v2/src/main/java/com/kama/jchatmind/mcp/McpKnowledgeTool.java) | MCP 检索工具（主体授权） | 363 |
| [resources/mapper/ChunkBgeM3Mapper.xml](../../backend_v2/src/main/resources/mapper/ChunkBgeM3Mapper.xml) | 全部候选 SQL | 823 |

## 3. 运行链路

```text
retrieve(kbIds, query, context, limit) L139
→ QueryRewriteServiceImpl.rewrite：意图检测 → auto-context（仅 NAVIGATION）→ applyMode → query plan
→ retrieveWithPlan L310-416：
   ① dense-original：仅原问向量（pgvector <=>）
   ② sparse-original：标题 exact/contains/keyword/trigram + 标题 BM25 + 正文 BM25（仅原问）
   ③ expanded-query：每个扩展 query（standalone/LLM）内部 vector+sparse 先 fuseBranch 再按 query 融合
      （无扩展 query 时分支为空，不回填原问）
   → rrfFuseByRank(branches) 外层 RRF → HARD filterByContext → reranker.rerank → limit(limit)
```

**生产主链路含 expanded-query 分支、不含 query-anchor 提升**——`preserveQueryAnchors`（页码锚点与 ≥16 字符技术 token 提升，L418-496）仅在评测变体（`"current-flat"|"two-branch-original"|"three-branch-expanded"`）为 true。

### 3.1 候选配额

`TITLE_MATCH=20 / TITLE_CONTAINS=10 / TITLE_KEYWORD=10 / TITLE_TRIGRAM=10 / TITLE_FULL_TEXT=10 / CONTENT_FULL_TEXT=20`（L38-43），全部乘 `scaledCandidateLimit × scopeMultiplier`（`min(max(kbCount,1),5)`，L1028）；vector 通道 `max(limit, RERANK_CANDIDATE_LIMIT)`。三路共享总候选与 rerank 预算，不能以三倍候选绕过前 50 rerank 上限。

### 3.2 RRF 与 provenance

`RagFusion.rrfFuse`：contribution = `1/(RRF_K+rank)`，`RRF_K=60`；`vector_*→"vector"`、`title_*→"title"` 同组折叠（同 chunk 取 bestRank）；provenance 格式 `branch:channel:querySource`，保留到 `RagRetrievalResult.retrievalProvenance`。同一 chunk 同一分支最多贡献一次、跨分支最多三次。

### 3.3 rerank（`RagReranker`）

前 `RERANK_CANDIDATE_LIMIT=50` 送重排；`rag.rerank.enabled=true` 时调用 TEI（batch ≤32、串行、严格校验，违例抛异常→本地回退），否则本地规则评分：

- titleExact 0.45 / titleContains 0.25 / titleOverlap×0.12 / contentContains 0.22 / 反向 0.14 / contentOverlap×0.16 + bm25 title(0.05,0.03) + bm25 content(0.10,0.05) + rankPenalty `min((rank-1)*0.03, 0.15)`；`GENERIC_LEAF_TITLES={回答,原理,总结,方案}` 降权。
- TEI 超时/不可用/非法响应 → 回退本地规则（候选排名 penalty 封顶 0.15，预算外候选保留 RRF 原序）。

### 3.4 RagRouter（确定性路由，L56）

| 输入特征 | 路由 | topK |
| --- | --- | --- |
| 空 query | CLARIFY | — |
| 精确问候词 | DIRECT | — |
| 无授权范围 / `管理员密码`/`数据库密码` / 他人私有措辞 / 无私有证据 | ABSTAIN | — |
| EXTERNAL_TERMS（未许可 / 已许可） | ABSTAIN / EXTERNAL_TOOL | 5 |
| MULTIMODAL_TERMS | MULTIMODAL_RAG（CONTEXTUAL） | 5 |
| 对比/区别/官方 | HYBRID_RAG | 5 |
| 默认 | PRIVATE_RAG（LIGHT） | 3 |

视觉 hint（L32）：需图片证据 + 命中 RAG 路由时，授权内无资产→CLARIFY，有→MULTIMODAL_RAG。MCP 检索入口不经过 Agent 意图层。ABSTAIN/CLARIFY/DIRECT 结果在 MCP 侧也写审计（ABSTAIN 记 DENY）。

### 3.5 QueryRewriteServiceImpl

- 意图检测（L158）：路径含 `>` 或 `.md`/`.markdown` → NAVIGATION；ANALYTICAL_MARKERS（为什么/原理/区别/设计/思路/流程/架构/如何设计/怎么设计/tradeoff）→ ANALYTICAL；有 context + 低信息（FOLLOW_UP_MARKERS 或 ≤18 字符且 ≤4 词且非路径）+ 无话题切换 → FOLLOW_UP；否则 FACTOID。
- applyMode（L174）：无 context→NONE；`sessionContextBias`→SOFT；NAVIGATION→话题切换?SOFT:HARD；FOLLOW_UP 无切换→HARD；其余 SOFT。
- query plan（L196）：LLM 改写（`rag.query-rewrite.llm.enabled` 默认 false；输出消毒：剥前缀引号、>160 字符或与原问相同即拒绝）或 FOLLOW_UP standalone（`sourceName + " " + contentPath + " " + query`）在前，`original` 恒在最后；去重后最多 2 条。
- auto-context（L437）：`selectTitlePathCandidatesByKbIds`（XML LIMIT 500，按最旧优先）打分 `title×0.55 + path×0.30 + source×0.15`；门禁 `best ≥ 0.52` 且 `best-second ≥ 0.12`。

## 4. 资产通道

`retrieveAssetCandidates`（L231）：仅 `MULTIMODAL_RAG` 触发，每条 retrievalQuery 一次向量资产 SQL → RRF → limit；**无 HARD 后置过滤（过滤在 SQL LIMIT 前）、无 rerank**。三通道经 `chunk_bge_m3 → document_asset_chunk → document_asset` 只取 `READY` 资产：

- 表格：provenance `asset_table_<query-source>`，按原始表格所在 chunk + 行号定位，无单元格级语义。
- PDF 页文本：provenance `asset_pdf_page_text_<query-source>`。
- 图片：IMAGE 候选 SQL LEFT JOIN `document_asset_extraction`(ACTIVE) 并 `jsonb_set` 注入 `asset.visualKind`；metadata.asset 含完整 id/type/locator。

入口合并顺序：**表格 → PDF → 图片 → 普通检索**，chunkId 去重后按 `route.topK()` 截断。metadata 含完整 `asset.id`+`asset.type` 时 chunk 引用附加 `资产: <type>:<id>`；PDF 显示页码；图片显示行号 `start-end`。

## 5. 授权收敛（双入口）

范围按层收敛，后层只能缩小前层（详见 [01](01-系统总览与运行边界.md) §7、架构文档 §4.3.1）：

1. 后端硬权限：`KnowledgeBaseAccessService` owner-only，`knowledge_base.owner_id`。
2. Agent 默认范围：`agent_knowledge_base` 是唯一持久化事实来源，API 的 `allowedKbs` 只是投影（无物理列，见 catalog-contract `forbiddenColumns`）。
3. 运行时收窄：`KnowledgeTools` 由 Factory 每会话 fork 并绑定授权 KB；模型传入 `kbIds` 只能收窄；MCP 侧 `requireAccessibleKnowledgeBaseIds` 以服务端解析主体为准。

**置信度门禁**（`KnowledgeTools.java` L34-35）：RRF 最低分 `MIN_CONTEXT_RRF_SCORE=0.02`、Top-1/Top-2 差值 `MIN_CONTEXT_RRF_GAP=0.002`；低置信度收束为证据不足，不更新会话 context。

## 6. 结果缓存（`RagRetrievalResultCache`）

每入口有界缓存（256 条）：key 含 `requesterId / kbIds(sorted distinct) / indexRevisions(TreeMap) / query / context / variant / routeFingerprint / limit / configurationFingerprint`（指纹 = `rag-retrieval-v1|embeddingModel|rerankDebug|disableQueryExpansion|disableRerank|query-rewrite指纹|bge-reranker指纹`，L117-124）。`requesterId` 为 null 直接绕过；每次命中先做授权检查；读写均深拷贝。失效靠 order 190 触发器维护的 `rag_kb_index_revision`。

## 7. 不变量与契约

1. 所有叶子查询必须在数据库 `LIMIT` 前执行授权 KB 与 HARD（来源/类型/路径）过滤；HARD 才把 kbIds 收敛到 context.kbId（`scopedKbIds`，不在则空集）。
2. `TITLE_TRIGRAM_MIN_SCORE=0.18`（L45）。
3. 混合真实/合成工具结果时按模型原始调用顺序排序（Harness 层保证，见 [06](06-工具Harness.md)）。
4. 维护约束：通道变更、RRF 参数、rerank 策略改动必须走冻结评测（[14](14-评测体系与门禁.md)），当前默认检索结构为 R0（三路分支未满足默认放行门禁，TC-G2-10 结构消融未过线）。
5. `selectTitlePathCandidatesByKbIds` 固定 `ORDER BY created_at, id LIMIT 500`——超大 KB 的 auto-context 候选按最旧优先截断。

## 8. 明确边界

不做：受控外部工具检索、evidence threshold、图片/OCR/公式/表格单元格关系/坐标回跳检索、LightRAG 图谱、跨 Query 高扇出生成器（最多 2 条 query plan）、默认三路切换（门禁未过）。

## 9. 验证入口

| 测试 | 门控 |
| --- | --- |
| `RagRouterTest`、`RagRouterVisualHintTest`、`RagAsMetricsTest`、`RagRetrievalResultCacheTest`、`RagIndependentBranchEvaluatorTest`、mapper 契约测试 | 默认跑 |
| `RagFastRegressionEvaluatorTest`（L1 replay，含 p95 断言 27ms/45ms） | 默认跑 |
| `G2CurrentFixtureRuntimeImportL2Test` | `-Dg2.current.fixture.runtime.enabled=true` |
| `RagDatasetE2eEvaluationL2Test` | `-Drag.eval.dataset.e2e.enabled=true` |

## 10. 关联文档

- 摄入与索引契约：[07-RAG摄入与索引](07-RAG摄入与索引.md)
- 评测门禁与冻结集：[14-评测体系与门禁](14-评测体系与门禁.md)
- MCP 入口细节：[11-MCP服务端](11-MCP服务端.md)
- G2 目标架构与选型决策：架构文档 §4.4、[../archive/2026-11-10/plans/terminated/trusted-knowledge-agent-roadmap.md](../archive/2026-11-10/plans/terminated/trusted-knowledge-agent-roadmap.md)
