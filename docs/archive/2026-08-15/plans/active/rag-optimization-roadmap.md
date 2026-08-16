# RAG 优化路线图

> 合并自：`Markdown分块与RAG瓶颈修改计划.md` + `RAG召回优化与对比方案.md`

## 0. 文档定位

本文覆盖两类内容：
- **Chunking 瓶颈分析与改造**（原 `Markdown分块与RAG瓶颈修改计划.md`）
- **整体优化路线图与对比框架**（原 `RAG召回优化与对比方案.md`）

两款内容合并后形成从 chunking → embedding → recall → rerank → query rewrite 的端到端 RAG 优化路线图。

适用范围：`backend_v2` 模块的 RAG 链路（Markdown 解析 → 分块 → embedding 入库 → pgvector 检索 → 重排 → Query Rewrite）。

---

## Part A：Markdown 分块瓶颈与改造

### A.1 当前链路现状

1. `MarkdownParserServiceImpl` 使用 Flexmark 解析 Markdown AST。
2. 解析器提取所有标题节点，生成 `MarkdownSection(title, content, contentPath)`。
3. 导入时对每个 section 生成一个 chunk：
   - `content = 当前标题下、直到下一个任意标题出现前的正文`
   - `metadata` 包含 `title / retrievableTitle / contentPath / sourceName / sourceType / sectionIndex`
   - embedding 文本为：`contentPath + "\n" + title + "\n" + content`
4. chunk 入 `chunk_bge_m3`，后续由向量召回 + 5 路标题检索 + BM25 + rerank 共同完成检索。

### A.2 瓶颈分析

| 瓶颈 | 描述 | 影响指标 |
|------|------|---------|
| 父标题 chunk 语义弱 | 父标题通常只有引导语，缺少子标题正文，但仍参与召回与排序 | `title_question Hit@1=0.5000` |
| 泛化叶子标题重复 | 大量"回答""原理""总结"等同名叶子，区分度低 | `auto_path_selection Recall@1=0.6813` |
| 路径信息利用不充分 | contentPath 已写入 metadata 但排序阶段未充分利用 | Top1 不稳定 |
| 粗/细粒度 query 共用同一种 chunk | 概览型 query 与叶子问答 query 用同一种表达 | 两端都不到最佳 |

### A.3 改造方案：结构分层 chunk

**Parser 层改造** — `MarkdownSection` 新增字段：

- `headingLevel`
- `parentContentPath`
- `hasChildren`
- `sectionType`：`PARENT_OVERVIEW` / `LEAF_CONTENT` / `LEAF_QA`
- `localContentLength`

**两类 chunk**：

1. **叶子 chunk**（精准命中）
   - embedding 文本：`sourceName + fullContentPath + leafTitle + content`
   - metadata：`chunkType=leaf`、`headingLevel`、`parentContentPath`、`pathDepth`

2. **父级 chunk**（章节导航）
   - 保留父标题引导语 + 子标题摘要/列表
   - 不再简单使用"直到下一个标题前的正文"

**召回与 rerank 改造**：

- `chunkType=leaf` 在叶子型 query 上加权
- `chunkType=parent` 限定在导航型 query
- rerank 引入 `pathDepth` 匹配度、`parentContentPath` 覆盖度、泛化标题惩罚

### A.4 验收线

- `auto_path_selection Recall@1 >= 0.80`
- `title_question Hit@1 >= 0.75`
- `content_rewrite Recall@5` 不退 > 0.02

---

## Part B：整体优化路线图

### B.1 基线走势

| 版本 | 关键改动 | title_exact Recall@5 | content_rewrite Recall@5 |
|------|---------|---------------------|--------------------------|
| V1 | embedding = 仅标题 | 0.9605 | 0.2883 |
| V2 | embedding = 标题+正文 | 0.7727 | 0.9632 |
| V3 | embedding = title+title+content | 0.8549 | 0.9451 |
| V4 | + top-10 轻量 rerank | 0.9016 | 0.9756 |
| 后续 | + contentPath、标题锚点、trigram、BM25、query rewrite 等 | ~1.0000 | ~0.9896 |

核心结论：V2 是检索偏好切换，不是单调改进。标题精确信号被正文稀释。

### B.2 优化方向清单（按优先级）

| 优先级 | 方向 | 主要解决问题 | 预期影响指标 |
|--------|------|------------|------------|
| **P0a** | embedding 文本构成微调（title+title+content 或 标题路径拼接） | 回补 V2 退步的 `title_exact` | `title_exact` ↑ |
| **P0b** | 标题/BM25 + 向量混合召回（RRF 融合） | 两组瓶颈同时打开 | `title_exact`、`content_rewrite` 同时 ↑ |
| **P1a** | 参数化 top-K 与相似度阈值 | 为重排准备更大候选集 | `Recall@5` ↑ |
| **P1b** | 接入重排层（bge-reranker-v2-m3） | 排序质量、首位命中 | `MRR` ↑、`Recall@1` ↑ |
| **P2a** | Query 改写（HyDE / 多查询融合） | `content_rewrite` 长尾失败 | `content_rewrite Recall@5` 边际 ↑ |
| **P2b** | Chunking 改进（长度上限 + overlap + 结构分层） | 过长章节语义稀释 | `overall Recall` ↑ |
| **P3a** | L2 距离 → cosine + embedding 归一化 | 与 bge-m3 官方推荐对齐 | 边际收益 |
| **P3b** | Embedding 缓存（按文本内容 hash） | 性能 | P95 延迟 ↓ |

### B.3 量化对比框架

**指标补全（最小集）**：

1. **MRR@10**：区分"命中但首位是噪声"和"首位即命中"
2. **命中分档分布**：hit@1 / hit@3\hit@1 / hit@5\hit@3 / miss
3. **Miss 增量 diff**：相比上一版，新增 miss 和修复 miss

**收益判定阈值**：

| 情况 | 判定 |
|------|------|
| `content_rewrite Recall@5` 涨幅 ≥ +0.02 且 `title_exact` 退步 < 0.02 | ✅ 采纳 |
| `content_rewrite` 涨幅 ≥ +0.05 但 `title_exact` 退步 ≥ 0.03 | ⚠️ 部分采纳 |
| 两组 |Δ Recall@5| 均 < 0.01 | 中性，不采纳 |
| 任一组 Recall@5 退步 ≥ 0.05 | ❌ 回滚 |
| `Recall@5` 不变但 `MRR@10` 涨幅 ≥ +0.03 | ✅ 采纳（排序质量优化） |

### B.4 推荐执行顺序

| 轮次 | 内容 | 代码位置 |
|------|------|---------|
| V_eval-upgrade | 先升级评测代码增加 MRR@10/命中分布/Miss diff | `RagRecallEvaluationTest.java` |
| V3 (P0a) | embedding 文本构成调整 | `buildChunkEmbeddingText` |
| V4 (P0b) | BM25 + 向量混合召回（RRF） | `RagServiceImpl.retrieve()` |
| V5 (P1a) | 参数化 top-K 与相似度阈值 | `RagServiceImpl.java` |
| V6 (P1b) | 重排层接入 | `RagServiceImpl.java` 检索流水线末端 |
| V7+ (P2/P3) | 视瓶颈决定 | — |

---

## 实现状态

- **状态**: ⚠️ 部分完成
- **最后验证**: 2026-05-24
- **已完成项**:
  - [x] P0a：embedding 文本 = `title+title+content`（V3），后升级为 `contentPath+title+content`
  - [x] Part A 结构分层 chunk：SectionType（PARENT_OVERVIEW/LEAF_CONTENT/LEAF_QA）、hasChildren、headingLevel、pathDepth、parentContentPath 全部写入 metadata（`util/RagChunkSupport.java`）
  - [x] P0b 部分：标题侧 BM25（`findTitleBm25Candidates`）
  - [x] P1b 部分：多维度 rerank（`RerankScore` 8 维，含 lexicalScore/titleExact/contentPath/structure 等）
  - [x] P2a：Query Rewrite（intent 识别 + contextApplyMode + LLM 改写分支）
  - [x] P2b 部分：Chunking 结构分层（Part A 改造）
- **未完成项**:
  - [x] P0b 内容侧 BM25 通道：已新增内容全文候选与 Java 侧 BM25 计算
  - [x] P0b RRF 融合：已切换为统一 RRF（`RRF_K=60`）
  - [x] P1b 自适应重排：已补入 title/content BM25 信号与向量信号
  - [ ] P3a：L2 距离 → cosine + embedding 归一化
    - 2026-05-24 已做 `P3a minimal` 试探，但真实 KB 小样本出现回退，本轮不采纳
  - [x] P3b：Embedding 缓存（进程内 LRU 最小版已落地）

### 2026-05-24 更新

- 已完成第一阶段召回架构收口：
  - 内容侧 BM25
  - 全通道 RRF 融合
  - rerank 内容信号补强
- 当前最大风险不再是“召回链路缺件”，而是：
  - 真实 KB 全量评测耗时过长，A/B 评测不适合作为默认回归入口
  - `P3a` 已评估但本轮不采纳，后续只在独立诊断轮次再评估距离口径
- 已补充宏观收口项：
  - 真实 KB 评测增加 `real-max-documents / real-max-cases / real-document-order`
  - 检索 embedding 增加进程内 LRU 缓存
