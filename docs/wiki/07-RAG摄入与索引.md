# RAG 摄入与索引

> 页面定位：文档从上传到可检索的完整链路——解析、分块契约、embedding、BM25 投影、多模态资产提取。动上传/解析/chunk 结构前必读。
>
> 最后核对：2026-11-14（按源码核验）。

## 1. 定位

摄入是**文档级全量重建**：同一文档再次摄入时，处理器在同一数据库事务内删除该 docId 的全部旧 chunk 与资产，再解析、向量化并写入整篇新 chunk；任一写入失败整体回滚。不是内容哈希增量，也不支持 chunk 级更新。**任何影响解析、chunk 边界、metadata、embedding 模型或输入拼接的改动，必须升级评测数据集/索引版本**（BM25 `INDEX_VERSION=1`），不能与旧 chunk 结果直接对比。

## 2. 关键文件索引

包根 `backend_v2/src/main/java/com/kama/jchatmind/`：

| 文件 | 职责 | 行数 |
| --- | --- | --- |
| [service/impl/DocumentFacadeServiceImpl.java](../../backend_v2/src/main/java/com/kama/jchatmind/service/impl/DocumentFacadeServiceImpl.java) | 上传门面：owner 校验、幂等、补偿 | 316 |
| [service/impl/DocumentStorageServiceImpl.java](../../backend_v2/src/main/java/com/kama/jchatmind/service/impl/DocumentStorageServiceImpl.java) | 文件落盘、部分写补偿、KB 目录删除（防穿越） | 145 |
| [service/impl/IngestionTaskServiceImpl.java](../../backend_v2/src/main/java/com/kama/jchatmind/service/impl/IngestionTaskServiceImpl.java) | 任务创建/重放（advisory lock）、状态机收束 | 331 |
| [service/impl/MarkdownParserServiceImpl.java](../../backend_v2/src/main/java/com/kama/jchatmind/service/impl/MarkdownParserServiceImpl.java) | md/txt/html/pdf 解析与分块 | 738 |
| [ingestion/DefaultIngestionTaskProcessor.java](../../backend_v2/src/main/java/com/kama/jchatmind/ingestion/DefaultIngestionTaskProcessor.java) | 事务内解析→资产→删旧→逐块写入 | 613 |
| [ingestion/IngestionTaskConsumer.java](../../backend_v2/src/main/java/com/kama/jchatmind/ingestion/IngestionTaskConsumer.java) | RabbitMQ 领取、重试/死信投递 | 94 |
| [ingestion/IngestionTaskStateMachine.java](../../backend_v2/src/main/java/com/kama/jchatmind/ingestion/IngestionTaskStateMachine.java) | 7 状态 8 事件状态机 | 65 |
| [ingestion/DocumentImageExtractionWorker.java](../../backend_v2/src/main/java/com/kama/jchatmind/ingestion/DocumentImageExtractionWorker.java) | 默认关闭的视觉抽取轮询 worker | 182 |
| [util/RagChunkSupport.java](../../backend_v2/src/main/java/com/kama/jchatmind/util/RagChunkSupport.java) | metadata / embedding 输入组装 | 79 |
| [typehandler/PgVectorTypeHandler.java](../../backend_v2/src/main/java/com/kama/jchatmind/typehandler/PgVectorTypeHandler.java) | float[] ↔ `"[v,..]"` Types.OTHER | 53 |
| [controller/DocumentController.java](../../backend_v2/src/main/java/com/kama/jchatmind/controller/DocumentController.java) | `/api/documents`（upload 带 `Idempotency-Key` 头） | — |

## 3. 运行链路

```text
POST /api/documents/upload（Idempotency-Key 必填）
→ DocumentFacadeServiceImpl.uploadDocument：KB owner 校验 → 幂等预检 → 插入 document 记录
   → 【事务外】saveFile → data/documents/<kbId>/<docId>/UUID+ext
   → 事务内：updateByIdAndOwnerId + IngestionTaskServiceImpl.submitDocumentIngestion
      （事务级 advisory lock → 幂等重放校验 → insert QUEUED）
→ publishAfterCommit（注意：无活动事务时立即 publish，L292-296）
→ RabbitMQ ingestion.queue（2 消费者 / prefetch 1，AUTO ack）
→ IngestionTaskConsumer.onMessage：剥 JSON 引号 → claimTask CAS（RETRYING 先 REQUEUE→QUEUED 再 START→RUNNING）
→ DefaultIngestionTaskProcessor.process（@Transactional）：
   校验 document/kbId 一致 → parseSections → 建 TABLE/IMAGE/PDF_PAGE_TEXT 资产
   → documentAssetMapper.deleteByDocumentId + chunkBgeM3Mapper.deleteByDocId
   → 逐 section：ragService.embed(buildChunkEmbeddingText) + vchordBm25ProjectionService.project
      → insert（任一失败抛"文档分块写入失败"整体回滚）→ 资产-chunk 关联
→ completeClaimedTask；失败 failClaimedTask（attempt+1，≥maxAttempts→DEAD_LETTER，否则 RETRYING→retry 队列）
→ 终态写 webhook outbox 事件（SUCCEEDED→INGESTION_SUCCEEDED；FAILED/DEAD_LETTER→INGESTION_FAILED）
```

**上传时序事实**：文件在 ingestion 任务事务**之前**落盘，document 记录先插（自动提交事务）；任何失败走 `compensateUpload`（删文件 + 删 document 行），不影响已有成功上传。`DocumentStorageServiceImpl.saveFile` copy 中途失败 `compensatePartialWrite`（删目标文件 + 仅删除为空的目录）；`deleteKnowledgeBaseDirectory` normalize 后校验父目录必须等于 base（防路径穿越）。

任务查询/取消/重试仅 owner；取消只允许 `QUEUED/RETRYING`；`retryTask` 将 attemptCount 归 0 后 afterCommit 重投递。任务输出不暴露 `ownerId` 或幂等键。

## 4. 分块契约

- **支持格式**：`md/markdown/txt/html/pdf`；其他类型记"不支持的文档类型"任务失败；空解析结果回退单文档 section（title=去扩展名文件名或"文档"）。
- **Markdown/TXT**（Flexmark AST）：每个标题 + 到下一标题前的直属内容 = 一个 section；标题前内容为"文档前言"；无标题回退单"文档" section。父标题**不继承**子标题正文；表格保留原始 Markdown 并创建 `TABLE` 资产。
- **HTML**：移除注释与 `script/style/noscript/template/head`，块级标签转换行、删标签、解码实体、合并空白，再按 `h1-h6` 组织 section。
- **PDF**（PDFBox 2.0.24）：逐页提取非空文本写入 `pageNumber`，每页创建 `PDF_PAGE_TEXT` 资产并把资产 ID/类型/页码 locator 写回 chunk metadata；**扫描件无 OCR 回退 → 摄入失败**。
- **长度规则**：section ≤2000 字符整块；超限从 `start+2000` 回扫到 `start+min(1000, max/2)` 找 `\n 。！？.!?；;` 自然边界，找不到才硬切；子块继承相同 metadata；**overlap=0**（类支持 overlap 但生产构造固定 0，`MarkdownParserServiceImpl` L32-33,462-476）。
- **清洗边界**：仅结构提取、HTML 噪声规整、空 PDF 页跳过、trim——无页眉页脚/目录/去重/乱码修复/敏感信息清洗。

## 5. metadata 与 embedding 契约

- **embedding 输入**：`contentPath(缺省 title) + "\n" + title + "\n" + content.trim()`，content 空时仅标题——章节上下文参与语义召回（`RagChunkSupport` L68-78）。Ollama `/api/embed` `{model, input, keep_alive:-1}`，30s 超时；实例内 LRU 容量 `rag.embedding.cache.max-entries:2048`（0 旁路），key=trim 原文，读写均 clone。
- **metadata 字段全清单**：`title`、`retrievableTitle`、`retrievableTitleSearchText`（BM25 标题投影输入）、`contentPath`、`parentContentPath`、`sourceType`、`sourceName`、`sectionIndex`、`headingLevel`、`hasChildren`、`sectionType`（`PARENT_OVERVIEW/LEAF_QA/LEAF_CONTENT`，LEAF_QA=标题恰为"回答/原理/总结/方案"）、`pathDepth`、`localContentLength`；PDF 加 `pageNumber`；关联资产加 `asset{id,type,locator[,visualKind]}`。
- **BM25 投影**（`VchordBm25ProjectionService`）：token 词典表 `rag_bm25_token_dictionary` 分配稳定 ID，`INDEX_VERSION=1`；向量格式 `{tokenId:freq,...}`（TreeMap 按 tokenId 排序）；标题/正文/词典三列全空或全非空 CHECK。**分词**：非 CJK token ≥2 字符；CJK 相邻双字 bigram（单字保留）；normalize 小写+空白折叠。

## 6. 物理索引

| 索引 | 定义 | 出处 |
| --- | --- | --- |
| 向量 | `chunk_bge_m3.embedding vector(1024)`，`ivfflat (embedding vector_cosine_ops) WITH (lists = 100)`，查询 `<=>` 余弦距离 | [sql/user-memory/cosine_index.sql](../../sql/user-memory/cosine_index.sql) |
| BM25 | VectorChord-bm25：`title_bm25_vector` / `content_bm25_vector`，查询强制 `bm25_index_version` 匹配，`SET LOCAL search_path = bm25_catalog, ...` | [sql/knowledge-base/2026-08-22-add-vchord-bm25-index.sql](../../sql/knowledge-base/2026-08-22-add-vchord-bm25-index.sql)、`VchordBm25QueryService` |
| trgm（order 260） | GIN trgm，表达式与 mapper 谓词逐字符一致（lower+regexp_replace+trim 的 retrievableTitle / contentPath） | `2026-11-12-add-title-trgm-indexes.sql` |
| hot-filter（order 250） | chunk(doc_id/kb_id)、chat_message(session_id)、chat_session(user_id)、user_memory(user_id) 5 个 B-tree | `2026-11-12-add-hot-filter-indexes.sql` |
| 索引版本跟踪 | order 190 触发器跟踪 chunk/document/asset/asset-chunk 变更 → `rag_kb_index_revision`，RAG 结果缓存据此失效 | `2026-10-21-create-rag-index-revision.sql` |

存量 chunk 的 BM25 投影回填走 `VchordBm25BackfillService`（FOR UPDATE 批量 ≤500）。

## 7. 多模态资产

- **提取**：Markdown 表格→`TABLE` 资产；PDF 内嵌图片（PDFBox 流引擎，PNG 写 `kb/docId/derived/image-<hash>.png`）与 Markdown/HTML 相对图片引用（跳过外链与 data URI，记录章节路径/行号）→`IMAGE` 资产；按内容 hash 命名，重跑无残留。资产表 UNIQUE(document_id, asset_type, asset_key)，类型 CHECK `{PDF_PAGE_TEXT, IMAGE, TABLE, FORMULA}`（**FORMULA 仅 DB 允许，代码不产出**），status `{PENDING, READY, FAILED}`。
- **视觉抽取 worker**（`rag.visual-extraction.enabled` 默认 false）：30s 轮询 + `resetStalePending(10)` 超时重置 + 单线程 + 120s 超时 + max-attempts 3；按 hash+prompt/model/parser 版本天然缓存（`promptVersion="image-extraction-prompt-v1"`）；provider 可选 ollama（qwen2.5vl:7b）/ deepseek / zhipu；契约校验后落 `document_asset_extraction`（ACTIVE 唯一部分索引、旧结果 SUPERSEDED）+ 实体/关系表 + 视觉 chunk。
- **意图联动**：`requiresVisualEvidence` hint → 路由 MULTIMODAL_RAG（授权内无图片资产→CLARIFY）；`VisualEvidenceAnswerService`（`rag.visual-extraction.answer-enabled` 默认 false）对 top-k 图片生成读图证据块，强制 `[assetId=...]` 引用标记，单图失败跳过。
- 详见 [08-RAG检索与排序](08-RAG检索与排序.md) §资产通道。

## 8. 明确边界

不做：内容哈希增量摄入、chunk 级更新、扫描件 OCR、公式资产、页眉页脚/重复 chunk 清洗、多语言识别、摄入任务的动态扩缩与跨实例协调（本地并发 = 2 消费者 × prefetch 1）、摄入事件持久化（进度 SSE 仅内存 64 帧）。

## 9. 配置项

| Key | 默认 | 出处 |
| --- | --- | --- |
| `rag.rerank.enabled` / `base-url` / `timeout-ms` | false / http://127.0.0.1:8081 / 3000 | `BgeRerankerService` |
| `rag.query-rewrite.llm.enabled` / `.model` | false / deepseek-chat | `QueryRewriteServiceImpl` L81-82 |
| `rag.embedding.cache.max-entries` | 2048 | `RagServiceImpl` L74 |
| `rag.visual-extraction.enabled`（及 `.max-attempts/.timeout-seconds/.vision-provider/...`） | false / 3 / 120 / ollama | `DocumentImageExtractionWorker` 等 |
| `rag.eval.disable-query-expansion` / `disable-rerank` | false / false | `RagServiceImpl` 构造器 |
| `document.storage.base-path`（`DOCUMENT_STORAGE_BASE_PATH`） | ./data/documents | application.yaml |

## 10. 验证入口

单测：`RagRouterTest`、`RagRetrievalResultCacheTest`、`VchordBm25ProjectionServiceTest`、`ChunkBgeM3MapperHardContextContractTest`、`ChunkBgeM3MapperMarkdownTableAssetCandidateContractTest`、`ChunkBgeM3MapperPdfAssetCandidateContractTest`（默认跑）。L2：`G2KnowledgeToolsPermissionRuntimeL2Test`、`G2MarkdownTableAssetCandidateMapperRuntimeL2Test`、`VectorChordBm25*` 系列、`VchordBm25QueryServiceL2Test`（独立容器）。评测分层见 [14](14-评测体系与门禁.md)。

## 11. 关联文档

- 检索与排序：[08-RAG检索与排序](08-RAG检索与排序.md)
- KB 授权与受控删除：[08](08-RAG检索与排序.md) §授权、[13](13-数据模型与迁移.md)
- 评测与门禁：[14-评测体系与门禁](14-评测体系与门禁.md)
- 归档：[../archive/2026-08-15/records/rag/](../archive/2026-08-15/records/rag/)
