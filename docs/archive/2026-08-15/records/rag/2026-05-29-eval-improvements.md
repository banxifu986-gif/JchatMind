# RAG 评测框架三项改进 - 2026-05-29

## 背景

当前评测框架只覆盖检索层指标（Recall/MRR/Hit@K），缺少三个维度：
- **答案质量**：检索好不代表生成好
- **Chunk 多样性**：top-K 可能扎堆在同一章节
- **跨文档检索**：fixture 只测单文档

## 改动内容

### 1. Chunk 多样性指标

在 `EvaluationSummary` 和 `DimensionSummary` 中新增 `diversityAt5`/`diversityAt10`：

- `uniquePaths` — top-K 中不重复 contentPath 数
- `uniqueSources` — top-K 中不重复 sourceName 数
- `pathDiversityRatio` / `sourceDiversityRatio` — 分散度比率

实现：在 `evaluateStyleGroup` 中收集原始检索结果，解析 metadata JSON 的 `contentPath` 和 `sourceName` 字段，全局去重统计。

改动文件：`RagRecallEvaluationTest.java`

### 2. 跨文档 Fixture

新增 3 个 fixture 文件，与原有 `fixture-kb.md` 组成 4 文档测试集：

- `fixture-kb-returns.md` — 退货退款政策（嵌套层级）
- `fixture-kb-logistics.md` — 发货与物流（含交叉术语"订单退款"、"会员积分"）
- `fixture-kb-membership.md` — 会员体系与积分规则

总计约 32 个 section，约 250 个 query cases。通过 `rag.eval.fixture.multi-doc` 开关控制，默认开启。

改动文件：`RagRecallEvaluationTest.java`、`application-rag-eval.yaml`、3 个新 fixture 文件

### 3. 答案质量评测（RAGAS 式）

新增 `AnswerQualityEvaluator` 内部类，实现 LLM-as-judge：

- **Faithfulness**：回答是否严格基于上下文（无幻觉）
- **Answer Relevancy**：回答是否直接回应查询

完全可选，默认关闭。ChatClient 不可用时自动降级跳过。

启用方式：
```bash
mvn test -Dtest=RagRecallEvaluationTest \
  -Dspring.autoconfigure.exclude="" \
  -DRAG_EVAL_ANSWER_QUALITY_ENABLED=true
```

改动文件：`RagRecallEvaluationTest.java`、`application-rag-eval.yaml`

## 验收

- 默认模式：BUILD SUCCESS，JSON report 含 diversity 字段，answerQuality 为 null
- `fixture.multi-doc=false`：向后兼容，约 30 个 primary cases
- `answer-quality.enabled=true` + ChatClient 不可用：跳过并 stdout 提示原因
