# RAGAS 风格指标维护说明

## 当前状态

本轮已补充独立的 `RagAsMetrics` 计算器，并已接入 `RagRecallEvaluationTest` JSON 报告。当前包含：

- `contextPrecision(rankedChunkIds, goldChunkIds)`：按检索排名计算 Average Precision 简化值。
- `contextRecall(rankedChunkIds, goldChunkIds)`：计算 gold chunk 被 Top-K 覆盖的比例。
- `clampScore(score)`：将 LLM judge 分数约束在 `[0, 1]`。

实现位置：

`backend_v2/src/test/java/com/kama/jchatmind/rag/RagAsMetrics.java`

测试位置：

`backend_v2/src/test/java/com/kama/jchatmind/rag/RagAsMetricsTest.java`

## 运行验证

```powershell
cd backend_v2
.\mvnw.cmd -q -Dtest=RagAsMetricsTest test
```

### L1 冻结数据集快速回归

```powershell
cd backend_v2
.\mvnw.cmd -q -Dtest=RagEvaluationDatasetLoaderTest,RagFastRegressionEvaluatorTest test
```

该入口不启动 Spring、不访问 PostgreSQL、也不调用 Ollama。它读取冻结的
`fixture-fast-v1` manifest、JSONL case 和版本化 retrieval replay，报告落盘到：

`backend_v2/target/rag-eval/fast/fixture-fast-v1-report.json`

报告中的 `executionMode=replay` 表示这是一项数据集、指标公式和报告 schema 回归，**不代表实时 RAG 检索效果**。

## 与现有评测的关系

现有 `RagRecallEvaluationTest` 已经提供 Recall、MRR、Hit、Coverage、多样性和可选答案质量评测。本轮在 overall 与 query-style breakdown 中新增：

- `contextPrecisionAt5`
- `contextPrecisionAt10`
- `contextRecallAt5`
- `contextRecallAt10`

本计算器不改变既有指标，也不改变生产 RAG 链路。

推荐接入顺序：

1. 将现有答案质量字段迁移/映射到 RAGAS 风格的 `faithfulness` 和 `answerRelevancy`，保留旧字段一段时间以兼容历史报告。
2. 按 dimension 聚合 Context Precision/Recall，避免只看 overall。
3. 默认关闭 LLM judge；开启时记录 evaluated、skipped、skipReasons、模型、延迟和成本。

## 解释注意事项

- Context Precision/Recall 依赖 gold chunk 对齐质量，必须与 Coverage 和 excludedReasons 一起阅读。
- Faithfulness 和 Answer Relevancy 是 LLM-as-judge 估计值，不是人工真值；发布门禁前应建立人工校准集。
- 空输入返回 `0.0` 仅适用于计算器层；报告聚合时应区分“真实得分为 0”和“不可评测”。
- 指标应按 query style、知识库、文档版本和多轮状态分桶，不能只看 overall 均值。

## 后续维护

新增指标时先更新 [RAGAS风格指标补充Spec.md](spec/RAGAS风格指标补充Spec.md)，再按 TDD 增加失败测试。不要直接在 `RagRecallEvaluationTest` 中复制计算公式；统一复用 `RagAsMetrics`，避免不同评测入口产生口径漂移。

## 本轮验证

- `RagAsMetricsTest`：通过。
- `RagEvaluationDatasetLoaderTest`：通过，验证冻结 manifest、20 条 JSONL case 和 corpus/case SHA-256。
- `RagFastRegressionEvaluatorTest`：通过，验证 L1 replay 报告输出 datasetId、manifest hash、`Recall@5`、`MRR@3`、`Context Precision/Recall@5/@10` 与拒答准确率。
- `mvnw.cmd -q -DskipTests test-compile`：通过。
- 跨文档 fixture 端到端评测：最终通过，Surefire 耗时 `1408.26s`；此前仅是外层 180 秒命令超时，测试进程随后完成并生成 `target/rag-eval/report.json`。
