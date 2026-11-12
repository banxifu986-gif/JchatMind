# Data Model: kb-synthetic-v1

实体、字段、校验规则与关系。字段级 JSON 契约见 [contracts/](./contracts/)。

## 实体关系

```
CorpusDocument (100) 1──n Chunk (1200~2200, inventory 定义)
CorpusDocument 1──n EvalCase (goldChunkIds 引用其 chunk)
EvalCase (2000) 0..1──1 AnswerGold (180, 仅子集)
DatasetManifest 1──1 EvalCase 文件 + Corpus 文件集合（sha256 链）
RegistryEntry 1──n ArtifactChecksum (case/语料汇总/answer-gold/inventory)
BuildState / ReviewLog / OutOfScopeTopics —— 构建侧辅助实体（working/ 下）
```

## 1. CorpusDocument（语料文档）

| 字段 | 说明 | 校验 |
|---|---|---|
| documentId | `handbook-01`/`faq-01`/`techdoc-01`/`opsdoc-01` 系列 | 唯一，与文件名一致 |
| category | handbook(30)/faq(25)/techdoc(30)/opsdoc(15) | 四类分布精确 |
| structure | `# H1` + 前言 + 全 `##`/`###` 章节 | 无行首 `#`、无图片语法、无跨节超长表格 |
| leafSectionLength | 每叶子 section 正文 300~1900 字符 | 越界即重试/人工处理 |
| plantedFacts | 2~3 个精确数字事实 | 逐字出现于正文 |
| plantedHardNegatives | 1~2 个跨文档同名近义 section 标题 | hard-negative 标题对登记 |
| englishSections | 部分文档含全英文小节 | 供 `_en` query 采样 |

## 2. Chunk（chunk-inventory.jsonl，每行一条）

| 字段 | 说明 | 校验 |
|---|---|---|
| logicalChunkId | `{docId}#{contentPath}#{chunkIndex}` | 唯一 |
| sourceDocumentId | 来源文档 | 必在 100 份清单内 |
| contentPath | 2~4 级标题路径（`技术文档>接口鉴权>令牌刷新`） | 与解析器产出一致 |
| title / content | 标题与全文 | content 为解析器真实产出 |

**权威性**: 由 Java 生成器以真实 `MarkdownParserServiceImpl` 解析产出，是 gold 对齐与 chunk 总量验收（500~2500，2026-11-11 按实际分块合并行为修正：生产分块器会把 <1000 字符的相邻 section 合并，100 份文档实测 617 chunk，原 1200~2200 估算未考虑合并）的唯一事实源。

## 3. EvalCase（cases/kb-synthetic-v1.jsonl，2000 行）

严格 15 字段（与 `RagEvaluationCase` record 一致，**不得增删**）：

| 字段 | 说明 | 校验 |
|---|---|---|
| caseId / datasetId | 数据集内唯一；datasetId=kb-synthetic-v1 | loader 强校验 |
| query / queryType / difficulty | queryType 六类 + `_en` 变体（title_path_en / content_rewrite_en / cross_document_en） | 六类占比 R3.1（±2%）；英文总量 ~8% |
| conversation | 多轮前置轮次（follow_up 类必填） | 参照 fixture-fast-v1-007 结构 |
| kbScope | 非空 | loader 强校验 |
| goldChunkIds | 必在 inventory；可回答 case 非空 | loader :84-89 强校验 |
| goldFacts | 证据串（空白归一化后）为对应 chunk content 子串 | 自动校验 |
| sourceDocumentIds | 与 gold chunk 的 sourceDocumentId 一致 | 自动校验 |
| shouldAbstain / abstentionReason | 拒答 case 无 gold、原因必填 | loader 强校验 |
| labels | `no-answer` / `permission-boundary` 等标记 | — |
| createdBy | `synthetic`（人工修改过改 `manual`） | 治理规范 §8.1 |
| reviewStatus | 冻结前全部 `approved` | loader :78 强校验 |

**去重**: query 归一化后 4-gram Jaccard ≥0.7 剔除；与 fixture-fast-v1 20 条跨集查重。

**拒答构成**: out_of_scope ~90 / missing_evidence ~70 / permission_denied ~40（对应 out-of-scope-topics.json 登记清单）。

## 4. AnswerGold（answer-gold/kb-synthetic-v1-answer-gold.jsonl，180 行）

| 字段 | 说明 | 校验 |
|---|---|---|
| caseId | 与 cases.jsonl 一致 | 存在性校验 |
| decision | answer/clarify/refuse/handoff | refuse 类 citations 为空 |
| reference_answer / required_facts | 标准答案与必备事实 | required_facts 可人工核验 |
| citations | document_id#section + supporting_text（逐字） | supporting_text 为原文子串 |
| forbidden_behaviors / grading | 禁止行为与评分配置 | 格式复用 ecommerce-small-appliances gold_answers |

**抽样**: no_answer 40 / cross_document 40 / multi-turn 30 / 含数字事实 medium-hard 60 / 英文 30 / 补齐至 180。

## 5. DatasetManifest（manifests/kb-synthetic-v1.json）

严格 11 字段（与 `RagEvaluationDatasetManifest` record 一致）：`datasetId / schemaVersion="1" / caseFile / caseSha256 / corpusFiles / corpusSha256 / chunkingVersion="markdown-section-v1" / embeddingModel="bge-m3" / embeddingInputVersion="title-title-content-v1" / defaultTopK=10 / status="frozen"`。

- caseSha256 = case 文件原始字节 SHA-256
- corpusSha256 = corpusFiles 路径字典序拼 `basename+"\n"+全文+"\n"` 整体 SHA-256（复刻 `RagEvaluationDatasetLoader.java:97-102`）

## 6. RegistryEntry（rag-eval/external-benchmark-registry.json 新增条目）

`kbSyntheticV1`：original_synthetic、verifiedAt、artifacts sha256 四项（case / 语料汇总 / answer-gold / chunk-inventory）、checksumPolicy 与现有 `requiredBeforeImport` 规则对齐。

## 7. 构建侧辅助实体（working/）

| 实体 | 文件 | 内容 |
|---|---|---|
| Outline | corpus-outlines/*.json | 文档 id、类别、章节树、要点、植入事实、hard-negative 对 |
| WorldFacts | config/world_facts.json | 跨文档共享专名与数字（一致性约束源） |
| Topics | config/topics.json | 主题槽位 + 明确"不存在于语料"的 out-of-scope 主题 |
| BuildState | working/state.json | 已完成 (doc, section) 集合，断点续跑 |
| CaseCandidates | working/case-candidates.jsonl | 原始候选含被过滤项与原因（审计） |
| ReviewLog | working/review-log.json | reviewedBy/reviewedAt/复核范围（降级方案记录） |
| ChunkInventory | working/chunk-inventory.jsonl | 实体 2 的构建侧副本 |
