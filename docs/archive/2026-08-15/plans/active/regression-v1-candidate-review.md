# regression-v1 候选集人工审核清单

## 审核范围

- 来源范围：已授权知识库 `34d6eabb-9823-434a-9966-bc9eaa103739` 的技术面试 Q&A、SQL 调优资料；以及测试资源内受控的 `synthetic/project-grounded` Agent 候选语料。
- 来源校验：候选 JSON 已记录三份文件 SHA-256；Agent 语料位于 `rag-eval/datasets/corpus/agent-harness-candidate-v1/`，不混入业务上传目录。
- 来源锚点：40 条 case 的 41 个检索 gold 均在 `regression-v1-candidate-anchors.json` 中绑定原始 Markdown 标题；跨文档 case 037 有两个 gold。
- 样本状态：候选集仍为 `candidate`，尚未进入冻结 `regression-v1`，不作为发布门禁。
- 审核元数据：所有 candidate 已记录 `createdBy=manual`；candidate 不得填写 `reviewedBy/reviewedAt`。将状态变为 `approved` 或 `rejected` 时，必须同时填写审核人和 ISO-8601 审核时间。
- 数据约束：本清单不复制原始文档正文，只保留查询、逻辑章节和最小事实摘要。
- 检索 gold：`retrievalGoldLogicalChunkIds` 是唯一可用于 Recall/MRR/Context 指标的候选字段；`logicalChunkId` 仅用于主来源锚定与人工审核，不得再由评测器隐式当作 gold。
- 拒答口径：`reg-candidate-023` 标注为 `shouldAbstain=true / missing_evidence`，`reg-candidate-024` 标注为 `shouldAbstain=true / permission_denied`；两条均不携带事实 gold，后续只进入 Abstention Accuracy，不进入 Context Recall。
- 运行期 UUID：可使用 `RagRegressionCandidateChunkUuidMappingTest` 只读调用 `selectTitlePathCandidatesByKbIds`，仅按 `sourceName + retrievableTitle/title` 的精确匹配输出 `mapped/unmapped/ambiguous`；禁止自动从多候选中任选一个。受控 `agent-harness-candidate` 语料没有运行期知识库映射，明确排除在数据库映射范围外，不记为 `unmapped`。

## 当前样本构成

- 总计 58 条：56 条可回答，2 条应拒答；其中 57 条 approved、1 条 rejected。冻结门槛以 `approvedCases` 计，不以候选总量计；rejected case 保留用于审核追溯，但不参与检索 gold、覆盖或门槛统计。
- 覆盖 title/content rewrite、自然问法、hard negative、no-answer、权限边界、1 条多轮 follow-up（028）和 1 条跨文档问题（037）。
- 数量达到 L2 的最低门槛，但 Agent 语料是受控合成候选，不可替代未用于调参的完整真实 KB；因此仍不等同于 Phase B 完成或发布集。

## 就绪度快照

执行以下测试会写入当前候选集的临时就绪度报告：

```powershell
cd backend_v2
.\mvnw.cmd -q "-Dtest=RagRegressionCandidateReadinessReportTest" test
```

报告路径：`target/rag-eval/candidates/regression-v1-candidate-readiness-report.json`。它包含 case 总数、唯一 retrieval gold、各 query type、拒答/多轮/跨文档数和冻结阻塞项。

2026-08-13 最新快照：`totalCases=58`、`eligibleCases=57`、`uniqueRetrievalGoldChunks=48`、`runtimeEligibleCases=40`、`runtimeUniqueRetrievalGoldChunks=38`、`abstentionCases=2`、`multiTurnCases=1`、`crossDocumentCases=1`、`approvedCases=57`、`rejectedCases=1`。若 approved 样本不足 40，报告写入 `insufficient_approved_case_count`；若可映射到真实 KB 的 approved 样本不足 40，报告写入 `insufficient_runtime_eligible_case_count`。真实运行样本量门槛已满足，当前自动检查仅剩 `runtime_uuid_mapping_not_completed`；发布前仍需满足真实未见 KB 的治理要求。

## 扩样边界与下一批采样要求

- 当前 57 条 approved 样本覆盖 48 个唯一逻辑 chunk，其中 40 条、38 个 gold 属于已有真实 KB 的运行期映射范围；不允许对同一标题反复改写问题来伪造 80 条样本量。
- `conversation` 和 `additionalGoldLogicalChunkIds` 已由 028、037 填充；后续仍需从真实生产脱敏对话构造更多多轮与跨文档样本。
- 下一批至少新增一个完整、已授权且未用于调参的文档；同一文档相邻章节、同一标题改写、同一会话不得跨 dev/release 切分。
- 建议先以不同逻辑 chunk 或不同证据组合形成 40-60 条人工审核候选，再冻结为 `regression-v1`，而不是一次性追求数量。

## 已完成的本地可追溯性核验（2026-08-12）

- 执行命令：`./mvnw.cmd -q -Dtest=RagRegressionCandidateSourceVerificationTest -Drag.eval.candidate-source-root=<已授权知识库目录> test`。
- 最新结果：40 条 case 的 41 个检索 gold 的来源 SHA-256 和原始标题锚点均匹配，报告写入 `backend_v2/target/rag-eval/candidates/regression-v1-candidate-source-report.json`。
- 核验读取两份明确授权的 Markdown 文件与测试资源内的受控 Agent 候选语料；报告的 `runtimeChunkUuidMappingStatus=not_attempted`，不代表数据库 chunk UUID 已经映射。

## 审核规则

每条样本需确认：

1. query 是否自然、无个人信息或运行期数据。
2. 逻辑章节是否确实可支持事实摘要。
3. 事实摘要是否精确、没有超出来源文档。
4. `no_answer` / `permission_boundary` 是否应拒答，而不是检索其他内容回答。
5. 确认后在候选 JSON 中将该条 `reviewStatus` 改为 `approved`，并在后续冻结 manifest 中记录审核人和日期。
6. 如需验证逻辑 chunk 对运行期 UUID 的映射，必须单独获得只读数据库授权；即使完成映射，冻结集仍不得保存运行期 UUID。

只读 UUID 映射仅可在获得授权后使用以下显式开关运行：

```powershell
cd backend_v2
.\mvnw.cmd -q "-Dtest=RagRegressionCandidateChunkUuidMappingTest" "-Drag.eval.uuid-mapping.enabled=true" "-Drag.eval.uuid-mapping.kb-id=<明确授权的知识库UUID>" test
```

不传两个开关时，测试类会在 Spring 上下文创建前跳过；成功执行只在 `target/rag-eval/candidates/` 写临时映射报告。

当前真实 KB 映射范围是 22 个逻辑 chunk。它只覆盖 `interview-qa` 与 `sql-tuning`；运行映射后应审查 `mapped/unmapped/ambiguous` 分布，且不得把受控 Agent 候选语料的“无数据库副本”误判为映射失败。

L2 运行时评测使用 `RagRegressionCandidateRuntimeEvaluator`：只接受 `executionStatus=read_only` 且 `knowledgeBaseId` 精确等于候选集 `sourceKnowledgeBaseId` 的映射报告，以及相同环境采集的 UUID replay；任何 approved 真实 KB case 的 gold 无唯一映射，或 replay 缺失，都会失败。它不读取数据库，也不代替 UUID 映射或真实检索采集。

运行入口 `RagRegressionCandidateRuntimeEvaluationTest` 必须显式传入 `rag.eval.runtime.mapping-path` 和 `rag.eval.runtime.replay-path`；文件中的 caseId 集合必须精确等于所有 approved 的真实 KB case，不能夹带受控 Agent 候选或遗漏拒答 case。

## 内容审核结果（2026-08-13）

- 审核身份：`codex_content_review`，这是按人工内容审核标准执行的模型辅助审核，不宣称真人复核。
- 审核方法：逐条核验 query 的自然性、`goldFacts` 是否由绑定标题支持、是否超出来源表述，以及两条拒答是否确属证据或权限边界。
- 结论：39 条 `approved`，1 条 `rejected`。025-040 按文档明确的“已实现 / 规划中”边界审核，不将记忆系统规划描述为上线事实；通过条目已写入 `reviewedBy` 和 `reviewedAt`。
- 建议人工抽检：两条拒答（023、024）与涉及系统现状的 SQL 条目（016-021），确认它们仍符合实际运行环境。

| Case | 结论 | 审核依据 |
| --- | --- | --- |
| 001-021 | approved | 绑定标题可直接支持问题和最小事实；事实均未要求超出文档的实现承诺。 |
| 022 | rejected | 问题要求同时覆盖“已做过、已具备、后续优化”三段，但仅绑定“已做过的”单一 logical chunk，检索 gold 不完整，不能量化该问题的 Recall。 |
| 023 | approved | 原文明确要求索引执行状态以实际库为准，不能据此断言所有线上索引已执行；`missing_evidence` 拒答合理。 |
| 024 | approved | 该问题索取其他用户的运行期安全数据，属于 `permission_denied`，不得以知识库内容替代授权校验。 |

## 候选样本

| Case | 类型 | 难度 | 逻辑章节 | 最小事实摘要 | 审核 |
| --- | --- | --- | --- | --- | --- |
| 001 | user_like_question | easy | 项目概览 / 用户认证实现 | JWT、拦截器与登录校验 | approved |
| 002 | content_rewrite | medium | 项目概览 / 密码存储 | 安全哈希替代明文或 MD5 | approved |
| 003 | title_to_content | easy | 认证授权 / 区别 | 认证确认身份，授权决定权限 | approved |
| 004 | content_rewrite | medium | 认证授权 / 异步显式传参 | 异步传最小必要字段 | approved |
| 005 | user_like_question | medium | 认证授权 / 最小必要字段 | 消息不传完整用户对象 | approved |
| 006 | user_like_question | medium | 验证码安全 / 防刷 | 限流与失败次数控制 | approved |
| 007 | content_rewrite | medium | 验证码安全 / 失败次数 Redis | 高频计数和过期控制 | approved |
| 008 | user_like_question | hard | 验证码安全 / Redis 不可用 | 安全校验偏向拒绝 | approved |
| 009 | content_rewrite | easy | JWT / 最小载荷 | 避免放入不必要用户信息 | approved |
| 010 | user_like_question | hard | JWT / 封禁用户 | 需要失效策略处理状态变更 | approved |
| 011 | content_rewrite | hard | JWT / 退出登录 | 需要服务端失效策略 | approved |
| 012 | title_to_content | easy | 异常日志 / 全局异常处理 | 集中异常映射 | approved |
| 013 | user_like_question | medium | 异常日志 / 日志脱敏 | 敏感字段需脱敏 | approved |
| 014 | content_rewrite | medium | MQ / 验证码邮件异步 | 邮件发送与主链路解耦 | approved |
| 015 | user_like_question | hard | MQ / Redis List 对比 | 确认、重试、死信能力 | approved |
| 016 | content_rewrite | easy | SQL / 时间范围查询 | 函数处理时间列影响索引 | approved |
| 017 | topic_switch_guard | medium | SQL / 全文索引 | 搜索不只依赖 LIKE | approved |
| 018 | user_like_question | medium | SQL / 联合索引 | 匹配组合查询条件 | approved |
| 019 | content_rewrite | medium | SQL / 排行榜统计 | 聚合与窗口函数 | approved |
| 020 | user_like_question | medium | SQL / 深分页 | LIMIT OFFSET 深分页性能问题 | approved |
| 021 | hard_negative | hard | SQL / 索引执行状态 | 脚本存在不代表环境已执行 | approved |
| 022 | user_like_question | hard | SQL / 稳妥表述 | 区分已做、能力与后续优化 | rejected |
| 023 | no_answer | hard | SQL / 索引执行状态 | 无法证明所有环境已执行 | approved |
| 024 | permission_boundary | hard | 验证码安全 / 失败次数 Redis | 不应跨用户暴露 | approved |
| 025 | user_like_question | medium | Agent / 执行主链路 | pre-flight 与 synthetic response | approved |
| 026 | content_rewrite | hard | Agent / 两层工具保护 | Factory 覆盖与 pre-flight 分工 | approved |
| 027 | user_like_question | easy | Agent / 人工审批 | 高风险工具先审批 | approved |
| 028 | multi_turn_follow_up | hard | Agent / 人工审批 | 同 step 同名工具聚合审批 | approved |
| 029 | user_like_question | medium | Agent / 熔断恢复 | OPEN 与 HALF_OPEN | approved |
| 030 | hard_negative | hard | Agent / 人工审批 | 审批拒绝不计熔断失败 | approved |
| 031 | content_rewrite | medium | Agent / 审计 | 结构化 outcome | approved |
| 032 | user_like_question | easy | Agent / SSE | TOOL_APPROVAL_REQUIRED 时机 | approved |
| 033 | hard_negative | hard | Agent / 审计 | 内存状态不保证重启恢复 | approved |
| 034 | topic_switch_guard | hard | Agent / 记忆边界 | 规划不等于已上线 | approved |
| 035 | content_rewrite | medium | Agent / 记忆原则 | 节流减少无意义提取 | approved |
| 036 | user_like_question | medium | Agent / 记忆原则 | 相似不等于相同 | approved |
| 037 | cross_document | hard | Agent 审计 + 异步消息 | 审计追溯与最小字段 | approved |
| 038 | hard_negative | hard | Agent / 执行主链路 | Harness 不可被模型绕过 | approved |
| 039 | hard_negative | hard | Agent / 熔断恢复 | 熔断不替代事实与权限校验 | approved |
| 040 | topic_switch_guard | medium | Agent / 采样提示 | 候选语料不等于线上事实 KB | approved |
| 041 | user_like_question | hard | Agent / 回答边界示例 | 规划不等于已上线保证 | approved |
| 042-058 | 多种 | easy-hard | 真实面试 Q&A 独立章节 | 认证、验证码安全、缓存一致性与 JWT 边界 | approved |

## 冻结条件

只有同时满足以下条件，才能生成 `regression-v1`：

- 至少 40 条 approved 样本均完成审核，记录审核人和日期；rejected 样本不进入冻结集。
- 每条 approved case 的逻辑章节与来源 SHA-256 可追溯。
- `no_answer` 与 `permission_boundary` 样本明确拒答预期。
- 建立与当前数据库 chunk UUID 的映射，但冻结集仍以逻辑 chunk id 为准。
- 使用新 manifest hash，禁止覆盖候选集或 `fixture-fast-v1`。
- release 集必须额外保留一份未用于调参的完整真实 KB；本轮 Agent 合成候选语料不能充当该文档。
- 042-058 虽来自明确授权的真实 KB 且可进入运行期 UUID 映射，但与此前候选同属调参/候选范围，不能充当 release 集的未见文档。
