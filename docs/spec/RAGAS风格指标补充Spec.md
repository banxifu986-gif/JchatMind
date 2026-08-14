# RAGAS 风格指标补充 Spec

## 1. 目标

在现有 `RagRecallEvaluationTest` 检索评测之上，补充一组 RAGAS 风格的上下文与答案质量指标，用于回答：

1. 检索到的上下文是否与问题相关、是否包含回答所需信息。
2. 生成答案是否忠实于上下文、是否真正回答了问题。

本次实现不引入 RAGAS/DeepEval 等新依赖，不改变生产聊天链路，不改变现有 Recall/MRR/Hit 指标含义。确定性上下文指标随检索评测输出；LLM judge 指标默认关闭，仅在评测配置显式开启时执行。

### 实施状态（2026-08-12）

- 已完成：`contextPrecisionAt5/10`、`contextRecallAt5/10` 已写入 `RagRecallEvaluationTest` 的 overall 与 query-style breakdown JSON 报告。
- 已复用：现有可选 `answerQuality` 继续输出 `avgFaithfulness`、`avgAnswerRelevancy`。
- 未完成：独立 `ragas` JSON 节点、按 dimension 聚合，以及 judge 成本和延迟统计。

## 2. 范围

### 2.1 本次包含

- `context_precision`：Top-K 上下文中相关 chunk 的排序质量。
- `context_recall`：gold chunk 是否被检索上下文覆盖。
- `faithfulness`：答案陈述是否能被检索上下文支持。
- `answer_relevancy`：答案是否回应用户问题。
- 指标按 overall、query style、dimension 聚合。
- LLM judge 不可用时安全跳过，并在报告中记录原因。
- 维持现有 JSON 报告兼容性：新增字段，不删除或重命名旧字段。
- 追加配置项、运行方式、指标解释和已知限制到持久化文档。

### 2.2 本次不包含

- 不新增外部评测框架或 Python 运行时。
- 不改 RAG 检索、rerank、query rewrite 和生产答案生成逻辑。
- 不把 LLM judge 指标作为默认测试失败门禁。
- 不实现人工标注平台、在线 tracing 或统计显著性检验。

## 3. 指标定义

### 3.1 Context Precision

对检索结果按排名计算 Average Precision 的简化版本：

`context_precision@K = sum(precision@i * relevant_i) / number_of_relevant_results`

其中 `relevant_i` 表示第 i 个 chunk 是否属于 gold chunk 集合。没有相关结果时为 `0.0`。该指标关注相关 chunk 是否排在前面。

### 3.2 Context Recall

`context_recall@K = covered_gold_chunks / total_gold_chunks`

如果一个 query 对应多个 gold chunk，则按 gold 集合覆盖率计算；没有可用 gold 的 case 不参与该指标，并记录 exclusion。

### 3.3 Faithfulness

将答案拆为可验证的原子陈述，由 judge 判断每条陈述是否能从给定上下文推出：

`faithfulness = supported_claims / total_claims`

无答案或无法拆出陈述时不计算，避免把空答案误判为满分。

### 3.4 Answer Relevancy

由 judge 根据 query 和答案给出 0 到 1 的相关性分数。答案为空、judge 返回非法结果或调用失败时跳过。该指标只衡量是否回答问题，不替代事实正确性。

## 4. Judge 接口与输出约束

评测层通过一个最小 judge 接口获取结构化结果，生产代码不依赖具体模型：

- 输入：`query`、`context`、`answer`。
- 输出：`faithfulness`、`answerRelevancy`、可选 `claims`、`reason`。
- 分数必须归一化到 `[0, 1]`；非法 JSON、越界分数、超时和异常均视为不可评测。
- judge 调用失败不得阻塞检索评测，报告写入 `status=skipped` 和 `skipReason`。

## 5. 配置

```yaml
rag:
  eval:
    ragas:
      enabled: false
      sample-size: 10
      model: deepseek-chat
      max-context-chars: 12000
```

`enabled=false` 时不调用 ChatClient，报告中的 `ragas` 为 `null` 或 `status=disabled`。`sample-size` 只限制答案质量 judge 样本，不影响检索指标。

## 6. 报告结构

在现有报告根节点新增 `ragas` 字段，并保持旧字段不变：

```json
{
  "ragas": {
    "status": "enabled",
    "sampleSize": 10,
    "evaluated": 8,
    "skipped": 2,
    "skipReasons": {"judge_error": 2},
    "contextPrecisionAt5": 0.81,
    "contextRecallAt5": 0.76,
    "faithfulness": 0.92,
    "answerRelevancy": 0.88
  }
}
```

### 聚合规则

- `contextPrecisionAt5` 与 `contextRecallAt5` 对所有可建立 gold 的检索 case 计算。
- `faithfulness` 与 `answerRelevancy` 只对 judge 成功的采样 case 计算。
- 聚合使用宏平均，并同时记录 `evaluated/skipped`。
- 空集合不输出 `0.0` 冒充真实结果，使用 `null` 并记录原因。

## 7. 测试验收标准

### 单元测试

- 多个 gold chunk 的 Context Recall 计算正确。
- 相关 chunk 排名靠前时 Context Precision 高于排名靠后时。
- judge 分数越界、空答案、异常调用会被跳过而非污染均值。
- disabled 配置不触发 judge。

### 集成/回归测试

- fixture 默认评测仍通过，既有 Recall/MRR/Hit 字段数值不变。
- `ragas.enabled=false` 时不增加外部模型调用。
- 开启后，报告包含 `ragas.status`、四项指标和 skip 统计。
- judge 不可用时测试仍能完成，且报告明确记录 `judge_unavailable`。

## 8. 非功能约束

- 不新增依赖，不修改数据库 schema。
- 评测调用必须有样本上限、上下文字符上限和超时/异常降级。
- 报告字段命名使用现有 camelCase 风格。
- 该指标属于诊断能力，默认不作为生产发布阻断门禁；后续积累人工校准数据后再设置阈值。

## 9. 待后续迭代

- 增加人工标注的 Answer Correctness、Citation Precision/Recall 和 Abstention Accuracy。
- 对多跳问题从 chunk 命中升级到 gold facts 覆盖率。
- 将 judge 一致性、成本、延迟纳入报告。
- 评估独立 Python 评测工具与现有 Java 报告的互操作，而不是直接耦合到生产服务。
