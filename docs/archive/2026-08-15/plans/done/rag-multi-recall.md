# RAG 多路召回升级方案

## 0. 文档定位

- 本文描述 RAG 检索链路从「1 路向量 + 5 路标题」升级为「内容侧双路互补 + RRF 融合 + 自适应重排」的完整改造方案。
- 核心原则：**做增量叠加，不做替换**。结构化文档的全部现有信号保留，扁平段落从单通道升级为多路有效融合。
- 适用范围：`backend_v2` 模块 `RagServiceImpl`、`QueryRewriteServiceImpl` 及关联 Mapper。
- 不在此文档范围内的事项：
  - 不引入新的 embedding 模型
  - 不引入数据库 schema 变更
  - 不引入新的 Maven 依赖
  - 不修改 Markdown 分块逻辑

## 1. 问题分析

### 1.1 当前链路现状

```
用户查询
  → QueryRewriteService.rewrite()
  → 向量检索 (pgvector cosine similarity)           ← 内容侧唯一通道
  → 标题精确匹配                                     ← 以下 5 路由 isTitleQuery() 门控
  → 标题 LIKE 模糊匹配
  → 标题关键词匹配
  → 标题 trigram 模糊匹配 (pg_trgm)
  → 标题 BM25 全文检索
  → mergeCandidates() 顺序合并 (按 distance 比较去重)
  → rerank() 加权重排 (75% 的分数依赖标题/contentPath 结构维度)
  → 返回 top-K
```

### 1.2 核心问题

| 问题 | 影响场景 | 根因 |
|------|---------|------|
| 内容侧单通道 | 扁平段落、非标题型查询 | 向量语义是唯一召回来源，无法互补 |
| BM25 限定在标题字段 | 内容精确匹配需求 | `findTitleBm25Candidates` 只拉 `retrievableTitleSearchText`，不拉 content |
| mergeCandidates 异构不可比 | 多通道融合质量 | 标题 LIKE 通道没有 `distance`，与向量通道排序信号不对等 |
| 重排对扁平文档无效 | 非结构化、多格式文档 | lexicalScore 75% 依赖 title/contentPath/structure，扁平段落下全部归零 |
| LLM 多查询改写门控过紧 | 扁平段落的长尾查询 | 仅在 FOLLOW_UP+HARD 或 ANALYTICAL 场景触发 |

### 1.3 为什么不做替换

现有 5 路标题通道在结构化 Markdown 文档下是核心区分信号，评测已验证：

- `title_exact Recall@5` 从 V1 的 0.9605 到最终态的 ~0.98+
- 纯叶子标题 `title_to_content Recall@5` 从 0.6313 到补充 contentPath 后的 0.9777

如果全局替换为重内容轻标题的权重体系，这些指标会断崖式回退。因此本方案的所有改造都是**并行新增**和**自适应判断**，不删除、不削弱任何现有逻辑。

## 2. 改造目标

| 指标维度 | 目标 | 验证方式 |
|---------|------|---------|
| 结构化文档·标题型查询 | Recall@5 不退化（波动 ≤0.02） | 现有评测数据集 |
| 结构化文档·内容型查询 | Recall@5 提升 ≥0.02 | 现有评测数据集 |
| 扁平段落检索 | MRR@10 相对裸向量 +0.03+ | DuReader-retrieval 电商子集 或 Multi-CPR 子集 |
| 非 Markdown 文档兼容 | 重排对无结构 chunk 也有区分力 | 构造扁平 chunk 评测集 |

## 3. 改造方案

### 3.1 总体架构

```
改造后链路：
──────────
用户查询
  → QueryRewriteService.rewrite()             ← 门控放松，多查询改写普遍开启
  → ┌─ 通道 1: 向量语义检索 (pgvector)        ← 已有
  │  ├─ 通道 2: 内容 BM25 词法检索              ← 新增
  │  ├─ 通道 3: 标题精确匹配                    ← 已有，保留
  │  ├─ 通道 4: 标题 LIKE                       ← 已有，保留
  │  ├─ 通道 5: 标题关键词                       ← 已有，保留
  │  ├─ 通道 6: 标题 trigram                     ← 已有，保留
  │  └─ 通道 7: 标题 BM25                       ← 已有，保留
  └─ RRF 融合                                   ← 新增（替换 mergeCandidates）
     → rerank() 自适应重排                       ← 改造（新增内容维度，保留结构维度）
        → top-K
```

### 3.2 改造一：新增内容 BM25 通道

**目标**：在内容侧形成「稠密向量 + 稀疏词法」双路互补。

**实现**：

1. `ChunkBgeM3Mapper` 新增方法：

```java
// 拉取内容候选数据，字段精简（只需 chunkId + content），用于内存 BM25 计算
@Select("SELECT id AS chunk_id, kb_id, doc_id, content, metadata, embedding " +
        "FROM chunk_bge_m3 WHERE kb_id IN <foreach>")
List<RagRetrievalResult> selectContentCandidatesByKbIds(@Param("kbIds") List<String> kbIds);
```

2. `RagServiceImpl` 新增 `findContentBm25Candidates()` 方法：

- 复用现有 `bm25Score()` 计算逻辑
- 对 content 字段做 tokenize（重用 `RetrievableTitleLexicalizer.tokenize()`）
- BM25 参数复用现有常量（`BM25_K1=1.2, BM25_B=0.75`）
- 候选上限设为 `scaledCandidateLimit(CONTENT_BM25_CANDIDATE_LIMIT, scopeMultiplier)`，`CONTENT_BM25_CANDIDATE_LIMIT` 默认值 20

3. `retrieveWithPlan()` 中调用：

```java
// 不经过 isTitleQuery() 门控，针对全部查询
List<RagRetrievalResult> contentBm25Candidates = findContentBm25Candidates(
    kbIds, normalizedOriginalQuery, contentFullTextCandidateLimit);
candidates = mergeCandidates(candidates, contentBm25Candidates);
```

**关键点**：与标题 BM25 不同，内容 BM25 针对全文 content 分词，对精确术语（产品名、编号、代码标识符）的召回优于向量。

### 3.3 改造二：RRF 融合替代 mergeCandidates

**目标**：解决异构通道分数不可比问题，让各通道按排名对等参与融合。

**实现**：

1. `RagServiceImpl` 新增 `rrfFuse()` 方法：

```java
/**
 * RRF（Reciprocal Rank Fusion）融合多个候选通道。
 * score = sum( 1 / (k + rank_i) )，其中 k 取 60。
 * 未出现在某通道的候选，该通道贡献 0。
 *
 * @param channelResults 每个通道的排序候选列表
 * @param k              RRF 平滑常数
 * @return 按 RRF 分数降序的融合候选列表
 */
private List<RagRetrievalResult> rrfFuse(
        List<List<RagRetrievalResult>> channelResults,
        int k
) {
    Map<String, Double> chunkScores = new LinkedHashMap<>();
    Map<String, RagRetrievalResult> chunkMap = new LinkedHashMap<>();

    for (List<RagRetrievalResult> channel : channelResults) {
        for (int rank = 0; rank < channel.size(); rank++) {
            RagRetrievalResult result = channel.get(rank);
            String chunkId = result.getChunkId();
            double contribution = 1.0 / (k + rank + 1);  // rank 从 0 开始，+1 修正

            chunkScores.merge(chunkId, contribution, Double::sum);
            chunkMap.putIfAbsent(chunkId, result);
        }
    }

    return chunkScores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .map(entry -> {
                RagRetrievalResult result = chunkMap.get(entry.getKey());
                result.setRrfScore(entry.getValue());
                return result;
            })
            .toList();
}
```

2. `retrieveWithPlan()` 中替换融合逻辑：

```
改造前：
  candidates = mergeCandidates(titleCandidates, candidates);
  candidates = mergeCandidates(candidates, titleContainsCandidates);
  // ... 逐一 merge

改造后：
  List<List<RagRetrievalResult>> channels = new ArrayList<>();
  channels.add(vectorCandidates);        // 通道 1
  channels.add(contentBm25Candidates);   // 通道 2 (新增)
  channels.add(titleExactCandidates);    // 通道 3 (仅在 isTitleQuery() 时非空)
  // ... 其余标题通道逐个添加
  candidates = rrfFuse(channels, 60);
```

3. 保留 `filterByContext()` 对 HARD 模式的硬过滤（在 RRF 融合之后执行），不改变。

**关键设计决策**：

- 空的通道列表直接加入 `channels`，RRF 中该通道对所有 chunk 贡献为 0，不影响最终排序
- 同一顺序下 7 个通道全部参与融合，不存在 "某通道吃掉另一通道" 的问题
- K=60 是学术界通用取值，代码中作为常量可调

### 3.4 改造三：自适应重排

**目标**：重排对结构化文档保持现有表现，对扁平文档新增有效评分维度。

**实现**：

1. `RagServiceImpl.lexicalScore()` 中，保留全部现有维度，新增两个通用维度：

```java
// 新增维度 1：BM25 归一化得分（对任何文档类型有效）
double bm25Score = computeBm25NormalizedScore(chunkId, contentBm25ChannelRanks);
// 0 ~ 0.12，以 content BM25 通道内排名换算

// 新增维度 2：向量余弦相似度归一化得分（已有 distance 字段，可直接使用）
double cosineScore = computeCosineNormalizedScore(result.getDistance());
// 0 ~ 0.10，以 1/(1+distance) 换算
```

2. `RerankScore.lexicalScore()` 的上限从 0.75 提升到 0.80，容纳新增维度。

3. 新增维度的计算方法：

```java
private double computeBm25NormalizedScore(String chunkId,
        Map<String, Integer> bm25Ranks) {
    Integer rank = bm25Ranks.get(chunkId);
    if (rank == null) return 0D;
    // 排名 1 → 0.12，排名递减
    return 0.12 / (1.0 + Math.log1p(rank - 1));
}

private double computeCosineNormalizedScore(Double distance) {
    if (distance == null || distance.isNaN()) return 0D;
    // pgvector cosine distance → 归一化到 0~0.10
    // cosine distance = 1 - cosine_similarity，范围 [0, 2]
    double similarity = 1.0 - Math.min(distance, 2.0);
    return Math.max(0.0, similarity * 0.10);
}
```

**自适应关键**：

- 现有结构维度（titleExact/ContentPath/structureScore）在扁平文档下自然为 0，不影响排序
- 新增内容维度（BM25/cosine）在两种文档下都有值
- 结构化文档 = 结构维度高分 + 内容维度补充 → 更强
- 扁平文档 = 结构维度为 0 + 内容维度支撑 → 从无效变有效

4. `structureScore()` 中现有的 `GENERIC_LEAF_TITLES` 判断和 `sectionType` 加减分全部保留，增加一个空值保护：

```java
// 如果所有结构字段均为空/0，直接返回 0（扁平文档快速路径）
if (!hasAnyStructureMetadata(result)) {
    return 0D;
}
// 否则走现有逻辑不变
```

### 3.5 改造四（可选）：放松多查询改写门控

**目标**：让扁平段落的长尾内容查询也能受益于 LLM 多查询改写。

**实现**：`QueryRewriteServiceImpl.shouldUseLlmRewrite()` 中放宽条件：

```java
// 现有条件：仅 FOLLOW_UP+HARD 或 ANALYTICAL+有上下文+非路径型
// 新增条件：FACTOID 意图且无上下文的扁平查询也允许 LLM 多查询
if (intent == QueryRewriteResult.Intent.FACTOID
        && (context == null || !context.hasContext())
        && normalizedQuery.length() > 8
        && normalizedQuery.length() <= 60
        && !isPathAwareQuery(normalizedQuery)) {
    return true;
}
```

**风险**：增加 LLM 调用延迟。通过 `.orTimeout()` 和现有的 fallback 机制兜底。

**建议**：此改造标记为可选，前三项改造完成并评测通过后，再单独评估是否开启。

## 4. 文件级改动清单

| 文件 | 改动类型 | 改动内容 |
|------|---------|---------|
| `ChunkBgeM3Mapper.java` | 新增方法 | `selectContentCandidatesByKbIds()` 查询方法 |
| `ChunkBgeM3Mapper.xml` | 新增 SQL | 对应的 `<select>` 节点 |
| `RagServiceImpl.java` | 新增字段 | `CONTENT_BM25_CANDIDATE_LIMIT` 常量 |
| `RagServiceImpl.java` | 新增方法 | `findContentBm25Candidates()` |
| `RagServiceImpl.java` | 新增方法 | `rrfFuse()` |
| `RagServiceImpl.java` | 新增方法 | `computeBm25NormalizedScore()` |
| `RagServiceImpl.java` | 新增方法 | `computeCosineNormalizedScore()` |
| `RagServiceImpl.java` | 新增方法 | `hasAnyStructureMetadata()` |
| `RagServiceImpl.java` | 改造方法 | `retrieveWithPlan()` — 新增 content BM25 通道 + RRF 融合替换 mergeCandidates |
| `RagServiceImpl.java` | 改造方法 | `lexicalScore()` — 新增 bm25Score + cosineScore 维度 |
| `RagServiceImpl.java` | 改造方法 | `structureScore()` — 新增结构化空值快速路径 |
| `RagServiceImpl.java` | 改造内类 | `RerankScore` — 新增字段，cap 从 0.75 → 0.80 |
| `RagRetrievalResult.java` | 新增字段 | `rrfScore` (double) |
| `QueryRewriteServiceImpl.java`（可选） | 改造方法 | `shouldUseLlmRewrite()` 门控放松 |

## 5. 执行顺序

| 步骤 | 内容 | 验收方式 | 依赖 |
|------|------|---------|------|
| 1 | ChunkBgeM3Mapper 新增 content 候选查询 | SQL 日志确认查询正常返回 | — |
| 2 | `findContentBm25Candidates()` 实现 | 单测验证 BM25 评分与预期排序一致 | 步骤 1 |
| 3 | `rrfFuse()` 实现 | 单测：3 个模拟通道，验证 RRF 排序与手工计算一致 | — |
| 4 | `retrieveWithPlan()` 集成 | 现有评测数据集全量跑，确认 title_exact/content_rewrite 不退化 | 步骤 2, 3 |
| 5 | `lexicalScore()` 新增维度 | 单条 query 调试日志确认新增维度非零 | 步骤 4 |
| 6 | 自适应重排集成 | 现有评测数据集全量跑，确认结构化指标不退化 | 步骤 4, 5 |
| 7 | 构造扁平 chunk 评测集 | 用 DuReader-retrieval 或 Multi-CPR 子集，对比裸向量 vs 完整链路 | 步骤 6 |
| 8 | （可选）LLM 多查询门控放松 | 评测对比开启/关闭 LLM 多查询，评估延迟与召回收益 | 步骤 6 |

## 6. 验收标准

### 6.1 必须满足（阻塞合并）

- 现有评测数据集 `title_exact Recall@5` 退化 ≤ 0.02
- 现有评测数据集 `content_rewrite Recall@5` 退化 ≤ 0.02
- 现有评测数据集 `auto_path_selection Recall@1` 不退化
- 现有评测数据集 Session contextual `Hit@Top3` 不退化

### 6.2 期望满足（不阻塞但需记录）

- `content_rewrite Recall@5` 提升 ≥ 0.02
- 扁平段落评测集 `MRR@10` 相对于纯向量检索提升 ≥ 0.03

### 6.3 单测要求

- `findContentBm25Candidates()` 独立单元测试
- `rrfFuse()` 独立单元测试（至少覆盖：单通道、多通道空列表、不同 k 值、排名相同分数一致）
- `computeBm25NormalizedScore()` 边界测试（rank=null, rank=1, rank=100）

### 6.4 不验证的事项

- 不要求端到端 Agent 对话验证
- 不要求前端变更
- 不要求数据库迁移

## 7. 风险与兜底

| 风险 | 概率 | 影响 | 兜底 |
|------|------|------|------|
| 内容 BM25 候选池过大导致 OOM | 中 | 高 | `CONTENT_BM25_CANDIDATE_LIMIT` 上限 + kbIds 预过滤 |
| RRF 融合后结构化查询退化 | 低 | 高 | 步骤 4 单独验收结构化指标，未通过则保留 mergeCandidates + 仅对扁平文档启用 RRF |
| 自适应重排增加计算耗时 | 低 | 中 | 新增维度都是 O(1) 查表操作，不增加外部 IO；如 P95 延迟超标则降级为仅对无结构 chunk 启用新增维度 |
| 内容 BM25 全量拉取数据库传输压力 | 中 | 低 | 每个 kbId 的候选集量级不大（当前知识库最多几千 chunk）；数据量大后加 `LIMIT` 推送下推 |

## 8. 与后续工作的关系

- 本方案完成后，RAG 链路对**多格式文档**（PDF、Word、Confluence）的检索兼容性显著提升——新格式 Parser 只需输出相同 chunk 格式，下游检索无需改动
- BM25 内容通道为后续引入 `bge-reranker-v2-m3` 重排模型提供了更丰富的候选池
- RRF 融合框架建立后，后续新增通道（HyDE、多模态 embedding 等）只需在 `channels` 列表中追加一行，无需改动融合逻辑
