# RAG 评测与数据集规范化推进计划

## 1. 背景与目标

当前 `RagRecallEvaluationTest` 已能输出 Recall、MRR、Coverage、多样性和 RAGAS 风格的 Context Precision/Recall。但最新跨文档 fixture 实测耗时约 `1408s`（23 分 28 秒），不适合作为每次改动的默认回归。

本计划的目标不是继续增加指标数量，而是让评测结果具备四个属性：

1. **可重复**：相同代码、模型、数据、配置必须得到可解释的可比结果。
2. **可归因**：能区分数据变化、chunk 变化、embedding 变化和检索策略变化。
3. **可持续**：快速回归不依赖全量 embedding；完整评测可阶段性执行。
4. **可信**：gold 标注、负样本、无答案样本、权限边界和多轮样本都有明确规范。

## 2. 当前基线

最新 fixture 评测（2026-08-12）已完成，Surefire 记录：

- `Tests run: 1, Failures: 0, Errors: 0`
- 总耗时：`1408.26s`
- `Recall@1=0.9531`，`Recall@5=1.0000`，`MRR@3=0.9732`
- `Context Precision@5=0.9732`，`Context Recall@5=1.0000`

耗时由 fixture 文档重复导入、chunk embedding、数百条自动生成 query 的 query embedding，以及每条 query 的多路召回和 rerank 共同构成。该评测属于阶段性验收，不应再被视为快速 fixture。

## 3. 新的评测金字塔

| 层级 | 目的 | 数据量与运行方式 | 目标耗时 | 合并门禁 |
| --- | --- | --- | --- | --- |
| L0 指标单测 | 校验公式与边界 | 内存数据；不启动 Spring | 秒级 | 是 |
| L1 快速检索回归 | 发现算法/报告回归 | 冻结小型索引或 mock 结果；20-40 个手工 case | 1 分钟内 | 是 |
| L2 日常真实回归 | 验证真实 KB 代表性样本 | 冻结 KB 快照；40-100 case；不重建索引 | 5 分钟内 | RAG 改动必跑 |
| L3 完整阶段验收 | 检验跨文档、多轮与 hard case | 全量冻结评测集；允许重建索引 | 30 分钟内 | 阶段性/发布前 |
| L4 公开基准 | 检查可迁移性 | Multi-CPR 等公开数据 | 小时级 | embedding/召回策略变更 |

### 3.1 各层强制输出

- L0：指标单测通过。
- L1/L2：Recall@K、MRR、Coverage、Context Precision/Recall、耗时、数据集版本、索引版本。
- L3：增加按 query type、文档来源、难度、多轮状态的分桶；保留新增/修复 miss case。
- L4：公开基准分数、语料采样规则、模型版本和硬件/服务配置。

## 4. 数据集目录和版本规范

建议在后续实现时建立以下目录，评测数据不混入业务上传数据：

```text
backend_v2/src/test/resources/rag-eval/datasets/
├── manifests/
│   ├── fixture-v1.json
│   ├── regression-v1.json
│   └── release-v1.json
├── corpus/
│   └── <dataset-id>/<document-id>.md
├── cases/
│   └── <dataset-id>.jsonl
└── reports/
    └── schema-v1.json
```

数据集版本采用 `name-vN`，只要发生以下任一变化就必须升版本：

- 文档正文、解析器、chunking 策略、metadata 模式变化。
- embedding 模型、维度或 embedding 输入拼接方式变化。
- case、gold、难度标签、切分规则变化。

`manifest` 是唯一运行入口，最少包含：

```json
{
  "datasetId": "regression-v1",
  "schemaVersion": "1",
  "corpusSha256": "...",
  "caseFile": "cases/regression-v1.jsonl",
  "caseSha256": "...",
  "chunkingVersion": "markdown-section-v1",
  "embeddingModel": "bge-m3",
  "embeddingInputVersion": "title-title-content-v1",
  "defaultTopK": 10,
  "createdAt": "2026-08-12",
  "status": "frozen"
}
```

禁止直接覆盖 `frozen` 数据集；修订必须创建新版本，并在报告中携带 manifest hash。

## 5. Query Case Schema

一个评测 case 使用 JSONL 一行一条，建议字段如下：

```json
{
  "caseId": "reg-v1-0001",
  "datasetId": "regression-v1",
  "query": "退款审核一般需要多久？",
  "queryType": "content_rewrite",
  "difficulty": "medium",
  "conversation": [
    {"role": "user", "content": "我申请退款了"},
    {"role": "user", "content": "审核一般多久？"}
  ],
  "kbScope": ["kb-fixture-ecommerce"],
  "goldChunkIds": ["refund-policy-2"],
  "goldFacts": ["退款审核时长为 1-3 个工作日"],
  "shouldAbstain": false,
  "sourceDocumentIds": ["refund-policy"],
  "labels": ["time-sensitive", "multi-turn"],
  "createdBy": "manual",
  "reviewStatus": "approved"
}
```

### 必填字段

- `caseId`：稳定且永不复用；不要用数据库 UUID 作为长期 case 身份。
- `query`、`queryType`、`kbScope`、`goldChunkIds`、`shouldAbstain`、`reviewStatus`。
- `datasetId` 用于防止 case 被错误混入其他语料。

### 可选但高价值字段

- `conversation`：follow-up 和 topic switch 必须保留原始上下文。
- `goldFacts`：端到端答案正确性、引用正确性和 Faithfulness 的基础。
- `difficulty`：`easy/medium/hard`，用于避免总体均分掩盖难例退化。
- `labels`：如 `acronym`、`exact-title`、`cross-document`、`no-answer`、`permission-boundary`。

## 6. Gold 标注规范

### 6.1 检索 gold

- 一个 case 可以有多个 `goldChunkIds`，支持同义段落、父子 chunk 或多跳回答。
- gold 只能引用当前 manifest 中存在的稳定 chunk 标识；禁止仅依据导入时生成的随机 UUID。
- 建议使用 `documentLogicalId + sectionPath + chunkOrdinal` 作为逻辑 chunk id，数据库 id 仅是运行时映射。
- 自动对齐（内容匹配、标题锚点、序号）只能用于生成候选；进入 `frozen` 数据集前必须人工确认或抽样复核。

### 6.2 答案 gold

- 可回答问题应有最小 `goldFacts`，每条事实对应至少一个 gold chunk。
- `shouldAbstain=true` 的 case 不设置事实 gold，并说明拒答原因：`out_of_scope`、`missing_evidence`、`permission_denied`。
- 不要求一开始写长参考答案；优先标注可验证事实，避免答案风格影响正确性判断。

### 6.3 标注质量

- 每个 release 数据集至少对全部 hard/no-answer/permission case 双人复核。
- 标注分歧保留 `adjudicationNote`，不静默覆盖。
- 记录 `createdBy/reviewedBy/reviewedAt`，使误标可追溯。

## 7. 样本构成与切分

### 7.1 推荐比例

| 类型 | 建议占比 | 目的 |
| --- | --- | --- |
| title/path/exact term | 20% | 保护词面、标题和路径能力 |
| content rewrite/user-like | 30% | 衡量真实自然语言检索 |
| multi-turn follow-up | 20% | 验证上下文补全和排序 |
| cross-document / hard negative | 15% | 检验同名标题、交叉术语与干扰项 |
| no-answer / abstention | 10% | 控制无证据幻觉 |
| permission boundary | 5% | 验证 KB/user scope 隔离 |

### 7.2 train/dev/test 规则

- **test/release**：冻结，只允许新增版本，不用于调权重、改 query 模板或挑选 rerank 特征。
- **dev**：允许用于策略选择和超参数调整。
- **train**：仅用于学习排序、分类器或 prompt 优化。
- 同一文档的相邻 section、同一标题的改写、同一会话的后续问题不得跨集合拆分，否则会产生泄漏。
- 为检验跨文档泛化，release 集应至少保留一组从未用于调参的完整文档。

## 8. 防止数据污染与指标失真

1. 自动生成的 query 不能直接全部作为真实质量结论，必须与人工真实问法混合并标识 `createdBy=synthetic`。
2. rerank 规则、query rewrite 模板、chunking 策略的调优只能看 train/dev，不能反复读取 release miss case 后直接针对性加分。
3. 报告必须显示 `evaluated/total/coverage/excludedReasons`；禁止只展示排除后的高分。
4. 重建索引后不得直接与旧 chunk id 的结果比较，必须通过逻辑 chunk id 或数据集版本对齐。
5. LLM-as-judge 的模型、prompt、temperature、样本数必须写入报告；judge 改动视为评测口径变更。
6. 生产对话脱敏后才能进入候选集，且需要用户/知识库隔离；禁止把跨用户对话用于公共评测语料。

## 9. 指标与门禁

### 9.1 检索指标

- 主指标：`Recall@5`、`MRR@3`、`Context Precision@5`、`Context Recall@5`、Coverage。
- 诊断指标：`Recall@10`、多样性、per-query-type 指标、miss cases。
- 对无答案 case：不计算 Context Recall；单独计算 `Abstention Accuracy`，避免把“检索到随机 chunk”视作成功。

### 9.2 初始门禁

在积累 2-3 个稳定版本前，只做回归阈值，不设置绝对分数门槛：

- L1/L2 的 `Recall@5`、`MRR@3`、`Context Precision@5` 相对基线下降超过 `0.02` 时阻断。
- `Coverage` 下降必须附带明确原因；无说明下降视为阻断。
- release 数据集新增 miss case 必须进入复盘，不允许仅用 overall 均值掩盖。
- Faithfulness/Answer Relevancy 初期仅观测；完成至少 50 条人工校准后才设阈值。

## 10. 执行步骤

### Phase A：先固化数据与快速回归

1. 定义 manifest 和 JSONL schema。
2. 从当前 fixture 挑选 20-40 个人工确认 case，建立 `fixture-fast-v1`。
3. 为每个 case 建立逻辑 chunk id 和 gold facts。
4. 将 L1 改为读取冻结索引或预生成 embedding，目标 1 分钟内。

验收：同一机器连续两次结果一致；报告含 datasetId、manifest hash、四项上下文指标。

#### Phase A 实施状态（2026-08-12）

已完成：

- 建立 `fixture-fast-v1` 冻结 manifest、20 条 JSONL case 与四份公开 fixture corpus 的 SHA-256 校验。
- case 覆盖可回答、多轮、跨文档、无答案和权限边界；gold 使用逻辑 chunk id 而非运行期 UUID。
- 建立版本化 retrieval replay 的 L1 快速回归，不启动 Spring、PostgreSQL 或 Ollama。
- 报告已输出 `datasetId`、`manifestSha256`、`executionMode`、`Recall@5`、`MRR@3`、Context Precision/Recall `@5/@10`、拒答准确率。

最新 L1 replay 快照：`total=20`、`answerable=18`、`abstentionTotal=2`、`Recall@5=1.0000`、`MRR@3=0.9444`、`Context Precision@5=0.9444`、`Context Recall@5=1.0000`、`Abstention Accuracy=1.0000`。

注意：该快照只验证数据集和报告回归，不能代替实时检索质量评测。

Phase A 验证命令：

```powershell
cd backend_v2
.\mvnw.cmd -q "-Dtest=RagAsMetricsTest,RagEvaluationDatasetLoaderTest,RagFastRegressionEvaluatorTest" test
.\mvnw.cmd -q -DskipTests test-compile
```

两项均已通过。

### Phase B：建立真实 KB 回归集

1. 从真实知识库脱敏采样 80-150 case，覆盖上述六类 query。
2. 将历史 miss case、hard negative、无答案和权限隔离 case 固化为 `regression-v1`。
3. 标注 gold chunk 与 gold facts，完成抽样双人复核。
4. L2 默认禁止重建语料和 embedding，目标 5 分钟内。

验收：任何 RAG 改动都能在固定数据集上前后比较；报告能定位到 query type 和 caseId。

#### Phase B 前置条件

真实知识库与生产对话可能含用户数据。执行前必须获得明确授权，并且仅允许读取指定知识库范围；不读取 `.env`、运行配置或无关数据，不向数据库写入或修改业务记录。样本需先脱敏，经人工确认后才能冻结为 `regression-v1`。

#### Phase B 实施状态（2026-08-13）

已完成：首批 `regression-v1-candidate` 24 条技术资料候选的来源文件 SHA-256、原始标题锚点和显式拒答语义核验。候选 schema 已能表达 `conversation` 与 `additionalGoldLogicalChunkIds`；每个逻辑 chunk 的结构化锚点包含独立来源哈希，跨文档 gold 可逐项验证。

候选集使用显式 `retrievalGoldLogicalChunkIds` 作为检索评测 gold；`logicalChunkId` 仅保留主来源锚定职责。拒答 case 的 retrieval gold 和事实 gold 均为空，避免审核来源段落被误算进 Recall/MRR/Context 指标。

候选审核状态要求：所有 case 都必须记录 `createdBy`；`candidate` 不得携带审核人/审核时间，`approved` 和 `rejected` 必须同时携带 `reviewedBy` 与 ISO-8601 `reviewedAt`。当前 40 条候选已经完成模型辅助内容审核：39 条 `approved`、1 条 `rejected`；审核身份明确为 `codex_content_review`，不替代 release 所需的人工抽样/双人复核。

候选就绪度使用固定报告输出当前样本数、`eligibleCases`、`runtimeEligibleCases`、唯一检索 gold、query type 覆盖、多轮/跨文档/拒答计数与冻结阻塞项。冻结指标只计算 `approved` case，`rejected` case 只保留审核追溯，不能用来满足数量或覆盖门槛；L2 真实运行还必须要求所有 gold 都属于已有真实 KB 可映射来源。2026-08-13 最新快照为 58 条 case、57 条 eligible/approved、48 个唯一 retrieval gold、40 条 runtime eligible、38 个 runtime gold、2 条拒答、1 条多轮、1 条跨文档、1 条 rejected；运行期样本量门槛已满足，自动检查仅保留运行期 UUID 映射阻塞项。

运行期 UUID 映射已具备严格只读入口和纯内存规则测试，但尚未执行：测试仅在显式启用开关与指定 KB UUID 时创建 Spring 上下文，读取 `chunk_bge_m3` 的标题/路径候选；无匹配和多匹配均保留在报告中，冻结集不保存 UUID。仅具备真实 KB `sourceName` 映射的逻辑 chunk 会进入该步骤；受控 Agent 候选语料没有数据库副本，明确排除而非记为 `unmapped`。该步骤仍需要单独的数据库只读授权。

本轮扩样：新增一份测试资源内的 `synthetic/project-grounded` Agent 候选语料，形成 16 条 Agent case；包括 1 条真实 follow-up 和 1 条与原始面试资料组合的跨文档 case。该语料具有 SHA-256 与标题锚点，可用于 schema、gold 和候选流程验证，但不属于真实用户上传资料，也不能作为 release 集“未用于调参的完整真实 KB”。

未完成：仍需获得运行期 UUID 的只读映射授权，并从新增明确授权、未用于调参的完整真实 KB 采样；release 集的 hard/no-answer/permission case 仍需人工双人复核。

#### L2 候选运行器（2026-08-13）

已新增纯内存的 `RagRegressionCandidateRuntimeEvaluator`。它不启动 Spring、不读取数据库、不调用 embedding 或模型服务；输入为：

1. 已审核的 `regression-v1-candidate`。
2. `RagRegressionCandidateChunkUuidMappingTest` 产生且 `executionStatus=read_only`、`knowledgeBaseId` 精确等于候选集 `sourceKnowledgeBaseId` 的 UUID 映射报告。
3. 同一版本、同一 KB、同一检索策略采集的 `caseId -> topRuntimeChunkUuids + abstained` replay。

运行器只评估 `approved` 且全部 retrieval gold 都具有唯一 `mapped` UUID 的真实 KB case。受控 `synthetic/project-grounded` Agent 语料和 `rejected` case 不进入 L2 分母；映射报告不是只读结果或 KB ID 不匹配、缺失 replay、`unmapped` 或 `ambiguous` gold 都会直接失败，禁止静默排除。输出 `Recall@5`、`MRR@3`、Context Precision/Recall@5、Abstention Accuracy 与各分母；没有拒答 case 的子集以 `abstentionCases=0 / abstentionAccuracy=0` 明确表达，不写入 `NaN`。

该运行器的纯内存测试已通过。真实 L2 仍不能运行：运行期候选已扩充至 40 条 approved case，但 UUID 映射尚未获得数据库只读授权；完成映射后还需采集同环境的真实检索 replay。

文件入口：`RagRegressionCandidateRuntimeEvaluationTest` 默认跳过，只有同时显式提供 mapping 与 replay 文件才执行。运行期 replay 是 UTF-8 JSONL，每行包含 `caseId`、`topRuntimeChunkUuids`、`abstained`；它必须精确覆盖所有 approved、真实 KB 来源 case。可在 UUID 只读映射与真实检索采集完成后运行：

```powershell
cd backend_v2
.\mvnw.cmd -q "-Dtest=RagRegressionCandidateRuntimeEvaluationTest" `
  "-Drag.eval.runtime.mapping-path=target\rag-eval\candidates\regression-v1-candidate-chunk-uuid-mapping.json" `
  "-Drag.eval.runtime.replay-path=<已采集的runtime-replay.jsonl>" test
```

报告固定写入 `target/rag-eval/candidates/regression-v1-candidate-runtime-report.json`。该命令只读取两个本地输入文件，不读取数据库、不调用模型服务；真实检索 replay 的采集步骤必须在单独授权下执行。

### Phase C：阶段性完整验收

1. 建立跨文档、多轮与未见文档组成的 `release-v1`。
2. 运行 full-chain、no-query-expansion、no-rerank 消融。
3. 对有答案 case 开启有限样本 judge，对 no-answer case 评估拒答。
4. 汇总质量、延迟、成本与新增/修复 miss。

验收：30 分钟内完成；每次策略结论有可追溯数据和消融证据。

## 11. 本计划的非目标

- 不在本计划中直接改生产检索参数、替换 embedding 模型或引入多 Agent。
- 不将自动生成 query 视为人工标注的等价物。
- 不用单一 overall 分数宣布 RAG 已优化。

## 12. 推荐优先级

1. 先做 Phase A 的 manifest、逻辑 chunk id 和 `fixture-fast-v1`。
2. 再做 Phase B 的真实 KB 冻结回归集与 hard/no-answer case。
3. 最后做 Phase C 的答案质量、拒答和公开基准对照。

在数据与评测口径固化前，不建议继续细调 rerank 权重或扩大 LLM rewrite。
