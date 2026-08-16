# 可信研发知识协作 Agent 实施 Spec

> 状态：当前唯一实施 Spec
> 对应计划：[可信研发知识协作 Agent 升级总计划](../plans/active/trusted-knowledge-agent-roadmap.md)
> 当前实施阶段：G0 基线与观测

## 1. 文档定位与范围

本 Spec 是总计划的实现级约束，定义需求到测试的追溯、测试优先开发方式、隔离环境和验收证据。总计划负责阶段目标、优先级和退出条件；架构文档负责当前实现与源码导航；本 Spec 负责“在当前阶段具体要实现什么、先写什么测试、如何证明通过”。

当前版本授权实施 G0。G1-G5 仅定义后续实现必须遵守的契约和测试入口，不构成提前开发授权。每个阶段开始前，必须先在本 Spec 补齐该阶段的精确数据、接口、事件和测试细节，再写生产代码。

不新增平行 Spec。RAGAS 指标作为本 Spec 的专项章节维护；任务、Router、记忆、Webhook、并发和浏览器 E2E 的实施契约同样在本文件持续补充。

## 2. SDD 需求追溯

每项实现必须从“阶段交付 -> 行为契约 -> `TC-ID` -> 测试代码/报告”单向追溯，并可从报告反查需求。未绑定 `TC-ID` 的需求不得进入实现。

| 阶段 | 实现级契约 | 必需测试入口 | 当前状态 |
| --- | --- | --- | --- |
| G0 | 聊天、RAG、SSE、审批在隔离环境可观测且可回归；不改变现有公开 API。 | `TC-G0-01` 至 `TC-G0-06` | 可实施，基线待签收。 |
| G1 | 任务状态机、异步摄入、幂等、重试和进度事件必须有确定状态与隔离边界。 | `TC-G1-01` 至 `TC-G1-05` | 待阶段开始前补充接口、表结构和事件 schema。 |
| G2 | Router 必须输出受限 schema，并按权限、证据与用户授权决定检索或拒答。 | `TC-G2-01` 至 `TC-G2-05` | 待阶段开始前补充 route 输入输出和评测数据。 |
| G3 | Skill 与记忆必须有可验证 schema、所有权和失败不阻断主链路的约束。 | `TC-G3-01` 至 `TC-G3-04` | 待阶段开始前补充模型、接口和 UI 状态。 |
| G4 | 工作流验证与 Webhook 必须对证据、权限、超时、签名、重试和死信作出确定响应。 | `TC-G4-01` 至 `TC-G4-04` | 待阶段开始前补充契约和投递 schema。 |
| G5 | 并发、缓存和多实例 SSE 必须满足顺序、隔离、背压和恢复语义。 | `TC-G5-01` 至 `TC-G5-04` | 待阶段开始前补充负载模型和部署拓扑。 |

阶段实现前必须在本 Spec 的对应小节补充：输入/输出或事件 schema、数据所有权、失败行为、幂等规则、观测字段、非目标和 `TC-ID` 映射。若这些内容不能写清，说明设计尚未达到实现条件。

## 3. TDD 实施协议

除纯文档、配置说明和已批准的生成代码外，所有生产代码变更遵循 Red-Green-Refactor：先写单一行为的测试并确认其因功能缺失而失败；再编写使该测试通过的最小实现；最后在全部相关测试通过后重构。测试不能在新增生产代码后才补写。

### 3.1 G0 测试先行清单

| TC-ID | 先写的失败测试 | RED 通过条件 | GREEN 与回归入口 |
| --- | --- | --- | --- |
| TC-G0-01 | 启动上下文测试，覆盖最小 Bean 图与循环依赖保护。 | 断言因缺少目标 Bean 图或循环依赖而失败，不因测试配置错误失败。 | 最小化配置后通过 `CircularDependencyStartupTest`，再运行相关启动测试。 |
| TC-G0-02 | 聊天链路测试，断言消息持久化、状态完成和历史顺序。 | 未实现的链路或持久化断言明确失败。 | 只实现必要编排后运行聊天链路测试与 G0 手工旅程。 |
| TC-G0-03 | 指标/replay 测试，断言 gold、报告字段和 fixture Recall@5。 | 新指标或报告字段缺失时失败，不能因外部模型不可用而错误。 | `RagAsMetricsTest`、`RagFastRegressionEvaluatorTest`、`RagRecallEvaluationTest`。 |
| TC-G0-04 | SSE 恢复契约测试，断言事件顺序、重复事件去重和异常可见。 | 缺少恢复、去重或错误状态时出现预期断言失败。 | `SseServiceImplTest` 与隔离手工旅程。 |
| TC-G0-05 | 审批状态测试，断言批准、拒绝、超时均不绕过 Harness。 | 未进入或未正确退出 `WAITING_APPROVAL` 时失败。 | `HarnessRunnerTest` 及审批相关测试。 |
| TC-G0-06 | 前端构建与手工旅程清单。 | G0 不引入浏览器测试依赖；构建或关键旅程不满足即失败。 | `npm run lint`、`npm run build` 与测试账号手工记录。 |

### 3.2 后续阶段的测试先行要求

G1 起，每个 `TC-ID` 在实现前必须补充测试类/文件、方法名、固定 fixture、RED 预期失败和 GREEN 回归命令。G1 的 Playwright 用例先失败后再实现 UI；G2-G5 的路由、状态机、签名、并发和恢复逻辑先以 L0/L1 契约测试固定，随后再补 L2/L3 集成验证。

每次 RED/GREEN 证据写入总计划的逐用例验收台账：记录数据/配置版本、命令或报告路径、执行日期、执行人和结论。仅在测试确实失败过且失败原因符合预期时，才允许进入生产实现。

### 3.3 测试数据与副作用边界

- L0 使用受控 fixture，不启动 Spring 或外部服务。
- L1 对邮件、MCP/Web 使用替身；不得断言替身自身行为而忽略业务结果。
- L2 只连接隔离 Docker 中的 PostgreSQL、Redis、RabbitMQ 和 Ollama，使用可清理测试账号与知识库。
- L3 从 G1 起使用 Playwright；截图和报告写入构建产物，不提交生产数据或凭据。
- 默认报告路径为 `backend_v2/target/surefire-reports/`、`backend_v2/target/rag-eval/` 和 Playwright 报告目录。

## 4. RAGAS 指标专项

### 4.1 目标

在现有 `RagRecallEvaluationTest` 检索评测之上，补充一组 RAGAS 风格的上下文与答案质量指标，用于回答：

1. 检索到的上下文是否与问题相关、是否包含回答所需信息。
2. 生成答案是否忠实于上下文、是否真正回答了问题。

本次实现不引入 RAGAS/DeepEval 等新依赖，不改变生产聊天链路，不改变现有 Recall/MRR/Hit 指标含义。确定性上下文指标随检索评测输出；LLM judge 指标默认关闭，仅在评测配置显式开启时执行。

#### 4.1.1 实施状态（2026-08-12）

- 已完成：`contextPrecisionAt5/10`、`contextRecallAt5/10` 已写入 `RagRecallEvaluationTest` 的 overall 与 query-style breakdown JSON 报告。
- 已复用：现有可选 `answerQuality` 继续输出 `avgFaithfulness`、`avgAnswerRelevancy`。
- 未完成：独立 `ragas` JSON 节点、按 dimension 聚合，以及 judge 成本和延迟统计。

### 4.2 范围

#### 4.2.1 本次包含

- `context_precision`：Top-K 上下文中相关 chunk 的排序质量。
- `context_recall`：gold chunk 是否被检索上下文覆盖。
- `faithfulness`：答案陈述是否能被检索上下文支持。
- `answer_relevancy`：答案是否回应用户问题。
- 指标按 overall、query style、dimension 聚合。
- LLM judge 不可用时安全跳过，并在报告中记录原因。
- 维持现有 JSON 报告兼容性：新增字段，不删除或重命名旧字段。
- 追加配置项、运行方式、指标解释和已知限制到持久化文档。

#### 4.2.2 本次不包含

- 不新增外部评测框架或 Python 运行时。
- 不改 RAG 检索、rerank、query rewrite 和生产答案生成逻辑。
- 不把 LLM judge 指标作为默认测试失败门禁。
- 不实现人工标注平台、在线 tracing 或统计显著性检验。

### 4.3 指标定义

#### 4.3.1 Context Precision

对检索结果按排名计算 Average Precision 的简化版本：

`context_precision@K = sum(precision@i * relevant_i) / number_of_relevant_results`

其中 `relevant_i` 表示第 i 个 chunk 是否属于 gold chunk 集合。没有相关结果时为 `0.0`。该指标关注相关 chunk 是否排在前面。

#### 4.3.2 Context Recall

`context_recall@K = covered_gold_chunks / total_gold_chunks`

如果一个 query 对应多个 gold chunk，则按 gold 集合覆盖率计算；没有可用 gold 的 case 不参与该指标，并记录 exclusion。

#### 4.3.3 Faithfulness

将答案拆为可验证的原子陈述，由 judge 判断每条陈述是否能从给定上下文推出：

`faithfulness = supported_claims / total_claims`

无答案或无法拆出陈述时不计算，避免把空答案误判为满分。

#### 4.3.4 Answer Relevancy

由 judge 根据 query 和答案给出 0 到 1 的相关性分数。答案为空、judge 返回非法结果或调用失败时跳过。该指标只衡量是否回答问题，不替代事实正确性。

### 4.4 Judge 接口与输出约束

评测层通过一个最小 judge 接口获取结构化结果，生产代码不依赖具体模型：

- 输入：`query`、`context`、`answer`。
- 输出：`faithfulness`、`answerRelevancy`、可选 `claims`、`reason`。
- 分数必须归一化到 `[0, 1]`；非法 JSON、越界分数、超时和异常均视为不可评测。
- judge 调用失败不得阻塞检索评测，报告写入 `status=skipped` 和 `skipReason`。

### 4.5 配置

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

### 4.6 报告结构

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

#### 4.6.1 聚合规则

- `contextPrecisionAt5` 与 `contextRecallAt5` 对所有可建立 gold 的检索 case 计算。
- `faithfulness` 与 `answerRelevancy` 只对 judge 成功的采样 case 计算。
- 聚合使用宏平均，并同时记录 `evaluated/skipped`。
- 空集合不输出 `0.0` 冒充真实结果，使用 `null` 并记录原因。

### 4.7 测试验收标准

#### 4.7.1 单元测试

- 多个 gold chunk 的 Context Recall 计算正确。
- 相关 chunk 排名靠前时 Context Precision 高于排名靠后时。
- judge 分数越界、空答案、异常调用会被跳过而非污染均值。
- disabled 配置不触发 judge。

#### 4.7.2 集成/回归测试

- fixture 默认评测仍通过，既有 Recall/MRR/Hit 字段数值不变。
- `ragas.enabled=false` 时不增加外部模型调用。
- 开启后，报告包含 `ragas.status`、四项指标和 skip 统计。
- judge 不可用时测试仍能完成，且报告明确记录 `judge_unavailable`。

#### 4.7.3 当前计划用例映射

本章节只覆盖总计划中的 RAG 指标与检索质量验收；任务、记忆、Skill、Webhook、并发和浏览器 E2E 的全局实施约束见本 Spec 第 1 至 3 节。对应关系如下：

| TC-ID | 阶段 | 验收内容 | 当前入口与报告 |
| --- | --- | --- | --- |
| TC-G0-03a | G0 | Context Precision/Recall 的公式、多个 gold chunk、空集合和排名边界。 | `RagAsMetricsTest`；Surefire 报告。 |
| TC-G0-03b | G0 | 冻结 replay 的数据集加载、指标和报告 schema。 | `RagEvaluationDatasetLoaderTest`、`RagFastRegressionEvaluatorTest`；`backend_v2/target/rag-eval/`。 |
| TC-G0-03c | G0 | fixture 检索回归、既有 Recall/MRR/Hit 不退化，fixture Recall@5 为 `1.0`。 | `RagRecallEvaluationTest`；`backend_v2/target/rag-eval/`。 |
| TC-G0-03d | G0 | `ragas.enabled=false` 不调用 judge；judge 异常、非法分数和空答案记录 skip。 | RAGAS judge 相关单元/集成测试；Surefire 或 RAG 评测报告。 |
| TC-G2-03 | G2 | 固定检索与 Router 链路在同一冻结数据集、配置版本和成本口径下对比。 | Router 实现后复用 RAG 评测入口并新增对比报告。 |
| TC-G2-04 | G2 | 多模态 golden case 的上下文覆盖与引用定位。 | 多模态摄入实现后新增受控评测数据与报告。 |
| TC-G2-05 | G2 | 无答案、权限越界和拒答 case 不进入无效的 Context Recall 聚合。 | 拒答评测实现后复用 RAGAS 报告的 `evaluated/skipped` 口径。 |

执行 `TC-G0-03a` 至 `TC-G0-03d` 时，报告必须记录数据集版本、评测配置、`ragas.enabled`、sample size、judge 模型（如启用）、成本、延迟、`evaluated/skipped` 及 skip reason。真实 KB、Router 与多模态能力尚未完成时，对应 G2 用例保持“待该阶段实现”，不得替代为 fixture 通过。

#### 4.7.4 与总计划一致的通过判定

- 任何指标或报告字段变更都必须同时满足本节验收和总计划 `TC-G0-03` / `TC-G2-03` 的证据记录要求。
- RAG fixture Recall@5 必须保持 `1.0`；真实 KB 或 Router 结果必须同固定 G0 基线比较。
- p95 延迟最多增加 15%，token/调用成本最多增加 10%，错误率最多增加 1 个百分点；超限时不得默认启用新链路。
- judge 指标默认关闭且不作为独立发布阻断门禁；启用时的失败、超时和不可评测结果必须显式记录，不能以 `0.0` 代替。

### 4.8 非功能约束

- 不新增依赖，不修改数据库 schema。
- 评测调用必须有样本上限、上下文字符上限和超时/异常降级。
- 报告字段命名使用现有 camelCase 风格。
- 该指标属于诊断能力，默认不作为生产发布阻断门禁；后续积累人工校准数据后再设置阈值。

### 4.9 待后续迭代

- 增加人工标注的 Answer Correctness、Citation Precision/Recall 和 Abstention Accuracy。
- 对多跳问题从 chunk 命中升级到 gold facts 覆盖率。
- 将 judge 一致性、成本、延迟纳入报告。
- 评估独立 Python 评测工具与现有 Java 报告的互操作，而不是直接耦合到生产服务。
