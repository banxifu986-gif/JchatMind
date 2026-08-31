# 可信研发知识协作 Agent 实施 Spec

> 状态：当前唯一实施 Spec
> 对应计划：[可信研发知识协作 Agent 升级总计划](../plans/active/trusted-knowledge-agent-roadmap.md)
> 当前实施阶段：G0 已结项。G1 核心权限、任务摄入、真实队列/数据库、单实例任务 SSE、MCP 主体与真实模型工具调用已签收，受控 KB 删除任务隔离 L2 和 Edge Playwright L3 已通过，生产迁移入口仍待完成。G2 已完成 VectorChord BM25、Router/资产入口、独立三路实现与真实结构消融；R2 低于 R0且拒答门禁失败，TEI 也未通过延迟门禁，因此默认结构和 rerank 开关保持不变，当前转入 Bad Case、release-v1、引用/拒答和 Router 消融收口。G3、G4、G5 均为部分实现，LongMemEval、真实集成、负载、持久化和多实例验收尚未完成。`TC-REL-02` 的迁移执行器 L0/L1 和真实隔离 PostgreSQL 生命周期 L2 已通过，完整发布 catalog 对账和生产发布入口仍待完成。

## 1. 文档定位与范围

本 Spec 是总计划的实现级约束，定义需求到测试的追溯、测试优先开发方式、隔离环境和验收证据。总计划负责阶段目标、优先级和退出条件；架构文档负责当前实现与源码导航；本 Spec 负责“在当前阶段具体要实现什么、先写什么测试、如何证明通过”。

当前版本保留 G0 验收事实，并已记录 G1 的 owner-only 知识库硬权限、任务状态机、异步摄入、幂等、重试、脱敏任务进度查询及前端 SSE/轮询兜底实现。隔离真实数据库、队列、HTTP/JWT、MCP、advisory lock、文件补偿、Markdown/HTML embedding、外部 embedding 自动恢复、已认证任务 SSE HTTP 多连接、浏览器 Bearer SSE 连接后事件、真实模型 Agent 工具调用和生产 MCP 主体协议均已补齐对应证据；已在本 Spec 具备明确契约的 G2/G3/G5 子项可按对应边界实施，其余能力不构成提前开发授权。

不新增平行 Spec。RAGAS 指标作为本 Spec 的专项章节维护；任务、Router、记忆、Webhook、并发和浏览器 E2E 的实施契约同样在本文件持续补充。

## 2. SDD 需求追溯

每项实现必须从“阶段交付 -> 行为契约 -> `TC-ID` -> 测试代码/报告”单向追溯，并可从报告反查需求。未绑定 `TC-ID` 的需求不得进入实现。

| 阶段 | 实现级契约 | 必需测试入口 | 当前状态 |
| --- | --- | --- | --- |
| G0 | 聊天、RAG、SSE、审批在隔离环境可观测且可回归；不改变现有公开 API。 | `TC-G0-01` 至 `TC-G0-06` | 2026-08-18 已完成全部 G0 必需证据；当时 G1 尚未开始。 |
| G1 | owner-only KB 硬权限、Agent 默认范围关系表、任务状态机、异步摄入、幂等、重试、删除任务和任务进度均必须有确定状态与隔离边界。 | `TC-G1-01` 至 `TC-G1-11`，含 `TC-G1-04a/04b/04c` | 已完成隔离 L2 的 HTTP/JWT、真实队列重试/死信、MCP 主体授权、advisory lock、文件补偿、摄入恢复、Markdown/HTML/PDF 与真实 embedding、任务 SSE、多实例连接、真实模型工具调用和生产 `STREAMABLE` MCP 协议；Edge L3 已覆盖摄入和删除旅程。受控 KB 删除任务已通过真实 PostgreSQL/RabbitMQ、第二账号隔离 L2；仅生产迁移入口仍待完成。 |
| G2 | Router 必须输出受限 schema，并按权限、证据与用户授权决定检索或拒答；普通文本检索必须以可评测的独立分支融合，不能把 query 改写伪装为额外索引票。 | `TC-G2-01` 至 `TC-G2-10` | 确定性 Router、生产入口计划、VectorChord BM25、PDF/Markdown 表格资产和独立三路均已实现；`TC-G2-09/10` 已产出真实结构消融，R2 未通过质量/拒答门禁，保持 R0。仍待 Bad Case 全绿、release-v1、组合授权 L2、Router 消融、引用/答案质量和真实多模态验收。 |
| G3 | Skill 与记忆必须有可验证 schema、所有权和失败不阻断主链路的约束。 | `TC-G3-01` 至 `TC-G3-05` | 候选确认/忽略/编辑/删除、节流、语义去重、冲突、365 天过期治理与首个内置 Skill 已完成 L0/L1；LongMemEval 30 题尚未下载/冻结，记忆 L2/L3、Skill HTTP/队列、模型总结和运行时预算仍待实现。 |
| G4 | 工作流验证与 Webhook 必须对证据、权限、超时、签名、重试和死信作出确定响应。 | `TC-G4-01` 至 `TC-G4-04` | `WorkflowPlanVerifier` 已完成 L0 计划预算、工具白名单、证据和矛盾校验；`WorkflowPlanExecutor` 已完成本地 L1 顺序执行、Harness 放行/拒绝与失败停止。Planner、可中断超时、fallback、持久化审计、Webhook 与投递 schema 仍待实现。 |
| G5 | 并发、缓存和多实例 SSE 必须满足顺序、隔离、背压和恢复语义。 | `TC-G5-01` 至 `TC-G5-04` | `TC-G5-01` 已完成单实例 L0/L1 会话互斥：同会话不重叠、不同会话并行，锁覆盖 Agent 与候选记忆提取；跨 `@Async` 提交顺序、真实负载、多实例、缓存和 SSE 恢复仍待对应契约。 |

阶段实现前必须在本 Spec 的对应小节补充：输入/输出或事件 schema、数据所有权、失败行为、幂等规则、观测字段、非目标和 `TC-ID` 映射。若这些内容不能写清，说明设计尚未达到实现条件。

## 3. TDD 实施协议

除纯文档、配置说明和已批准的生成代码外，所有生产代码变更遵循 Red-Green-Refactor：先写单一行为的测试并确认其因功能缺失而失败；再编写使该测试通过的最小实现；最后在全部相关测试通过后重构。测试不能在新增生产代码后才补写。

### 3.1 G0 测试先行清单

| TC-ID | 先写的失败测试 | RED 通过条件 | GREEN 与回归入口 |
| --- | --- | --- | --- |
| TC-G0-01 | 启动上下文测试，覆盖最小 Bean 图与循环依赖保护。 | 断言因缺少目标 Bean 图或循环依赖而失败，不因测试配置错误失败。 | 最小化配置后通过 `CircularDependencyStartupTest`，再运行相关启动测试。 |
| TC-G0-02 | 聊天链路测试，断言消息持久化、状态完成和历史顺序。 | 未实现的链路或持久化断言明确失败。 | 只实现必要编排后运行聊天链路测试与 G0 手工旅程。 |
| TC-G0-03 | 指标/replay 测试，断言 gold、报告字段和 fixture Recall@5。 | 新指标或报告字段缺失时失败，不能因外部模型不可用而错误。 | `RagAsMetricsTest`、`RagFastRegressionEvaluatorTest`、`RagRecallEvaluationTest`。 |
| TC-G0-04 | SSE 契约测试，覆盖 `AI_CONTENT_DELTA`、`AI_ERROR`、空 `ChatResponse` 帧、发送失败；恢复/去重仍为待补充契约。 | 空帧触发 `getResult()` 空指针，或缺少 `AI_CONTENT_DELTA`/`AI_ERROR` 事件时失败。 | `SseMessageStreamingContractTest`、`JChatMindStreamingSseTest`、`JChatMindErrorSseTest`、`SseServiceImplTest`。 |
| TC-G0-05 | 审批状态测试，断言批准、拒绝、超时均不绕过 Harness。 | 未进入或未正确退出 `WAITING_APPROVAL` 时失败。 | `HarnessRunnerTest` 及审批相关测试。 |
| TC-G0-06 | 前端类型/静态契约与手工旅程，覆盖登录拦截、执行轨迹、审批卡片、回答分片、最终状态收尾、错误提示、新会话隔离和同会话轨迹恢复。 | 缺少 SSE 类型、事件处理、临时回答气泡、最终回答收尾、按会话隔离的 UI 瞬态状态或同会话已接收轨迹恢复时，TypeScript 构建或 Node 静态契约失败。 | `node ui/tests/chat-auth-guard.contract.mjs`、`node ui/tests/execution-trace.contract.mjs`、`node ui/tests/content-delta-rendering.contract.mjs`、`node ui/tests/final-content-status.contract.mjs`、`node ui/tests/hook-state-in-effect.contract.mjs`、`node ui/tests/new-chat-session.contract.mjs`、`node ui/tests/session-trace-cache.contract.mjs`、`npm.cmd run lint`、`npm.cmd run build` 与隔离测试账号手工记录。 |

### 3.1.1 G0 已实施流式契约与续作边界（2026-08-17）

- 后端在每个有效 `AssistantMessage.getText()` 分块时发送 `AI_CONTENT_DELTA`，前端在临时回答气泡中按到达顺序累积；流结束后仍由 `AI_GENERATED_CONTENT` 写入并替换临时气泡，最后发送 `AI_DONE`。
- 兼容中转站产生的 `ChatResponse.getResult() == null` 空帧：空帧不得产生 SSE、不得参与最终消息或工具调用聚合，后续有效分块必须继续发送与持久化。
- 任意 Agent 执行异常必须发送 `AI_ERROR`，不能错误发送 `AI_DONE`。`JChatMindStreamingSseTest` 的空帧用例和 `JChatMindErrorSseTest` 分别固定这两个回归点。
- 若前端收到无 `metadata.toolCalls` 的 Assistant `AI_GENERATED_CONTENT`，必须立即清理“思考中”状态；这是 `AI_DONE` 因 SSE 断连未送达时的前端收尾兜底，不适用于仍含工具调用的中间消息。
- `/chat` 与 `/chat/:chatSessionId` 必须以 `chatSessionId ?? "new"` 作为聊天视图的 React `key`；切换至新会话必须重置初始化防重标记、SSE 临时正文和审批卡片，且不得显示旧会话轨迹；切回同一会话必须恢复当前 SPA 生命周期内已收到的执行轨迹。`new-chat-session.contract.mjs` 与 `session-trace-cache.contract.mjs` 固定此边界。离开会话期间的 SSE 不回放，刷新页面不保留内存轨迹；Ban 已于 2026-08-18 手工签收 RAG 会话切换后轨迹恢复、新会话无旧轨迹及其首条消息持久化，本项不保存截图。
- Ban 已于 2026-08-18 在隔离 RAG 会话中手工观察到最终回答气泡从部分正文持续增长至五点完整回答；前端静态契约将该渲染行为对应到 `AI_CONTENT_DELTA`。`KnowledgeTool` 命中隔离文档，最终态归档为 `backend_v2/target/tc-g0-06-l3-rag-streaming-final-20260818.png`。该截图只证明最终态，正文分片的签收依据为 Ban 的连续页面观察。
- `AI_THINKING` 是应用层执行状态，不是暴露模型原始 `reasoning_content` 的承诺；当前仅流转 Spring AI `AssistantMessage.getText()`，不显示或持久化独立推理字段。
- 当前已通过后端回归命令：在 `backend_v2` 执行 `.\mvnw.cmd -q "-Dtest=SseMessageStreamingContractTest,JChatMindStreamingSseTest,JChatMindErrorSseTest,SseServiceImplTest" test`；在 `ui` 执行六项 G0 静态契约、`hook-state-in-effect.contract.mjs`、`npm.cmd run lint` 与 `npm.cmd run build` 均已通过。lint 仅保留依赖数据过期提示，不再有 Hook 规则诊断；未在本阶段升级依赖。
- `TC-G0-06` 的隔离账号 L3 旅程已签收普通回答逐段显示、RAG 正文增长、`AI_ERROR`、审批批准/拒绝、最终状态收尾、新会话首条消息持久化及同会话轨迹恢复，详细证据在总计划验收台账。
- 2026-08-18 已补齐并复核 `TC-G0-01` 至 `TC-G0-05`：最小及完整应用上下文、真实 Spring 聊天事件边界、SSE/Harness 组合回归、RAG 指标和冻结 replay 均通过。`RagRecallEvaluationTest` 使用隔离 PostgreSQL 与本机 `bge-m3:latest` 完成四文档受控 fixture 检索，`Recall@5=1.0`，Surefire 无失败或错误；它只证明 gold chunk 的 Top-5 覆盖及当前入库/embedding/检索链路可回归，不能外推为真实用户问题、真实 KB 规模、权限隔离、引用准确性或答案忠实性已验证。为避免 A/B 诊断将相同检索集重复三遍，验收命令显式关闭了 A/B 对比，未改变 fixture、embedding 或检索实现。
- 因此 G0 必需证据已齐备。G1 已完成 owner-only 硬权限、Agent 默认范围关系表及任务/摄入的 L0/L1 RED/GREEN 契约；tenant/ACL、真实数据库和队列、真实模型、浏览器旅程仍不得因这些前置而跳过各自的测试先行要求。

### 3.1.2 G1 owner-only 知识库权限契约（2026-08-18）

**数据与迁移。** `knowledge_base.owner_id BIGINT` 引用 `jchatmind_user.user_id`。本地库已依次执行 [2026-08-18-add-knowledge-base-owner.sql](../../sql/knowledge-base/2026-08-18-add-knowledge-base-owner.sql)、[2026-08-18-migrate-agent-knowledge-base.sql](../../sql/knowledge-base/2026-08-18-migrate-agent-knowledge-base.sql) 和 [2026-08-18-enforce-knowledge-base-owner-not-null.sql](../../sql/knowledge-base/2026-08-18-enforce-knowledge-base-owner-not-null.sql)：owner 外键和检查约束均已校验，`owner_id` 为 `NOT NULL`。`agent_knowledge_base(agent_id, kb_id)` 是 Agent 默认范围唯一持久化表，外键分别对 `agent`、`knowledge_base` 使用 `ON DELETE CASCADE`，记录当前 `bound_by_user_id` 和 `bound_at`；旧 `agent.allowed_kbs` JSONB 列已删除。2026-08-19 另在全新、无业务数据的 Docker PostgreSQL 验收库实际执行五份 G1 迁移：确认旧 JSONB 只迁入同 owner 绑定并去重、`owner_id NOT NULL`、`(owner_id, idempotency_key)` 与 MCP 单主体有效 grant 的负向约束，以及删除 KB 对关系绑定、文档、chunk、摄入任务的数据库级级联。

**授权判定。** `KnowledgeBaseAccessService` 是服务端唯一 owner 判定入口：当前用户 ID 必须与 KB owner 相等。KB 列表、CRUD、文档 CRUD、上传、Markdown 索引及文档删除的 chunk 索引清理均在副作用前校验；知识库更新与删除的最终 DML 还固定带 `id + owner_id` 条件，文档更新则以现有 `kb_id` 关联 KB owner 作为最终 DML 条件并禁止改写该归属，避免预读校验与写入之间的归属变化扩大修改范围。无 owner、缺失或非 owner 均返回统一拒绝，不暴露 KB 是否存在。当前没有 tenant、成员、共享 ACL 或角色模型，因此它们不构成任何隐式放行规则。

**三层范围。**

1. 后端硬权限是当前用户 owner 范围，不能由 Agent、会话或工具参数扩大。
2. `agent_knowledge_base` 是 Agent 默认范围；API `allowedKbs` 只是其读写投影，不是 JSONB，也不是资源授权模型。创建和更新时，每个传入 ID 必须存在、去重且属于当前用户；显式空数组允许保存并表示无私有 KB 检索范围，未传该字段的局部更新保留既有关系绑定。
3. 会话上下文和 `KnowledgeTool` 传入的 `kbIds` 只能从 Agent 已注入的默认范围继续收窄。`JChatMindFactory` 在运行时再次校验 Agent owner，从关系表读取绑定并过滤已删除或失权 KB。

**MCP 边界。** MCP V1 代码只从服务端解析有效主体凭据指纹到单一内部 `user_id`，再走同一 owner-only 判定；不得信任 MCP 参数中的 `kbIds` 或调用方自报的用户 ID。生产库已执行 MCP 迁移并创建主体 `principal_id=1` 到 `user_id=10` 的单一有效 grant；服务端显式使用 `STREAMABLE` `/mcp` 协议，过滤器覆盖该根端点及其子路径。在无有效主体、无有效 grant 或 KB 越权时，私有 KB 检索不进入 RAG。

**本地历史数据决策。** 依据 [2026-05-29-eval-improvements.md](../archive/2026-08-15/records/rag/2026-05-29-eval-improvements.md) 的四份 fixture，保留 `RAG Recall Fixture KB`（`a57df226-122b-481f-992a-935c5cc72a81`）的 4 条文档、32 个 chunk；其 owner 为测试账号 `g0l3_20260817_114535`（`user_id=9`）。账号已存在，未读取、未记录或写入密码、JWT 或其他凭据。此前的 G0 L3 KB、文档和 Agent 已删除，该账号只保留为这套评测 KB 的 owner，不能再将 G0 L3 资源视为可复用 fixture。按 Ban 确认，另 17 个候选 KB 与其 46,979 条文档、49,152 个 chunk、候选文件目录已清理；当前关系表无遗留绑定。

**非目标与后续迁移。** 当前没有 tenant、共享 ACL、角色授权、反向查询、完整绑定历史审计或 KB 删除时文档/chunk/文件的通用应用层物理级联。`agent_knowledge_base` 已提供当前绑定的数据库级级联，不等于共享授权或完整审计。出现共享、角色授权、tenant 或审计需求时，必须先扩展授权数据模型；API `allowedKbs` 投影不能承担授权、审计或级联语义。受控 fixture `Recall@5=1.0` 仅证明 gold chunk Top-5 覆盖和检索链路可回归，不证明真实 RAG 效果、真实权限隔离、引用准确性或答案忠实性。

**MCP 身份映射（代码已实现，生产迁移已执行）。** [2026-08-18-create-mcp-principal-access.sql](../../sql/mcp/2026-08-18-create-mcp-principal-access.sql) 定义四张表：`mcp_principal`（稳定外部主体与启停状态）、`mcp_principal_credential`（主体、凭据指纹/版本、有效期和撤销状态，明文只留在受控密钥系统）、`mcp_principal_user_grant`（V1 每个主体至多一条未撤销的内部 `user_id` grant，含审批者、授予/撤销时间和原因）和 `mcp_access_audit`（追加记录主体、解析出的用户、动作、目标 KB、决定、关联 ID、时间和脱敏请求元数据）。`McpApiKeyFilter` 仅以 `X-API-Key` 的 SHA-256 指纹解析启用主体和有效 grant，并传播主体与关联 ID；`McpKnowledgeTool` 以该 `user_id` 走 owner 校验。认证和知识检索的允许/拒绝均追加审计，审计不存原始凭据或查询正文。当前无 tenant 模型，V1 不伪造 tenant 字段；审计查询/保留，以及未来 tenant/共享 ACL 的独立 grant 迁移仍不在本次范围。

**2026-08-25 受控 KB 删除任务契约（TC-G1-04c，L0/L1）。** `DELETE /api/knowledge-bases/{knowledgeBaseId}` 先由 `KnowledgeBaseDeletionTaskServiceImpl` 以当前 user ID 和 `KNOWLEDGE_BASE_DELETION:<kbId>` advisory lock 查询同 owner 历史任务；存在则直接返回原 `deletionTaskId`，即使 KB 已被删除。否则必须先通过 `KnowledgeBaseAccessService` owner 校验，再在一个事务内写入 `knowledge_base_deletion_task`、追加 `DELETE_REQUESTED` 审计并以 `id + owner_id` 删除 KB；既有外键在该事务中级联清理绑定、文档、chunk 和摄入任务。任务不对 KB 建立外键，保存输入 JSON、结果引用、状态、进度、重试和错误信息，进度固定为 `QUEUED=0`、`RUNNING=50`、`SUCCEEDED=100`，避免数据库级联丢失文件清理状态。提交后才投递独立 `knowledge-base-deletion` Rabbit queue；单消费者只认领 `QUEUED` 或重试任务，调用受限 `DocumentStorageService.deleteKnowledgeBaseDirectory(kbId)`，缺失目录成功，越出基目录拒绝，失败最多三次后 `DEAD_LETTER`。`GET /api/knowledge-base-deletion-tasks/{taskId}` 只向 owner 返回 ID、状态、进度、尝试次数、错误摘要和时间。RED 覆盖任务模型/迁移、请求 API、生命周期、消费者和存储目录边界；GREEN 为 `cd backend_v2 && .\mvnw.cmd -q "-Dtest=KnowledgeBaseDeletionHttpContractTest,KnowledgeBaseFacadeServiceImplTest,KnowledgeBaseDeletionTaskContractTest,KnowledgeBaseDeletionTaskServiceImplTest,KnowledgeBaseDeletionTaskConsumerContractTest,KnowledgeBaseDeletionTaskConsumerTest,DocumentStorageServiceDeletionContractTest,DocumentStorageServiceImplTest" test`。本项未执行迁移，也未启动真实 Rabbit/PostgreSQL；第二隔离账号 L2 和 Edge Playwright L3 仍是后续门禁。

**2026-08-30 受控 KB 删除任务隔离 L2 验收（TC-G1-04c）。** `G1KnowledgeBaseDeletionRuntimeL2Test` 使用随机命名的 PostgreSQL `16` 与 RabbitMQ `3-management-alpine` 临时容器、随机高位端口和临时上传目录；测试在隔离数据库内应用 `2026-08-25-create-knowledge-base-deletion-task.sql`，创建双 owner 及 KB/文档/chunk/摄入任务/物理文件 fixture。真实运行结果为 `3 tests, 0 failures, 0 errors, 0 skipped`：非 owner 删除在建任务前统一拒绝且无任务/文件副作用；owner 请求经真实 Rabbit 消费后删除任务为 `SUCCEEDED`、进度 `100`，审计保留一条，KB/文档/chunk/摄入任务级联为零且物理目录删除；第二 owner 无法读取该删除任务。测试结束已删除临时容器、目录和数据，未读取或修改业务库。生产迁移入口和 Edge Playwright L3 仍未验收。 |

**2026-08-30 删除 UI/L3 旅程准备。** `useKnowledgeBases` 新增删除任务终态轮询，`KnowledgeBaseTabContent` 提供二次确认删除按钮，删除成功后刷新列表并反馈完成状态；`ui/tests/g1-runtime.spec.ts` 新增“创建 KB -> 删除确认 -> 等待完成 -> 列表移除”Edge 旅程，静态契约、TypeScript、ESLint 和 Vite 构建均通过。由于本轮未启动隔离后端和 Vite 服务，该浏览器旅程尚未执行，不计入 L3 通过证据。 |

**2026-08-31 删除 Edge Playwright L3 验收。** 在动态端口隔离 PostgreSQL/RabbitMQ/Redis、后端和 Vite 环境中，`g1-runtime.spec.ts --grep "deletion completion"` 完成注册、登录、创建知识库、二次确认删除、异步任务完成和列表移除，结果为 `1 passed`。临时容器、数据库、后端/Vite 进程和存储目录均已清理，生产迁移入口仍待验收。

| TC-ID | 先写的失败测试 | 已观察的 RED | GREEN 入口与覆盖 |
| --- | --- | --- | --- |
| TC-G1-04a-1 | `McpKnowledgeToolTest.shouldDenyPrivateKnowledgeRetrievalWithoutCallerIdentity` | 当前返回空结果并可进入 RAG，而非统一拒绝。 | 无身份 MCP 直接拒绝，不查询 KB 或 RAG。 |
| TC-G1-04a-2 | `AgentFacadeServiceImplTest` 的创建、更新、去重、关系表读取及 Agent 存在性隐藏用例 | 越权/缺失 KB 未拒绝，重复绑定未去重，Agent JSONB 仍是绑定事实来源，越权 Agent 返回不同文案。 | 创建/更新校验 owner、去重并原子替换关系绑定；不存在与越权统一拒绝。 |
| TC-G1-04a-3 | `KnowledgeBaseFacadeServiceImplTest` 的历史 KB 列表、创建、更新、删除用例 | 全量列出、可更新/删除，创建没有 owner。 | 列表和 CRUD 按 owner；新 KB 服务端写 owner；历史空 owner 拒绝。 |
| TC-G1-04a-4 | `DocumentFacadeServiceImplTest` 的读取、创建、上传、更新、删除索引用例 | 文档入口未校验 KB，删除不清理 chunk。 | 所有文档副作用先按 KB owner 校验；获授权删除先清 chunk。 |
| TC-G1-04a-5 | `JChatMindFactoryOwnershipTest` 的跨用户、失权、删除引用及关系表运行时读取用例 | Factory 可运行他人 Agent 并加载越权 KB，且不会从关系表解析默认范围。 | Agent owner 校验；运行时从关系表读取并过滤失权和删除 KB。 |
| TC-G1-04a-6 | `KnowledgeToolsScopeTest` 的空绑定和多 KB 收窄用例 | 既有收窄逻辑已在 G1 前通过；关系表运行时读取的新增 RED 用例固定在 `JChatMindFactoryOwnershipTest`。 | 空绑定不检索；请求 KB 只能收窄已注入集合。 |
| TC-G1-04a-7 | `AgentKnowledgeBaseBindingServiceTest`、`AgentKnowledgeBasePersistenceContractTest`、`AgentKnowledgeBaseMigrationContractTest` | 缺少替换绑定、`agent_knowledge_base` 迁移、owner 收紧，或仍由 Agent JSONB 持久化绑定时失败。 | 关系表成为唯一持久化来源；迁移移除旧 JSONB，并在清理后收紧 owner 约束。 |
| TC-G1-04a-8 | `McpPrincipalMigrationContractTest`、`McpPrincipalAccessServiceTest`、`McpServerConfigTest`、`McpKnowledgeToolTest`、`McpAccessAuditPersistenceContractTest` | 缺少 MCP 主体/凭据/grant/audit 迁移，Filter 未传播主体或关联 ID，允许/越权检索未写审计，或无效凭据仍进入 FilterChain 时失败。 | L0/L1 覆盖指纹解析和审计契约；2026-08-21 生产库 `STREAMABLE` `/mcp` 协议已实际验证无效凭据 `401`、有效主体初始化、工具发现、owner 范围检索和允许/拒绝审计；原始凭据不写入数据库、文档或仓库。 |

定向 GREEN 命令：`cd backend_v2; .\mvnw.cmd -q "-Dtest=AgentKnowledgeBaseBindingServiceTest,AgentKnowledgeBasePersistenceContractTest,AgentKnowledgeBaseMigrationContractTest,AgentFacadeServiceImplTest,KnowledgeBaseFacadeServiceImplTest,DocumentFacadeServiceImplTest,JChatMindFactoryOwnershipTest,KnowledgeToolsScopeTest,McpPrincipalMigrationContractTest,McpPrincipalAccessPersistenceContractTest,McpPrincipalAccessServiceTest,McpAccessAuditPersistenceContractTest,McpPrincipalAuditServiceContractTest,McpServerConfigTest,McpKnowledgeToolAuthorizationContractTest,McpKnowledgeToolTest" test`，已退出 `0`。测试只使用 mock 和迁移/源码文本契约，不读取本地敏感配置，不访问真实数据库、模型或网络；2026-08-19 的隔离后端另验证 HTTP/JWT、MCP 跨用户运行时和 `TC-G1-05` Edge Playwright。模型驱动的 Agent 聊天工具调用与会话范围收窄仍不是本项证据。

### 3.1.3 G1 任务与异步摄入 L0/L1 契约（2026-08-19）

**数据与状态。** [2026-08-18-create-ingestion-task.sql](../../sql/ingestion/2026-08-18-create-ingestion-task.sql) 定义 `ingestion_task`：`owner_id` 绑定提交者，`(owner_id, idempotency_key)` 唯一，关联 KB/文档，状态受 `QUEUED`、`RUNNING`、`RETRYING`、`FAILED`、`DEAD_LETTER`、`CANCELLED`、`SUCCEEDED` 约束。取消只允许 `QUEUED -> CANCELLED` 或 `RETRYING -> CANCELLED`；`RUNNING` 取消被拒绝，避免已领取 worker 继续写入时产生矛盾状态。生产库任务迁移尚未执行；2026-08-19 已在无业务数据的独立 Docker PostgreSQL 库实际执行，并验证 owner 范围内唯一约束拒绝重复键、不同 owner 可使用同键及删除 KB 的任务级联。该 schema 验收不等同于真实并发事务或应用端到端处理。

**提交、权限与幂等。** `/documents/upload` 要求 `Idempotency-Key`。上传门面先完成 KB owner 校验和非空键校验，后者发生在文档和文件写入前；同一上传事务内，`IngestionTaskServiceImpl` 先以 `(owner_id, idempotency_key)` 的 PostgreSQL advisory lock 串行化预检，再重放既有任务或保存文档、文件并提交任务，响应包含 `documentId`、`taskId`。新任务和手动重试均在提交事务后发布消息。同一 owner 对同一 KB/文档重用同一键返回原任务；用同一键指向其他资源统一拒绝。任务查询、取消和手动重试均先按任务 `owner_id` 校验；不存在和非 owner 返回同一拒绝，不向响应暴露 `ownerId` 或幂等键。

**处理与文件范围。** HTTP 请求不执行解析或 embedding。`IngestionTaskConsumer` 显式绑定 `ingestionRabbitListenerContainerFactory`，该工厂先应用既有 Spring Boot Listener 配置，再固定每个实例两个消费者、每消费者预取一条未确认消息；该限制不改变消息格式、任务状态机、既有重试/死信语义，也不提供执行超时、动态扩缩、队列监控或跨实例协调。RabbitMQ 消费者领取任务后调用默认处理器：先定位同一 KB 的文档、读取受控存储路径，再在数据库事务内清理该文档旧 chunk、构造 metadata、embedding 并写入新 chunk；任一 `insert` 返回非正数即抛业务异常，使本次数据库替换回滚。Markdown 保持已有章节语义；HTML 使用标题结构解析并保留路径 metadata；PDF 使用 PDFBox 逐页提取非空文本，并把 `pageNumber` 写入 metadata；`txt` 或无章节内容作为单个原文 chunk。损坏或无可提取文本的 PDF 返回稳定业务错误，再由既有重试/死信状态机处理。消费者在 `RUNNING`、`SUCCEEDED`、`RETRYING`、`DEAD_LETTER` 发布脱敏进度事件；`/sse/ingestion/{taskId}` 先经任务 owner 校验，SSE 帧带单调 `id`，以任务级锁串行化回放、连接注册和实时发送；每任务最多保存 64 条事件，终态且无连接 30 分钟后在后续任务活动时清理，可对 `Last-Event-ID` 在本进程有限历史中回放。独立 HTTP/JWT 验收已覆盖同任务多连接接收和跨 owner 无资源泄露；多实例或进程重启恢复仍未覆盖。

**前端状态。** 上传请求为每次提交生成请求级幂等键。知识库页面取得 `taskId` 后仅在当前 `knowledgeBaseId` 下轮询受控任务路由，返回值再次核对 `kbId`；对 `QUEUED`、`RUNNING`、`RETRYING` 显示进度，仅对 `QUEUED`、`RETRYING` 显示取消入口，对 `FAILED`、`DEAD_LETTER` 显示图标化重试入口。取消/重试失败显示错误并保留当前任务状态；这是轮询，不是 SSE。轮询失败不改变任务授权或任务状态，下一轮可恢复。

| TC-ID | 先写的失败测试 | 已观察的 RED | GREEN 与边界 |
| --- | --- | --- | --- |
| TC-G1-01 | `IngestionTaskStateMachineTest`、`IngestionTaskLifecycleTest`、`IngestionTaskServiceImplTest`、`IngestionTaskControllerTest` | 状态机、任务服务查询和控制器均缺失或不能拒绝非 owner。 | L0/L1 覆盖状态迁移；隔离 RabbitMQ 实际验证领取、失败、重试和死信，仍未测消息恢复。 |
| TC-G1-02 | `IngestionTaskServiceImplTest`、`IngestionTaskPersistenceContractTest`、`IngestionTaskMigrationContractTest`、`DocumentFacadeServiceImplTest`、`G1AdvisoryLockRuntimeL2Test` | 幂等重放、跨资源冲突、唯一约束、事务锁或 Mapper SQL 缺失。 | L0/L1 固定 owner 范围；隔离 HTTP 验证顺序重放和跨 KB 拒绝，独立 PostgreSQL 高并发再验证锁等待、回滚释放和零持久化残留。 |
| TC-G1-03 | `DefaultIngestionTaskProcessorTest`、`MarkdownParserServiceImplTest`、`DocumentFacadeServiceImplTest`、`G1IngestionSuccessRuntimeL2Test` | 默认处理器缺失；无标题纯文本不生成 chunk；损坏 PDF 未稳定拒绝；上传同步索引或空键先写入；HTML 标题未解析时仅产生原始 fallback chunk。 | HTML 结构化 RED 后最小解析修复；PDF RED 后按页文本/页码 metadata 与损坏输入业务错误转绿；隔离真实 Rabbit/数据库/Ollama 验证两页 PDF 直调得到两个 chunk、非空 embedding 与 `pageNumber=1/2`，Rabbit 摄入另验证 `sourceType=pdf`、1024 维 embedding 和 `SUCCEEDED`。损坏 PDF 首次投递验证 retry queue，测试手工重投到真实消费者后到达死信且无 chunk 或重复物理副作用；不覆盖 retry TTL/DLX 自动回投。 |
| TC-G1-04 | `DocumentFacadeServiceImplTest`、`IngestionTaskServiceImplTest`、`IngestionTaskControllerTest` | 跨 owner 任务读取或空键副作用未被拒绝。 | L0/L1 owner 校验加上两个隔离账号的 HTTP/JWT 读写、上传、任务和 Agent 绑定拒绝；模型聊天工具调用未执行。 |
| TC-G1-05 | `ui/tests/g1-runtime.spec.ts`、`ui/tests/document-upload-idempotency.contract.mjs`、`ui/tests/ingestion-task-progress.contract.mjs`、`IngestionTaskProgressServiceTest`、`G1IngestionTaskSseHttpRuntimeL2Test` | 项目级 Playwright 初始缺失；新建 KB 后详情页使用独立陈旧列表；消费者在完整 Spring 装配时注入空进度服务；任务第二 SSE 连接覆盖第一连接；事件没有稳定序号与回放。 | Edge 实际登录、上传、轮询、当前 KB 隔离、跨账号无泄露、取消/重试失败提示通过；有效 RED 证明预先建立的第二条 Bearer 浏览器 SSE 在真实重试后未收到 `RUNNING`。四参生产构造器修复后，洁净隔离全栈 GREEN 确认该已连接流收到 `RUNNING`。后续 RED/GREEN 固定了事件单调序号、`Last-Event-ID` 单实例回放与浏览器重连；轮询继续作为跨实例与长断线兜底。 |
| TC-G1-06 | `G1RabbitConsumerDatabaseRecoveryRuntimeL2Test` | 真实处理器在数据库失败时可能部分提交、重复写入或错误确认消息。 | 独立 PostgreSQL/RabbitMQ 真实失败回滚，任务依次重试并死信，文档、任务、chunk 和物理文件不重复。 |
| TC-G1-07 | `G1IngestionSuccessRuntimeL2Test` | HTML 标题未解析时成功任务只有单个原始 fallback chunk。 | Markdown/HTML 真实 Rabbit 摄入各生成两个结构化 chunk、非空 1024 维 Ollama embedding，任务为 `SUCCEEDED`；两页 PDF 直调断言两个 chunk、非空 embedding 与 `pageNumber=1/2`，Rabbit 摄入另验证 `sourceType=pdf`、1024 维 embedding 和 `SUCCEEDED`。损坏 PDF 首次投递进入 retry queue，测试手工重投到真实消费者后第二次重试并最终死信，且无 chunk 残留；未验证 retry TTL/DLX 自动回投。 |
| TC-G1-08 | `G1IngestionTaskSseHttpRuntimeL2Test`、`IngestionTaskProgressServiceTest` | 单 emitter 覆盖先建立的同任务 SSE 连接；终态事件可能无限保留。 | 任务级 emitter 集合向两条同 owner 连接广播 `RUNNING`，跨 owner 不泄露资源标识；单实例无连接终态任务超过 30 分钟后会在下一次活动时清理 latest/history/sequence/lock。 |
| TC-G1-09 | 真实 Agent runtime、生产 MCP `STREAMABLE` 协议脚本 | 模型/embedding 或 MCP 主体链路不可用时，工具调用顺序、结构化引用或审计可能缺失。 | 真实 Agent 实际完成 `KnowledgeTool` 调用并持久化四段消息；生产 MCP `initialize`、`tools/list`、`tools/call` 成功，原始凭据不落库，审计追加。 |
| TC-G1-10 | `G1EmbeddingRecoveryRuntimeL2Test` | 首次 embedding 不可达时，任务不得误成功；测试写入目录与真实存储服务解析目录不一致时必须在处理前失败。 | 首次为 `RETRYING(1)` 且 retry queue 有消息；不手工投递，TTL/DLX 自动回投后两个 chunk 写入非空 1024 维 embedding 并为 `SUCCEEDED(1)`。 |
| TC-G1-11 | `IngestionWorkerConcurrencyConfigTest` | `IngestionTaskConsumer` 未绑定命名容器，Worker 并发和预取仅依赖未声明的默认值。 | `ingestionRabbitListenerContainerFactory` 固定两个消费者和 `prefetch=1`，消费者显式绑定；不覆盖任务超时、动态扩缩、监控或跨实例协调。 |

定向 GREEN 命令：`cd backend_v2; .\mvnw.cmd -q "-Dtest=DefaultIngestionTaskProcessorTest,DocumentFacadeServiceImplTest,IngestionTaskServiceImplTest,IngestionTaskControllerTest,IngestionTaskStateMachineTest,IngestionTaskLifecycleTest,RabbitIngestionTaskPublisherTest,IngestionTaskMigrationContractTest,IngestionTaskPersistenceContractTest,IngestionTaskSpringWiringContractTest" test`，已退出 `0`。前端执行 `node ui/tests/document-upload-idempotency.contract.mjs`、`node ui/tests/ingestion-task-progress.contract.mjs`、`npm.cmd run lint`、`npm.cmd run build` 均退出 `0`；lint 只输出 `baseline-browser-mapping` 数据新鲜度提示，build 的 bundle 体积提示不等于 lint 结果。所有这些验证不读取本地敏感配置，不访问真实数据库、模型或网络。

**2026-08-19 运行时 L2/L3 证据。** 在不读取本地敏感配置、不写业务库且禁用外部模型的前提下，独立 PostgreSQL、独立 RabbitMQ vhost 和 `18080` 隔离后端执行五份 G1 迁移。真实 HTTP/JWT 证明 B 不能读取、更新、删除、上传 A 的 KB/文档，也不能绑定 A 的 KB；越权与不存在资源的拒绝相同且不返回资源身份或内容。A 的同资源幂等重放返回相同文档/任务，跨 KB 重用键拒绝；不支持 PDF 经真实消费者进入 `RETRYING`，三次尝试后为 `DEAD_LETTER` 且隔离 DLQ 可见消息。删除 KB 后不存在 orphan 文档、chunk、任务或绑定。两个实际缺陷均经 RED 后最小修复：PostgreSQL advisory lock 查询不能映射为 `void`，Rabbit JSON 字符串 UUID 必须在消费者解包。补充的 `G1FactoryKnowledgeToolRuntimeL2Test` 在独立 PostgreSQL/RabbitMQ、完整 Spring 上下文和真实 `JChatMindFactory` 中，以 `agent_knowledge_base` 关系数据装配运行时，再从 Factory 生成的本地 `KnowledgeTool` 回调获取实际工具实例：A 只注入 A1/A2，B 的 KB 不进入运行时；空绑定与 B 的 `kbIds` 在调用 RAG 前返回统一不可检索结果；会话 A1 默认收窄，A2/B 请求只解析为 A2。该测试先 RED 暴露 `chat_session.user_id BIGINT` 与 Factory 传入字符串参数的 PostgreSQL 比较错误，`ChatSessionMapper.selectByIdAndUserId` 改为显式 `BIGINT` 转换后 GREEN（Surefire `1 test, 0 failures, 0 errors`）。

**2026-08-21 真实模型、生产 MCP 与成功路径证据。** 生产业务库已实际存在 `agent_knowledge_base`、`ingestion_task`、`mcp_principal`、`mcp_principal_credential`、`mcp_principal_user_grant` 和 `mcp_access_audit`，主体 `principal_id=1` 仅有一条至 `user_id=10` 的有效 grant；凭据轮换只写入 SHA-256 指纹，原始值未写入仓库、文档或日志。真实外部聊天模型驱动隔离 Agent 完成 `KnowledgeTool` 调用，消息顺序为 `user -> assistant(tool call) -> tool -> assistant`；真实 HTML 摄入生成两个结构化 chunk 和非空 1024 维 Ollama embedding，最终回答包含 `Codex Runtime Guide > Tool Calling` 路径和精确标记。服务端显式配置 Spring AI `STREAMABLE` 协议，`/mcp` 的 `initialize`、`tools/list` 和 `mcpKnowledgeQuery` `tools/call` 均返回成功；无效或缺失凭据仍由最高优先级 Filter 返回 `401`，认证与知识查询审计追加成功。该证据不扩展为模型驱动会话范围收窄、多实例 SSE、重连恢复或跨组件故障恢复。

**2026-08-21 PDF golden case 与单实例终态清理证据。** `G1IngestionSuccessRuntimeL2Test` 在独立 PostgreSQL、RabbitMQ vhost、上传目录和本机 Ollama `bge-m3:latest` 中，以测试内生成的两页 PDF 先验证直调处理器：两个 chunk、非空 embedding 和 `pageNumber=1/2`。真实 Rabbit 消费另验证两个 `sourceType=pdf`、`pageNumber=1/2`、非空 1024 维 embedding 的 chunk，任务最终为 `SUCCEEDED`。同一隔离链路以损坏 PDF 验证真实 PDFBox 解析失败后首次进入 `RETRYING(1)` 并有 retry queue 消息；测试手工重投到真实消费者后依次观察 `RETRYING(2)` 和 `DEAD_LETTER(3)`，DLQ 有消息，document/task/物理文件始终各为单份且 chunk 为 0。该用例未验证 retry TTL/DLX 自动回投。脱敏运行摘要为 `backend_v2/target/g1-runtime-l2/pdf-ingestion-l2-summary.txt`。`IngestionTaskProgressServiceTest` 固定单实例内存边界：终态、无连接且超过 30 分钟的任务会在下一次活动时清理 latest/history/sequence/lock。PDFBox 首次字体扫描会对本机损坏字体发出警告，不影响通过结论。

**2026-08-19 浏览器 L3 证据。** 新增 `ui/playwright.config.ts` 与 `ui/tests/g1-runtime.spec.ts`，使用本机 `msedge` channel，不下载 Chromium。Edge 对隔离 UI/后端执行登录、KB 创建、PDF 上传、轮询至 `DEAD_LETTER`、当前 KB 切换、跨账号直达拒绝无泄露以及取消/重试双击冲突的实际错误提示；截图和 HTML 报告只写入 `backend_v2/target/g1-playwright/`。该旅程先因缺少项目 Playwright 配置及“新建 KB 后详情页使用陈旧列表”失败，后者通过详情路由变更时刷新 KB 列表修复后转绿。它不是 Chromium 冒烟、静态契约、lint 或 build 的替代描述。

**2026-08-20 成功摄入与进度发布证据。** 独立 PostgreSQL、独立 RabbitMQ、独立上传目录和本机 Ollama 中，`G1IngestionSuccessRuntimeL2Test` 先 RED 暴露 HTML 标题未解析：任务成功却只生成一个原始 HTML chunk，结构化断言预期两个。最小 GREEN 仅新增 HTML 标题解析入口；随后同一测试类的 Markdown 直调、Markdown Rabbit、HTML Rabbit 三项均为 `0 failures, 0 errors`。两种格式都产生两个带 section metadata 的 chunk、非空 1024 维 embedding 和持久化物理文件；真实消费者完成后，`IngestionTaskProgressServiceImpl` 的最终事件为 `SUCCEEDED`。脱敏摘要位于 `backend_v2/target/g1-runtime-l2/ingestion-success-summary.txt`，对应隔离容器和目录已删除。

**2026-08-20 任务 SSE HTTP 证据。** `G1IngestionTaskSseHttpRuntimeL2Test` 使用独立 PostgreSQL、嵌入式 HTTP、项目 `TokenInterceptor`、真实 JWT 和两个同 owner 客户端。它先 RED：两条连接均收到 `QUEUED`，发布 `RUNNING` 后首条连接五秒超时，根因是进度服务每任务仅存一个 emitter，第二条连接覆盖第一条。最小 GREEN 改为每任务并发 emitter 集合并按连接完成、超时、错误和发送失败精确注销；两项 GREEN 为 `0 failures, 0 errors`，两条授权连接均收到 `RUNNING`，另一 owner 的响应不含任务、KB 或文档标识。脱敏摘要位于 `backend_v2/target/g1-runtime-l2/sse-http-summary.txt`，隔离容器和原始报告已删除。

**2026-08-20 浏览器任务 SSE 证据。** Edge 在真实隔离后端、独立 PostgreSQL/RabbitMQ 与真实 Bearer JWT 上执行 `node .\node_modules\@playwright\test\cli.js test tests/g1-runtime.spec.ts --project=edge --grep "retry progress published"`。RED 先将第二条 SSE 建立在 `DEAD_LETTER` 后，再通过页面真实重试触发 Rabbit 消费；15 秒后该流仍仅为 `["DEAD_LETTER"]`，而轮询 UI 已为 `RETRYING`，表明 Spring 误选三参消费者构造器并使用空进度服务。最小修复将 `@Autowired` 放到四参生产构造器，保留三参构造器给既有单测；`subscribeIngestionTaskProgress` 以 Bearer `fetch + ReadableStream` 解析 `ingestion-progress`，KB 切换、组件卸载和任务终态均由 `AbortController` 清理。洁净重建时临时 schema 缺少既有 UUID 主键默认值而阻止 KB 创建；仅在该临时库补齐默认值后重跑同一用例，实际退出码 `0`，预先建立的第二条流收到后续 `RUNNING`，浏览器连接后事件 GREEN 签收。脱敏结论为 `backend_v2/target/g1-runtime-l2/ui-sse-browser-summary.txt`。

**仍未覆盖。** 图片/OCR 与表格解析，以及 SSE 多实例分发和持久化恢复仍待独立验收。进度服务当前为单实例内存状态：前端已支持事件序号和 `Last-Event-ID` 重连，服务端只回放当前进程的有限历史。`TC-G1-06` 覆盖真实 Rabbit 消费入口在受控 PostgreSQL 数据库失败时的处理器事务回滚、重试和死信；`TC-G1-10` 覆盖外部 embedding 短暂不可用后的 TTL/DLX 自动恢复，但不外推为其他外部依赖故障。生产业务库迁移、主体创建和 MCP 协议调用已完成，不应再写作“未执行”。文件系统不参与数据库事务，KB 删除的受控应用层文件异步清理仍是后续任务。

### 3.1.4 G2 原生 BM25、改写校准与多模态证据契约（局部实现，其余待实施）

**范围与非范围。** G2 只把现有标题/正文 BM25 从 JVM 全量候选计算迁为 PostgreSQL 原生倒排索引，并校准查询改写、RRF、Router 接线和证据资产模型；不重写已存在的 pgvector 向量检索、owner-only 授权模型或标题精确/包含/关键词/Trigram 行为。RAG-Anything 仅作为多模态摄入和证据关系的设计参考，不引入其 LightRAG 图谱、Python 运行时或通用多 Agent 架构；用户长期记忆也不属于本阶段。

**唯一 provider 原则。** 首个隔离 PoC 优先验证 ParadeDB `pg_search`，同时以 VectorChord-bm25 作为备选进行同口径比较。落地前必须记录目标 PostgreSQL 版本、扩展版本、pgvector 镜像兼容性、许可证、建索引/重建时长、删除传播、备份恢复和 `EXPLAIN` 证据；通过后只保留一个生产 provider。不得把两个插件同时部署到生产，不得采用长期双读/双写，也不得把 JVM BM25 与数据库 BM25 的结果混合为“兼容层”。

**数据与 Mapper 契约。** `chunk_bge_m3` 是检索事实的唯一来源。若 provider 需要搜索字段投影或索引表，投影由同一摄入事务随 chunk 写入/删除，带 `chunkId` 与 `indexVersion`，禁止独立业务写入口。BM25 Mapper 入参必须是已通过授权收窄的 `kbIds`、原问或受控 standalone query、`HARD` 上下文字段与候选上限；`kb_id`、`sourceName`、`sourceType`、规范化 `contentPath` 过滤都在数据库 `LIMIT` 前完成。返回最少包含 `chunkId`、通道名、通道内 rank、可选 lexical score 和展示必需字段。应用只能将 rank/provenance 送入 RRF/rerank，禁止直接把 BM25 score 与 pgvector distance 相加。

**改写与融合契约。** 原问为不可删除的主 query；只有低信息 follow-up 且存在已授权会话证据时，才能产生最多一个 standalone 补全 query。原问和补全 query 都需要记录来源、触发原因和超时/失败回退；改写失败、超时、空输出或范围不一致时只能回退原问。标题精确通道继续只使用原问，避免标题稳定性被改写污染；正文 BM25 是否消费补全 query 必须由冻结评测证明收益。RRF 使用通道内 rank 而不是异构原始分数，并在每个最终候选保留 `channel/query provenance`，以便诊断某个改写或通道的影响。

**范围、候选与会话契约。** 必须在实施前冻结“未传 `kbIds`”的产品语义：推荐默认搜索该 Agent 的全部已授权 KB，会话 context 仅作为排序偏置；若需要 sticky scope，必须由显式会话或用户选择收窄，不能隐式采用上次 Top-1 的 KB。`FOLLOW_UP` 至少需要代词/续问标记与已授权上下文，短新标题、代码标识符和 API 路径仍保留标题通道；只有受控层级路径可进入导航 auto-context。RRF 后必须在 rerank 前按明确预算截断候选，rank penalty 必须有界或移除，避免深层精确命中数学上不可能升序。保存 `retrievalContext` 前必须满足相关性阈值及 Top-1/Top-2 gap；无答案、拒答和低置信结果不得更新会话 context。

本轮已落地该契约中的排序局部：RRF 和 `HARD` 过滤完成后最多对前 50 个候选执行 rerank，预算外候选保持原 RRF 顺序；rank penalty 以 `0.15` 为上限。2026-08-24 提交 `1e96e44` 进一步在跨组 RRF 前对 `vector_*` 与 `title_*` 做同源组内去重/校准，同一 chunk 在组内只保留最佳 rank 的一次贡献，并在 `RagRetrievalResult.retrievalProvenance` 保留 `vector_original`、`vector_standalone`、`title_exact` 等通道/query 来源；`RagServiceImplTest` 8/8 通过且独立审查无 P0/P1/P2。会话 context 置信门禁、默认 KB 范围、Router 入口计划执行和 PDF 页资产引用均已在后续子项落实；冻结集消融、真实运行时资产召回和其余 G2 子项仍待后续 RED/GREEN 验收。

2026-08-24 已补齐 VectorChord-bm25 的生产调用链 L2 验收：`VchordBm25QueryServiceL2Test` 在固定 digest 的隔离 PostgreSQL 中同时执行 `RagServiceImpl -> VchordBm25QueryService -> Mapper` 的标题和正文检索。`HARD` 的 `kb_id`、文件名、类型与路径过滤在数据库 `LIMIT` 前生效，范围外高分 chunk 不会挤掉范围内 gold；结果保留 `title_bm25`/`content_bm25` provenance，且每次原生查询的 `SET LOCAL search_path` 与实际 BM25 SQL 使用同一事务连接。该验收不替代冻结集 Recall/p95、备份恢复，或 owner/Agent/会话范围的真实 PostgreSQL 组合测试，`TC-G2-02/03` 因而仅为部分通过。

2026-08-24 提交 `4a57d34` 已落实默认 KB 范围的入口边界：未传 `kbIds` 时检索 Agent 的全部授权 KB，历史命中只复制为本次调用的 `sessionContextBias`，改写器固定使用 `SOFT`，不能借旧 `kbId`、来源或路径触发 `HARD` 过滤；只有调用方显式 `kbIds` 才收窄。撤销的历史 KB context 会清除。`FOLLOW_UP` 在 soft-bias 下仍生成最多一个受控 standalone query 并始终保留原问；短新标题和与旧路径仅部分重叠的标题走 `FACTOID` 与标题通道。`QueryRewriteServiceImplTest` 和 `KnowledgeToolsScopeTest` 共 31/31 通过，独立复审无 P0/P1/P2。任意 slash 导航误判、Router 冻结集消融、引用资产和其余 G2 项仍待后续 RED/GREEN 验收。

2026-08-24 已补齐 Router 的入口计划执行：`KnowledgeTools` 与 `McpKnowledgeTool` 不再各自固定检索条数，而是把已授权范围内的 `RagRouteDecision.topK()` 传给 `RagService`。先 RED 证明 Agent 多模态计划的 `5` 被固定 `3` 覆盖、MCP 私有计划的 `3` 被固定 `5` 覆盖；GREEN 后，MCP 多模态测试进一步断言 `topK=5` 与 `ALLOW/retrieved` 审计，防止回退为固定值。`RagRouterTest`、`KnowledgeToolsScopeTest`、`McpKnowledgeToolTest`、`RagServiceImplTest` 和 `QueryRewriteServiceImplTest` 共 52/52 通过；独立审查和 P2 修复复核均无 P0/P1/P2。该契约只签收本地检索入口的路线执行，不宣称 Router 消融收益、受控外部工具、OCR/图片/表格/公式或资产回跳引用已完成。

2026-08-24 已补齐 MCP 无证据拒答的审计语义：`McpKnowledgeTool` 仅在检索到非空证据时写入 `ALLOW/retrieved`；空结果返回证据不足，并写入 `ABSTAIN/no_evidence`，防止把拒答统计为已检索成功。该变更由先 RED 的 `McpKnowledgeToolTest.shouldAuditNoEvidenceAsAbstain` 固定，随后 `RagRouterTest`、`KnowledgeToolsScopeTest` 和 `McpKnowledgeToolTest` 共 28/28 通过。这里的“无证据”只指空检索结果；数值质量阈值须由冻结集校准后再启用，受控外部工具、消融收益、p95 与 token 成本仍未验收。

2026-08-24 已收窄导航判定：`>` 才作为章节导航自动选择 context，`.md`/`.markdown` 文档定位保持既有导航；`/`、`\\` 只代表结构化 API/代码路径，不触发标题路径候选扫描。审查发现的 P1 进一步固定了 active context 语义：`>`、`/`、`\\` 都不能进入低信息 `FOLLOW_UP/HARD`，也不能触发 LLM 改写。`/api/v1/agents` 与 `src\\main\\java\\Agent.java` 均以 `FACTOID/SOFT` 和仅原问执行；测试以 LLM spy 断言累计零调用，并保留 Markdown 导航与 API 零标题路径扫描断言。`QueryRewriteServiceImplTest` 21/21、`RagServiceImplTest` 8/8、`KnowledgeToolsScopeTest` 15/15，共 44/44 通过，修复复审无 P0/P1/P2。合法导航的候选读取上限、冻结集收益与真实 PostgreSQL p95 仍待独立验收。

**Router 与证据资产契约。** Router 只能在 `KnowledgeTools`/MCP 已收窄的可访问 KB 范围内输出计划，不能自行访问未授权 KB 或外部工具。`ABSTAIN`、`CLARIFY`、`EXTERNAL_TOOL` 需在真实入口生效，而非仅由 Router 单测断言。非文本能力增加前，先定义资产的 `assetType`、文档 ID、页码/坐标、关联 chunk、内容哈希、解析版本和状态；回答引用须可回跳资产及关联文本。当前已实现 PDF 页文本和 Markdown `TABLE` 候选，不等价于图片/OCR、公式、单元格语义检索或坐标回跳已实现。

**G2-4a 资产持久化契约。** [2026-08-22-create-document-asset.sql](../../sql/ingestion/2026-08-22-create-document-asset.sql) 定义 `document_asset` 与 `document_asset_chunk`：资产以 `(document_id, asset_type, asset_key)` 作为稳定定位键，保存可选 `page_number`、JSON `locator`、小写 SHA-256 `content_hash`、`parser_version` 与 `PENDING`/`READY`/`FAILED` 状态；关系表分别携带 `asset_document_id` 和 `chunk_document_id`，通过相等检查约束两者属于同一文档，再以资产 `(asset_id, document_id)` 与 chunk `(id, doc_id)` 的复合级联外键关联。该模型由数据库引用完整性阻止跨文档关联，也阻止已关联的资产或 chunk 换文档，不依赖易产生并发检查-写入竞态的触发器。初始合法资产类型为 `PDF_PAGE_TEXT`、`IMAGE`、`TABLE` 和 `FORMULA`；PDF 页文本和 Markdown `TABLE` 已各自完成 L0/L1 契约，OCR、图片和公式仍未启用。后续每种资产类型必须先独立 RED，再补摄入、幂等替换、权限、召回和定位引用 GREEN。

**G2-4b PDF 页文本资产摄入与引用契约。** 默认摄入处理器对 `filetype=pdf` 执行资产替换：在已有 `@Transactional` 边界内删除当前文档旧资产，在每个新 chunk 成功插入且 ID 回填后写入一条 `PDF_PAGE_TEXT` 资产。资产 `asset_key=page-{pageNumber}`、`page_number=pageNumber`、`locator={\"pageNumber\":pageNumber}`、`content_hash=SHA-256(UTF-8 正文的小写十六进制)`、`parser_version=pdf-text-v1`、`status=READY`；关系行必须带相同的资产/Chunk 文档 ID。资产或关系写入返回非正数即抛业务异常，触发整次 chunk/asset 替换回滚。PDF chunk metadata 同时必须写入嵌套 `asset.id`、`asset.type=PDF_PAGE_TEXT` 和 `asset.locator.pageNumber`，其中 ID 必须等于关联关系表的 `asset_id`；`KnowledgeTools` 与 `McpKnowledgeTool` 只在该 metadata 的 ID、类型均非空时追加 `资产: <type>:<id>`，并保留既有页码引用。HTML 和 TXT 摄入不得删除、创建或关联资产；Markdown 的 `TABLE` 例外由 G2-4d 定义。`DefaultIngestionTaskProcessorTest` 覆盖 PDF 字段、关系、metadata ID 一致性、非 PDF 零交互及 Mapper 失败；`KnowledgeToolsScopeTest`、`McpKnowledgeToolTest` 覆盖两个生产检索入口的稳定资产引用。受 `g2.pdf.asset.transaction.l2=true` 显式启用的 `G2PdfAssetTransactionRuntimeL2Test` 在仅允许本机隔离 `g2pdfassettx` 库的配置中，用 PostgreSQL trigger 验证资产写入失败后旧 chunk、资产和关系均恢复且无新行残留。共用的 G1 摄入成功 L2 数据源只接受随机端口加 nonce 的回环隔离库 URL，在任何清理 DDL 前拒绝业务库。它不覆盖 OCR、图片、表格单元格语义、公式或图片/表格坐标回跳，`TC-G2-06` 保持部分通过。

**G2-4c PDF 页文本资产候选契约。** `RagService.retrievePdfPageAssets(kbIds, query, context, limit)` 仅接受已由 Agent/MCP 收窄的 KB 集合，复用 query rewrite、embedding 缓存与 RRF；其 Mapper 必须通过 `chunk_bge_m3 -> document_asset_chunk -> document_asset` 关联，只返回 `asset_type=PDF_PAGE_TEXT`、`status=READY` 且 embedding 非空的 chunk。`kb_id`、`sourceName`、`sourceType` 与规范化 `contentPath` 的 `HARD` 条件均在向量排序与 `LIMIT` 前下推，不得以资产候选绕过会话范围；动态范围标签必须位于 CDATA 外，由 MyBatis 解析。每个 query source 的 provenance 为 `asset_pdf_page_text_<source>`。仅在 `MULTIMODAL_RAG` 时，`KnowledgeTools` 与 `McpKnowledgeTool` 查询资产候选，再按 chunk ID 去重并以资产结果优先、普通检索回退、`route.topK()` 截断的顺序合并；资产查询抛出运行时异常时按空资产集合继续普通检索，普通私有检索、授权、拒答和审计语义不变。该项仅证明 L0/L1 代码路径和 SQL 契约，不执行真实数据库、多模态 golden case、冻结集、p95/成本或任何 L2/L3 RAG 评测；图片/OCR、公式和坐标回跳继续未实现，`TC-G2-06` 仍是部分通过。

**G2-4d Markdown `TABLE` 资产摄入与候选契约。** 默认摄入处理器对 Markdown 文档执行旧资产替换。Flexmark `TableBlock` 保留原始表格 Markdown 与稳定 `startLine`/`endLine`；每张合法表格在同一事务中创建 `TABLE`/`table-{ordinal}`/`READY`/`markdown-table-v1` 资产，保存行号 locator 和原始表格的 UTF-8 小写 SHA-256，并关联到包含该原始表格的同文档 chunk。表格资产和 chunk 使用同一个处理批次时间戳。chunk metadata 保留第一个关联表格以兼容普通检索；`similaritySearchMarkdownTableAssets` 必须通过资产关系只查询 `TABLE + READY`，并以 `jsonb_set` 用当前候选的 `asset.id`、`asset.type` 与 `asset.locator` 覆盖输出 metadata，避免多表格关联同一 chunk 时引用到摄入 metadata 的首个资产。`RagService.retrieveMarkdownTableAssets(kbIds, query, context, limit)` 复用改写、embedding 缓存、RRF、授权 KB 与 `HARD` 范围，其 provenance 为 `asset_table_<source>`。`MULTIMODAL_RAG` 的 Agent/MCP 入口按表格、PDF 页文本、普通检索顺序合并并按 chunk ID 去重；表格查询运行时失败只清空表格候选，普通私有检索、授权、拒答和 MCP 审计保持不变。`MarkdownParserServiceImplTest`、`DefaultIngestionTaskProcessorTest`、`RagServiceImplTest`、`KnowledgeToolsScopeTest`、`McpKnowledgeToolTest` 与 `ChunkBgeM3MapperMarkdownTableAssetCandidateContractTest` 均先 RED 后 GREEN。该项不实现表格单元格级 embedding/关系、图片/OCR、公式、坐标回跳、真实数据库多模态召回或任何冻结集/基准评测。

**G2-3b 独立三路召回契约（已实施，默认切换被拒绝）。** 普通文本 RAG 已实现 `dense-original`、`sparse-original`、`expanded-query` 三个分支。第一支路只对原问作向量检索；第二支路只对原问作标题词法、标题 BM25 和正文 BM25 的分支内 rank 融合；第三支路只处理 `retrievalQuerySources != original` 的 standalone/LLM query，并在每个扩展 query 内、再在整个第三支路内按 chunk 去重。当前最多一个受控扩展 query，不能借本改造新增不受评测约束的高扇出 query 生成器。

分支输出必须是 chunk 唯一、rank 连续、带 branch/channel/query-source provenance 的候选 list。外层 RRF 只接收三份分支 list，同一 chunk 在同一分支只能贡献一次，在不同分支最多三次；第三支路为空时不得重新执行原问。所有叶子查询必须在数据库 `LIMIT` 前执行已授权 KB、`HARD` 来源/类型/路径过滤。普通文本三路与 `MULTIMODAL_RAG` 的表格/PDF 资产候选继续分开：资产优先级和 chunk 去重沿用已有入口契约，不计入三路 outer RRF。三路共享总候选与前 50 条 rerank 预算，禁止用每支路独立 50 条 rerank 造成总预算膨胀。

L0 RED/GREEN 已由 `RagServiceImplTest.shouldKeepOriginalQueryOutOfExpandedBranch`、`RagServiceImplTest.shouldCountSameChunkAtMostOncePerIndependentBranch`、`RagServiceImplTest.shouldApplyHardScopeBeforeLimitForEveryIndependentBranch` 和 `RagIndependentBranchEvaluatorTest.shouldRejectNonComparableVariants` 固定。最小 GREEN 只补分支编排、provenance、可比性校验和报告，没有改变授权入口、数据库 schema、VectorChord provider 或默认 rerank 开关。定向回归入口为 `mvn.cmd "-Dtest=RagServiceImplTest,QueryRewriteServiceImplTest,RagIndependentBranchEvaluatorTest" test`；真实数据库 L2 与冻结 replay 由 `TC-G2-10` 显式启用，不能以 mock 单测替代。2026-08-28 的首次真实运行显示 R2 指标低于 R0 且三臂均有 2 个拒答违规；2026-08-30 复用同一隔离库和输入、先执行生产 Router 后重跑，拒答/权限违规均为 `0`，Recall@5 保持 `1.0`，但 R2 的 MRR/nDCG 仍低于 R0，因此默认切换门禁仍失败，R0 保持默认。

**发布边界。** 当前仓库没有自动 schema migrator；该 SQL 是版本化、一次性发布工件，提交不代表任何生产业务库已升级。发布前必须确认 `document_asset`、`document_asset_chunk`、所有命名约束与两个索引均不存在；随后在维护窗口以脚本原有事务一次执行，并通过 PostgreSQL catalog 核验表、检查约束、外键和索引，再将迁移文件名、提交 SHA、执行时间和 catalog 核验结果登记到发布记录。若 preflight 发现任一对象已存在或部分漂移，必须停止并人工比对修复，禁止以 `IF NOT EXISTS` 或重复执行掩盖状态。

| TC-ID | 先写的失败测试 | GREEN 与边界 |
| --- | --- | --- |
| TC-G2-02 | 隔离 PostgreSQL 中，原生 BM25 对标题/正文精确术语不能返回正确 chunk，或 Provider 未按 `kb_id`/上下文过滤。 | 真实扩展索引返回预期 Top-N；`EXPLAIN` 和大于 fixture 的冻结语料证明不调用 `selectLexicalCandidatesByKbIds` 全量扫描 JVM；仅比较候选排名，不将插件 score 和 vector distance 相加。 |
| TC-G2-03 | `HARD` context 外的高分 lexical chunk 挤掉 context 内 gold，或不同 owner/Agent/会话范围出现结果。 | 所有 BM25 过滤在 `LIMIT` 前；范围内 gold 可进入候选，范围外内容和元数据均不返回。 |
| TC-G2-04 | 原问被改写替换、改写失败改变召回、多个改写通道等权放大，或标题 exact 因改写退化。 | 原问始终保留，最多一个受控补全 query；输出保留 provenance；改写失败稳定回退；标题通道不使用改写 query。 |
| TC-G2-05 | `RagRouterTest` 通过但真实工具入口仍固定检索，或 Router 触发未授权外部访问。 | 已由 `KnowledgeTools` 与 MCP 的 L0/L1 测试验证 Router 计划、拒答、外部许可和范围收窄；固定链路消融、质量/p95/token 成本报告仍待后续冻结集验收。 |
| TC-G2-06 | PDF 页文本或 Markdown `TABLE` 资产候选绕过 KB 或 `HARD` 会话范围，或图片、表格、公式只能产生无位置的文本。 | PDF 页文本和 Markdown `TABLE` 已分别通过 `READY` 资产关系、向量候选、范围谓词、入口优先合并与稳定资产引用的 L0/L1 契约；表格当前定位为 Markdown 行号，不提供单元格关系或坐标回跳。图片/OCR、公式和未启用资产类型仍待独立验收。 |
| TC-G2-07 | 默认多 KB 搜索被上次 context 隐式收窄，短新标题/代码标识符被误判 follow-up，或 `/api/...` 被误判导航。 | 默认范围符合冻结产品语义；仅显式 scope 可缩窄；标题通道和导航上下文只在对应信号满足时启用。 |
| TC-G2-08 | RRF 第 35 名的精确候选因线性 penalty 无法升序，重复通道被重复投票，或低相关 Top-1 污染下一轮。 | rerank 截断/有界 penalty 可验证；同源通道组内校准；低置信和无答案不更新 retrieval context。 |
| TC-G2-09 | 原问进入 expanded-query 分支、同一 chunk 在同一支路重复投票、`HARD` 谓词在任一叶子查询的 `LIMIT` 后过滤，或普通三路误吸收资产候选。 | 三个分支均输出唯一且连续的 rank；outer RRF 最多接收三个分支贡献；原问不进入第三路；授权/HARD 约束在每条叶子查询前生效；普通文本与资产候选边界保持不变。 |
| TC-G2-10 | R0/R1/R2 使用不同 gold、scope、query replay、候选预算或有效分母仍生成可比较结论，或报告缺少支路诊断、延迟和输入哈希。 | 评测器拒绝不可比运行；同一冻结输入下输出结构消融报告、每支路候选/去重/gold 命中、外层 rank 与 p50/p95；R2 只有在授权/拒答全绿且指标、延迟门槛满足时才能进入 rerank A/B/C。 |

**G2 阶段门禁。** 在冻结集的标题、正文精确匹配、中文/代码术语、multi-turn follow-up、topic switch、无答案、越权及 PDF 页码 case 上，现有主要召回指标不得退化；新增正文 BM25/standalone query/Router/独立三路必须报告分组收益、p95、token 成本、数据和索引版本。只有当所有授权/拒答测试通过且收益可复现时，才能切换唯一 provider 或默认检索结构；否则保留现有已签收链路并记录失败原因，不把 PoC 标记为上线。

**G2 Bad Case 数据契约。** Bad Case 按 `inbox -> reviewed -> development-regression -> next-release-test` 演进。`inbox` 不具备 gold，不进入门禁；`reviewed` 必须固定 query、会话上下文、授权 KB 范围、预期 route、gold facts/chunks、拒答标记、预期引用、失败阶段/类型和输入 SHA-256；修复时先以相同输入复现 RED，再验证 GREEN。任何用于修复或调参的 case 都不能回填当前 untouched test 并宣称同版本收益，只能进入下一冻结版本。真实用户内容必须脱敏，原始对话、凭据和私有文档不得进入 Git。

首版 `rag-badcase-v1` 至少包含跨 KB topic switch、短 follow-up、API/Windows 路径、精确术语、旧版本/重复 chunk、全局高分越权干扰、无答案/冲突/部分证据、错误引用、外部依赖回退和文档内 prompt injection。三路结构消融的两个拒答违规已由 `RagBadCaseManifestTest` 校验并冻结为 `backend_v2/src/test/resources/rag-eval/badcase/rag-badcase-v1.json` 的 reviewed case；Router 修复与同一隔离库三路复跑后，两个 case 均为 `fixed`，拒答/权限违规为 `0`，但 R2 的 MRR/nDCG 仍低于 R0，整体报告保持 `inconclusive`。Bad Case 分别绑定 `TC-G2-04/05/06/07/08`，不单独制造一套指标；G2 退出时 reviewed P0/P1 必须全部 GREEN，且既有正向冻结集不得退化。

同一复跑还将 `g2-pre-bm25-v1-002` 的实际 gold rank `R0=1/R1=2/R2=2` 冻结为 P1 `retrieval_rank_regression`，将 `g2-pre-bm25-v1-009` 的 PDF 第 2 页 gold rank `R0=1/R1=2/R2=2` 与预期 `chunkId/pageNumber` 冻结为 P1 `citation_rank_regression`。两条均为 `development-regression`，尚未修复；它们解释 R2 的 MRR/nDCG 退化，不能因拒答 case fixed 而提前放行 R2。

### 3.2 G3 候选记忆确认与忽略契约

本子项将既有候选记忆从“自动持久化的记录”收敛为用户可治理的状态机：`PENDING -> PERSISTED` 或 `PENDING -> DISCARDED`。提取器只写入 `PENDING` 候选，候选在确认前不得写入 `user_memory` 或参与记忆召回；不满足已有保留规则的候选可直接标记 `DISCARDED`。现有 `PERSISTED` 历史行保持已确认语义，不做回填或重写。

`POST /api/users/memory-candidates/{candidateId}/confirm` 不接收请求体，身份由既有 `RequestScopeData` 获取。服务必须按当前用户读取候选，缺失或越权统一拒绝；只有 `PENDING` 能确认，已结束状态必须拒绝且不得重复写入记忆。确认在一个事务中使用候选保存的类型、内容、来源会话、证据和重要度创建或复用同内容长期记忆，再将候选置为 `PERSISTED`。若当前用户已有同内容记忆，确认只收束候选状态，不能重复插入；`更新：` 前缀仍选择同一用户、同一类型的当前首条记忆，先插入新行，再将旧行的 `superseded_by_memory_id` 指向新行。所有当前读取、精确去重和向量召回均排除该字段非空的历史行；替代标记失败时整个确认事务回滚。自引用关系以 `ON DELETE CASCADE` 保持单条删除当前记忆后旧冲突内容不会重新可见。embedding 失败沿用既有降级为 `null` 的行为，不能阻断用户确认。`POST /api/users/memory-candidates/{candidateId}/discard` 同样不接收请求体，只允许当前用户将仍为 `PENDING` 的候选条件转换为 `DISCARDED`；它不写入 `user_memory`，也不物理删除候选。

候选列表只返回当前用户的 `PENDING` 行，避免 `PERSISTED`/`DISCARDED` 历史行被误展示为待确认。前端确认忽略操作后刷新同一列表。候选确认/忽略子项本身不改变编辑、过期、摘要节流或 Playwright 语义；这些能力各自按后续 G3 契约实现。

| TC-ID | 先写的失败测试 | GREEN 与边界 |
| --- | --- | --- |
| TC-G3-02a | 候选提取后自动插入长期记忆或把候选直接置为 `PERSISTED`。 | 中高重要度提取只留下 `PENDING` 候选，确认前不插入 `user_memory`。 |
| TC-G3-02b | `POST /api/users/memory-candidates/{id}/confirm` 返回 404，或确认时可越权、重复插入。 | Controller 通过当前用户委派确认；服务只确认同 owner 的 `PENDING` 候选，在同一事务中持久化/复用记忆并将候选置为 `PERSISTED`。 |
| TC-G3-02c | `POST /api/users/memory-candidates/{id}/discard` 缺失，或可忽略越权、终态候选，或误写入长期记忆。 | Controller 通过当前用户委派忽略；服务只将同 owner 的 `PENDING` 候选原子转换为 `DISCARDED`，不写入 `user_memory`；前端在确认后调用该入口并刷新候选列表。 |
| TC-G3-02d | `DELETE /api/users/memories` 缺失，或清空越权记录、删除候选或把空列表当作失败。 | 服务只以当前用户 ID 删除 `user_memory`；候选表不受影响，空集合成功。前端仅在有长期记忆时显示清空操作，二次确认成功后刷新列表。 |
| TC-G3-02e | `PATCH /api/users/memories/{id}` 缺失，或可修改越权记忆、接受空内容、保留旧 embedding 或改变候选/来源字段。 | 仅当前用户可修改非空 `content`；更新条件包含 `id + user_id`，新正文重新生成 embedding，失败降级为 `null`，候选及来源/类型字段保持不变。前端用受控弹窗保存并刷新。 |
| TC-G3-02f | 多次压缩无界追加 `conversationSummary`，或将完整旧摘要重新送入下一轮摘要模型。 | 当既有 8000 字符压缩阈值触发时，`conversationSummary` 至多保留 4000 字符并保留末尾最新内容；决策与摘要提示均只读取该受限值，工具调用对保护与最近 8 条消息保留语义不变。 |
| TC-G3-02g | 每个聊天事件重复调用记忆提取，或同会话并发事件重复调用模型；以最近 8 条窗口计数又使长会话停止提取；删除历史后总数回退使提取长时间停滞。 | 先按 owner 计算会话全部 `role='user'` 消息数；首次立即提取，其后每累计 3 条新用户消息才进入 LLM/关键词提取。总数低于上次成功提取的计数时立即重提取并重新建立阈值；相同 session 在单实例内串行，读取/持久化异常不推进计数；仅在节流通过后才读取最近 8 条消息。 |
| TC-G3-02h | 用户确认与已有记忆语义等价的候选时重复写入长期记忆，或不同类型/不相似候选被错误抑制，或 embedding 依赖故障阻断确认。 | 确认普通候选时仅比较同一用户、同一记忆类型、同维且分量有限的非零向量；余弦距离 `<= 0.05` 时候选可完成但不新插入长期记忆。不同类型、超阈值、无有效向量或向量/读取失败继续写入；候选正文 embedding 只生成一次并复用于判断和写入。 |
| TC-G3-02i | 删除成功后该会话的节流状态仍驻留，或删除前已提交的事件在会话计数失败后残留状态并抑制下一次首次提取。 | 删除与同会话 Agent/提取复用协调器；只有主表删除成功才发布会话删除事件并移除本进程状态。消息计数读取失败时条件移除当前状态后原样抛错；不引入 TTL、跨实例或持久化状态。 |
| TC-G3-02j | 确认 `更新：` 候选时物理删除旧记忆，或新旧关系未持久化而使历史无法审计。 | 新记忆先写入并取得 ID；旧同类型当前记忆仅在尚未被替代时标记 `superseded_by_memory_id`，所有当前读取路径排除已替代行，关系失败回滚确认事务。 |
| TC-G3-02k | 到期记忆仍进入 Agent 上下文、精确去重或向量召回，过期字段无法持久化，或新确认、冲突更新、正文编辑未采用统一 365 天期限。 | `expiresAt` 对历史行可为空，但新确认、`更新：` 新版本及正文编辑均从操作时刻写入 365 天后到期；管理读取保留本人当前到期记录，Agent/精确去重/向量召回只读取未到期当前行。`PATCH /api/users/memories/{id}/expiration` 使用 owner 条件更新，拒绝空值和过去时间；不自动清理、不回填历史 `NULL` 行、不推断候选 TTL。 |

**2026-08-24 实施记录。** `TC-G3-02a/02b` 先 RED：中高重要度关键词候选触发了 `user_memory` 插入和 `PERSISTED` 状态；候选列表包含终态记录；确认服务与 Controller 方法均缺失。最小 GREEN 调整 `UserMemoryFacadeServiceImpl`、`UserMemoryCandidateMapper` 与 `UserMemoryController`：确认事务以 `id + user_id + status=PENDING` 条件更新原子领取候选，内存写入/复用失败会使该状态更新随事务回滚。`TC-G3-02c` 再先 RED，确认拒绝服务/Mapper/Controller 路由均缺失后，以相同 owner + `PENDING` 条件更新实现 `DISCARDED` 转换；`TC-G3-02d` 再先 RED，确认 `clearUserMemories` 服务、Mapper 删除和 DELETE 路由均缺失后，以 `deleteByUserId(requireUserId())` 实现仅删除本人 `user_memory` 的幂等清空，不访问候选 Mapper。`UserMemoryFacadeServiceImplTest`、`UserMemoryControllerTest`、`node ui/tests/user-memory-candidate-discard.contract.mjs` 与 `node ui/tests/user-memory-clear.contract.mjs` 均通过。测试使用 mock Mapper/会话服务与 `RequestScopeData`，不调用模型、数据库或网络。

`TC-G3-02e` 先 RED：服务、请求对象、Mapper 更新和 PATCH 路由均缺失，前端也没有编辑 API 或受控输入。最小 GREEN 新增 `UpdateUserMemoryRequest`、`updateContentAndEmbedding(id, userId, content, embedding)` 与 `PATCH /api/users/memories/{memoryId}`；服务在更新前归一化内容、检查当前 owner，并以新内容调用既有 embedding 生成，失败时写入 `null`。`UserMemoryFacadeServiceImplTest` 固定本人/空内容边界，`UserMemoryControllerTest` 固定路由委派，`node ui/tests/user-memory-edit.contract.mjs` 固定 API、编辑弹窗与刷新；定向 Maven 测试和该 Node 契约均通过。为恢复完整 `testCompile`，同时仅给既有多模态接口变更遗漏的五个测试替身补齐空候选、转发或原有“不覆盖检索”异常语义，未改动生产 RAG 行为或执行评测。

`TC-G3-02f` 先 RED：构造超过 8000 字符的会话消息并预置较长历史摘要后，摘要合并结果为 4433 字符，下一轮摘要模型提示仍含已淘汰的旧前缀。最小 GREEN 在 `JChatMind` 统一限制 `conversationSummary` 的存储、决策提示与摘要提示为 4000 字符，截断时保留末尾新近内容。`JChatMindMemoryCompressionTest` 固定存储、摘要模型提示和直接决策提示这三个行为，并与既有 Agent SSE 单测共同通过；测试只使用替身 ChatClient。

`TC-G3-02g` 先 RED：同会话连续四次调用分别产生四次模型提取，两个并发调用产生两次模型提取。最小 GREEN 新增 owner-checked 会话用户消息总数查询，并以单实例 `sessionId` 状态记录已完成提取时的总数；第 1、2、3、4 条用户消息只在第 1 与第 4 次调用模型，相同 session 的并发调用只进入一次。复审新增 RED：上次计数为 4 后删除历史使总数降为 2 时只调用一次模型；最小 GREEN 将计数下降视为历史变化并立即重提取。`UserMemoryFacadeServiceImplTest` 进一步固定总数 8-11 而最近窗口恒为 8、未通过阈值不读取窗口、候选写入失败后同计数重试，以及首个模型调用受阻时第二个并发任务不能越过会话锁；`ChatMessageFacadeServiceImplTest` 固定 owner 校验后计数 Mapper 委派；定向 Maven 回归通过。状态不持久化、不跨实例分发，重启后首次事件会重新提取，时间防抖不在本项范围。

`TC-G3-02i` 先 RED：会话删除后相同用户消息数仍受旧节流状态抑制，或消息计数读取抛错后下一次首次提取仍被抑制；删除失败时不应发出状态清理事件。最小 GREEN 在 `ChatSessionFacadeServiceImpl` 的同会话协调器临界区内，在主表删除成功后发布 `ChatSessionDeletedEvent`；`UserMemoryFacadeServiceImpl` 监听后移除状态，并在 owner/session 消息计数读取失败时条件移除当前状态再抛出原异常。`ChatSessionFacadeServiceImplTest` 固定协调器委派、成功后发布和失败不发布，`UserMemoryFacadeServiceImplTest` 固定事件回收与异常回收；定向 Maven 回归通过。该项不定义 TTL、跨实例状态、持久化恢复或记忆过期/冲突关系。

`TC-G3-02h` 先 RED：同类型候选确认时，即使已有同向长期记忆仍尝试 `user_memory` 插入。最小 GREEN 在候选原子领取后，对普通候选只读取当前用户的已有长期记忆，以同类型、同维且分量有限的非零向量余弦距离 `<= 0.05` 判断重复；命中时不再插入，候选仍保持已领取的 `PERSISTED` 终态。不同类型近邻、余弦距离超阈值仍插入；非有限向量和 embedding 不可用时不读取已有向量或继续正常插入，均不阻断确认。普通确认将同一候选正文向量复用于判断和写入，避免额外模型调用。`UserMemoryFacadeServiceImplTest` 固定有限大幅值同向量、上述边界和单次 `RagService.embed` 调用；定向 Maven 回归通过。该规则不处理 `更新：` 冲突候选，不给候选表增加向量，不提供跨类型合并、自动删除或阈值配置。

`TC-G3-02j` 先 RED：确认 `更新：` 候选时仍调用旧行的 `deleteById`。最小 GREEN 为 `user_memory` 增加 `superseded_by_memory_id`，Mapper 的 `selectByIdAndUserId`、`selectByUserId`、精确文本去重和 `similaritySearch` 全部只查询空关系字段的当前行；冲突确认先插入新行，随后在同一事务中原子标记旧行。迁移 `2026-08-25-add-user-memory-superseded-by.sql` 以幂等自引用外键持久化关系，并对删除当前行使用 `ON DELETE CASCADE`，避免旧行复活。`UserMemoryFacadeServiceImplTest` 固定不物理删除旧行且记录 `oldId -> newId`；`cd backend_v2 && .\mvnw.cmd -q "-Dtest=UserMemoryFacadeServiceImplTest" test` 通过。该项不定义 TTL、历史查询、跨类型冲突、自动合并或任何 RAG 评测。

`TC-G3-02k` 先 RED：Agent 仍从全部当前行读取、期限更新服务/请求/Controller 缺失，迁移/Mapper/前端 API 均不含 `expiresAt`。最小 GREEN 新增幂等迁移 `2026-08-25-add-user-memory-expires-at.sql`、`UserMemory.expiresAt` 及 Mapper 的活跃查询：管理列表只排除已替代行，Agent 上下文、回退、精确文本去重和向量召回在 SQL 中额外固定 `expires_at IS NULL OR expires_at > NOW()`。确认新候选保持 `expiresAt=null`；`PATCH /api/users/memories/{memoryId}/expiration` 仅更新当前用户的当前行，拒绝过去时间，`null` 清除期限。前端显示永久、有效至或已过期状态，并用受控弹窗更新期限。`UserMemoryFacadeServiceImplTest`、`UserMemoryControllerTest`、`UserMemoryExpirationContractTest`、`node ui/tests/user-memory-expiration.contract.mjs` 和前端构建均通过；该项不添加后台清理、自动 TTL、历史查询或任何评测行为。

**2026-08-25 365 天期限决策。** 上述首次 GREEN 的“新候选为空期限、可清除期限”语义已废止。新增 RED 固定普通候选、`更新：` 新版本仍写入空期限，正文编辑仍走旧的四参数更新，且 `null` 可清除期限；最小 GREEN 以 `DEFAULT_MEMORY_EXPIRATION_DAYS = 365` 为普通确认、冲突插入和正文编辑写入到期时间，并以 `updateContentEmbeddingAndExpiration` 原子更新正文、向量和期限。期限 PATCH 拒绝空值与过去时间；前端请求不再允许 `null`，历史空期限记录显示为“未设置（历史记录）”，首次编辑预填当前时间加 365 天。该项不添加后台清理、历史 `NULL` 回填、历史查询或任何评测行为。

### 3.2.1 G3 记忆提取失败诊断与下一事件重试契约

`UserMemoryFacadeService.extractMemoryCandidates(userId, sessionId)` 必须返回本次执行结果：`EXTRACTED` 表示已通过节流、读取消息并完成候选提取/持久化；`SKIPPED` 表示空会话、未达节流阈值或没有用户消息。LLM 返回空白或无效 JSON 时必须进入既有关键词提取回退；回退成功同样是 `EXTRACTED`，不得把可恢复的模型响应失败静默为空候选。回退日志只能记录稳定异常类型，不得输出模型原文、异常 message 或堆栈。无法完成回退的提取异常或候选持久化异常继续向调用方抛出，且不得推进会话的已提取计数，以便后续同会话聊天事件可以再次尝试。

`ChatEventListener` 必须保持 Agent 主链路与记忆提取解耦：无论 Agent 成功或失败，均在 finally 中尝试记忆提取；提取异常不得覆盖或阻断 Agent 的原始结果。监听器以进程内、线程安全的 `(userId, sessionId)` 失败注册表记录稳定异常类型、累计失败次数和最后失败时间。注册表不得保存异常 message、用户正文、签名或其他敏感载荷。只有一次实际 `EXTRACTED` 才能清除该会话的旧失败记录；`SKIPPED` 不得将失败误标为已恢复。

本 L0 不增加 Controller/UI、数据库持久化、跨实例同步、定时或指数退避自动重试、DLQ，也不承诺进程重启后的诊断保留。“可重试”仅指记忆提取状态在失败时不推进，下一条同会话聊天事件会重新调用提取器。

| TC-ID | 先写的失败测试 | GREEN 与边界 |
| --- | --- | --- |
| TC-G3-04a | 同一 `(userId, sessionId)` 连续失败两次时，记录丢失、次数未累计或泄露原始异常 message。 | 注册表只保存稳定异常类型、次数和最后失败时间，原子累计并可在进程内查询；不持久化诊断。 |
| TC-G3-04b | 记忆提取异常使聊天处理抛错/中断，或未留下诊断；随后的 `EXTRACTED` 没有清除旧失败；`SKIPPED` 错误清除。 | 聊天 Agent 主链路保持完成，失败被诊断；同会话实际提取成功才清除，跳过仍保留失败，下一事件重试由既有未推进节流状态保证。 |

### 3.2.2 G3 LongMemEval 30 题诊断契约

LongMemEval 数据与官方评测器只能来自官方仓库 `https://github.com/xiaowu0162/LongMemEval`。“30 题”是本项目从官方 test split 分层冻结的诊断子集，不是官方固定版本，也不能表述为完整公开基准成绩。2026-08-30 本地尚未下载数据或生成 manifest；缺少仓库 revision、许可证证据、源文件 SHA-256、官方类别或评测器版本时，preflight 必须返回 `blocked_input_integrity`，不得创建评测用户、写入数据库或输出分数。

`longmemeval-30-v1` 固定抽取信息提取 6 题（单会话 3、跨会话 3）、多会话推理 6 题、知识更新 6 题、时间推理 6 题和拒答 6 题。manifest 必须保存 `caseId`、官方类别、源 revision、会话/消息 ID、reference answer、支撑证据会话/消息、抽样种子和输入 SHA-256；一经冻结不得根据运行结果换题。每题使用独立虚拟用户和清空的 `longmemeval-eval` 命名空间，按原始时间和 session 边界回放目标问题之前的会话；runner 必须调用真实候选提取、确认、长期记忆读取与 Agent 注入服务，禁止把 gold fact、reference answer 或人工整理记忆直接写入 `user_memory`。

每题固定 M0 无长期记忆、M1 全部机械确认、M2 人工盲审确认三个配对实验臂。模型、prompt、embedding、采样参数、上下文窗口和超时在三臂之间保持一致；审核员在 M2 只能查看候选及 evidence，不能查看目标问题、reference answer 或 judge 结果。报告记录候选、确认记忆、召回排名/距离、实际注入、最终回答、官方判分、耗时、token/调用次数、代码/模型/配置版本，并把失败归类为未提取、未确认、未召回、召回错误、已召回未利用、更新未覆盖、时态缺失或错误拒答。

| TC-ID | 先写的失败测试 | GREEN 与边界 |
| --- | --- | --- |
| TC-G3-05 | manifest 缺来源或哈希仍启动；30 题类别/数量错误；M0 仍读取记忆；M1 未处理全部实际候选；M2 审核看到目标问题或答案；虚拟用户间泄露；runner 直接写入 gold 记忆。 | preflight fail-closed；30 题和五类分布固定；三臂输入可比；每题独立 owner/namespace；只通过生产记忆链路形成候选和长期记忆；报告可反查来源、证据、失败阶段、成本和延迟。 |

诊断通过不以单一总准确率判断。必须分别报告总体/分类 Answer Accuracy、拒答正确率、Memory Recall@K、Candidate Precision、知识更新正确率、时态正确率、p50/p95 和单题成本；M2 相对 M0 至少净增 4 个正确 case、拒答最多新增 1 个幻觉错误、无跨用户泄露，才允许提出继续投入建议。30 题只支持方向性决策，不报告统计显著或总体领先。

### 3.2.3 G3 内置 Skill 契约

`BuiltinSkillRegistry` 只登记服务端代码固定的内置模板，不接受用户上传脚本、动态工具列表或持久化自定义 Skill。首个模板为 `technical-decision-comparison@v1`，输入是必填非空 `question` 和可选 `kbIds` 字符串数组；调用方未传 `kbIds` 时使用其全部已授权 KB，传入时只能继续收窄该范围。输入中的 `tools` 或其他未登记字段一律拒绝，避免调用方绕过模板工具边界。

该模板固定声明同步执行预算为 30 秒、并发上限为 2、不主动申请审批，唯一允许工具为只读 `KnowledgeTool`。全局 Harness 策略仍可对该工具施加审批、熔断或其他拒绝，模板声明不能降低该安全门禁。`BuiltinSkillExecutor` 先复用注册表得到不可变调用契约，再由 `HarnessedSkillKnowledgeToolExecutor` 为该工具创建 Harness 上下文；只有 Harness 结果为 `ALLOW` 时，才通过 `HarnessToolCallbackProxy` 调用绑定到本次授权 KB 子集的 `KnowledgeTools.retrieveKnowledge`。熔断、审批拒绝或超时等非 `ALLOW` 结果写入既有合成审计并返回结构化拒答；检索异常由代理进入错误审计，Skill 只返回固定拒答原因，不回显异常细节。当前没有 Controller、队列任务、Agent/模型总结或调用方可指定的动态工具；30 秒与并发 2 仍是模板契约，尚未在执行器中实现独立的超时/并发调度。

模板的非拒答输出必须有非空 `conclusion`，并提供至少一条 `evidence` 对象；每条证据同时含 `chunkId` 和位于本次授权 KB 范围内的 `kbId`。若 `abstained=true`，则必须提供非空 `reason`，此时可不提供证据。`BuiltinSkillRegistryTest` 先 RED 验证注册表缺失，再 GREEN 固定合法范围收窄、动态工具拒绝、越权 KB 拒绝，以及无证据且无拒答原因的输出拒绝；`BuiltinSkillExecutorTest` 固定执行器只能消费准备后的 KB 子集，并将无证据或 Harness 阻断收束为拒答；`HarnessedSkillKnowledgeToolExecutorTest` 固定代理接线、成功审计和熔断时不触发检索。这是 `TC-G3-01` 的 L0/L1 本地契约，不替代 HTTP/队列、真实模型、独立预算调度或 L2 集成验收。

### 3.2.4 G4 受限工作流验证契约

`WorkflowPlanVerifier` 接收尚未执行的 `WorkflowPlan`，其字段为 `maxSteps`、`timeoutSeconds` 与 `steps`。每个 `WorkflowStep` 必须有唯一 `id`、`toolName`、`factKey`、`claim` 和非空 `evidence`；每条 `WorkflowEvidence` 必须含非空 `chunkId`、与步骤相同的 `factKey` 和非空 `statement`。验证器使用与当前 `JChatMind` 相同的单次执行上限：`maxSteps` 为 1 至 20、`timeoutSeconds` 为 1 至 30，实际步骤数不得超过声明预算。

验证时，步骤工具必须属于调用方提供的白名单，且步骤声明的工具名称不得包含首尾空白；白名单比较可以规范化名称，但通过验证的名称必须与 Harness 策略的精确名称一致。相同 `factKey` 不能产生不同的 `claim`，否则返回拒绝结果及违反项。`WorkflowPlanExecutor` 先调用该验证器，只有验证通过才按原声明顺序为每个步骤创建 `HarnessContext`；每步只在 `ALLOW` 后经 `HarnessToolCallbackProxy` 调用调用方提供的受控执行器。执行结果为只读 `WorkflowExecutionResult`：无效计划返回违反项且不创建 Harness 调用；放行步骤返回 `SUCCEEDED` 与工具输出；Harness 拒绝返回 `BLOCKED` 并沿用合成审计；执行器异常返回 `FAILED` 并由代理记录错误审计。`BLOCKED` 或 `FAILED` 都立即停止后续步骤，避免继续产生副作用。它不生成 Planner 输出、不持久化结果、不映射 HTTP/队列任务，也不实现单 Agent fallback、成本预算、多 Agent 协作、可中断的角色超时或跨进程恢复；`timeoutSeconds` 在本项仍只作为验证边界，不能被表述为实际取消正在运行的工具。

`WorkflowPlanVerifierTest` 先 RED 证明验证器缺失，随后覆盖通过的授权计划、缺证据、未授权工具、同事实矛盾、超过 20 步/30 秒，以及实际步骤数超过声明预算。`WorkflowPlanExecutorTest` 先 RED 证明执行器缺失，再 GREEN 固定合法步骤的声明顺序和 Harness 成功审计、无效计划不创建 Harness 调用、首尾空白工具名不创建 Harness 调用、熔断或审批过期不进入业务执行器并分别记录 `CIRCUIT_OPEN`/`EXPIRED`、已放行步骤异常记录 `ERROR` 并停止后续步骤。这签收 `TC-G4-01` 的本地 L0/L1 验证与执行接线；`TC-G4-02` 的实际角色超时、fallback 和协作执行仍待。

### 3.2.5 G4 入站 Webhook L0 安全契约

`InboundWebhookVerifier` 只在请求尚未映射到任务中心前作纯本地判定。入站事件字段为 `sourceId`、`eventId`、`timestamp`、`signature` 和原始 `payload`；`timestamp` 必须是 Unix 秒级整秒值。签名原文固定为版本字节 `1`、`sourceId` 的 4 字节大端有符号 UTF-8 字节长度与内容、`eventId` 的 4 字节大端有符号 UTF-8 字节长度与内容、8 字节大端有符号 Unix 秒值、以及 `payload` 的 4 字节大端有符号 UTF-8 字节长度与内容，全部作为 HMAC-SHA256 的输入并编码为小写十六进制值。长度前缀保证正文和事件 ID 含 `.` 或其他文本边界字符时不会重解释为不同事件。固定互操作向量使用来源 `build-system`、事件 `event-vector`、时间 `2026-08-25T09:00:00Z`、正文 `payload` 与测试密钥 `contract-test-signing-key`，其签名为 `9bf26f4f93cf781ba3cba92464aa546d7dd676047530a788a69d58b8943de62c`。验证器不读取配置文件或密钥系统，调用方仅以内存参数提供预先匹配的来源和签名密钥；实现不记录原始密钥或正文。

来源 ID 必须与已解析来源一致，事件 ID、时间戳和签名不能为空，签名使用常量时间比较；时间戳与验证时刻的偏差不得超过 5 分钟。只有验签和时间窗通过的事件才能占用 `(sourceId, eventId)`，同一 JVM 内再次收到相同事件必须返回重复结果，且不会进入后续业务映射。来源不匹配、签名缺失或无效、过期或未来时间戳均拒绝，拒绝事件不得占用事件 ID。结果仅暴露固定状态码，不回显密钥、签名或正文。

该 L0 组件不提供 Controller、来源白名单存储、任务创建、出站 HTTP、重试、投递日志或 DLQ；进程重启后的跨实例幂等和“业务持久化与事件占用”原子性，必须在后续任务中心/持久化接线中补齐。`InboundWebhookVerifierTest` 先 RED 验证类缺失，随后再 RED 复现分隔符重解释和同秒纳秒篡改，再 GREEN 固定有效签名通过、来源或签名拒绝、重复事件拒绝、无效签名不占用 ID、非整秒时间戳拒绝，以及超过正负 5 分钟时间窗拒绝。该项只补充 `TC-G4-03` 的本地安全门禁，不将其表述为 Webhook 端到端验收。

### 3.2.6 G5 单实例会话执行互斥契约

`ChatEventListener` 的异步处理必须先按 `sessionId` 进入 `ChatSessionExecutionCoordinator`，锁覆盖 `JChatMindFactory.create(...)/JChatMind.run()` 和 finally 中的候选记忆提取；同一会话在当前进程内不得并发运行这两个步骤，不同会话不应被全局锁阻塞。协调器必须在等待锁前登记会话引用，并仅在最后一个执行或等待任务退出后移除该会话锁，避免清理竞争导致同会话重新并发。

该契约只约束单实例中实际到达协调器的顺序互斥：它不排序跨 `@Async` 工作线程的提交先后，不提供跨进程锁、持久化队列、重启恢复、背压、专用线程池隔离或多实例 SSE 分发。`ChatSessionExecutionCoordinatorTest` 先 RED 确认协调器缺失，再 GREEN 固定同会话第二任务在首任务结束前不能开始、不同会话可完成；`ChatEventListenerTest` 再先 RED 确认监听器未依赖协调器，再 GREEN 固定委派以及 Agent 后提取记忆的顺序。

`TC-G5-01` 只补齐 Agent 执行的隔离有界线程池：`ChatEventListener.handle` 必须显式使用 `@Async("agentTaskExecutor")`，该 Bean 固定为 core=2、max=4、queue=50 和 `agent-event-` 线程前缀，并复用既有请求上下文传播；未限定的邮件 `@Async` 继续使用 `taskExecutor`。该项不为工具调用、Rabbit 文档索引或尚未实现的 Webhook 投递声明专用执行器，也不提供背压策略、多实例调度或持久化恢复。`AsyncConfigTest.shouldBindChatEventHandlingToDedicatedBoundedAgentExecutor` 先 RED：应用上下文不存在该 Bean；最小 GREEN 新增 Bean 并绑定监听器。GREEN 回归命令为 `cd backend_v2 && .\mvnw.cmd -q "-Dtest=AsyncConfigTest,ChatEventListenerTest,ChatSessionExecutionCoordinatorTest,ChatMessageEventFlowIntegrationTest" test`。

### 3.3 后续阶段的测试先行要求

G1 起，每个 `TC-ID` 在实现前必须补充测试类/文件、方法名、固定 fixture、RED 预期失败和 GREEN 回归命令。G1 的 Playwright 用例先失败后再实现 UI；G2-G5 的路由、状态机、签名、并发和恢复逻辑先以 L0/L1 契约测试固定，随后再补 L2/L3 集成验证。

每次 RED/GREEN 证据写入总计划的逐用例验收台账：记录数据/配置版本、命令或报告路径、执行日期、执行人和结论。仅在测试确实失败过且失败原因符合预期时，才允许进入生产实现。

### 3.4 测试数据与副作用边界

- L0 使用受控 fixture，不启动 Spring 或外部服务。
- L1 对邮件、MCP/Web 使用替身；不得断言替身自身行为而忽略业务结果。
- L2 只连接隔离 Docker 中的 PostgreSQL、Redis、RabbitMQ 和 Ollama，使用可清理测试账号与知识库。
- L3 从 G1 起使用 Playwright；截图和报告写入构建产物，不提交生产数据或凭据。
- 默认报告路径为 `backend_v2/target/surefire-reports/`、`backend_v2/target/rag-eval/` 和 Playwright 报告目录。

#### 3.4.1 G0 L3 隔离资源的历史记录与当前状态（2026-08-17）

下列资源用于 2026-08-17 至 2026-08-18 的 G0 L3 验收：账号 `g0l3_20260817_114535`（userId `9`）、知识库 `G0 L3 隔离知识库 20260817-114535`（`7005d6b2-85d8-4639-87ae-82740070dd27`）、Agent `G0 L3 隔离验收 Agent 20260817-114535`（`9b1c349a-270b-4972-aaec-7a58a6965367`）与 Markdown 文档 `g0-l3-rag-20260817-114535.md`（`505cd70f-0993-4d23-8524-4afa2aff6351`）。该 KB、Agent 和文档已在本轮历史 KB 清理中删除；账号 `user_id=9` 被保留并重新登记为 `RAG Recall Fixture KB` 的 owner。密码、JWT 和其他凭据不得写入本文或提交到仓库。

隔离账号下曾有临时 Agent `agent`，仅用于 `TC-G0-06` 的 `AI_ERROR` 浏览器旅程；它也已删除。G0 截图和手工验收记录保持为历史证据，但不能再使用任何 G0 L3 资源复验或新建 G1 fixture。

| 复用范围 | 是否可复用 | 使用规则 |
| --- | --- | --- |
| `TC-G0-04` 至 `TC-G0-06` 历史证据 | 不可复用 | 已删除的 G0 L3 KB、Agent、文档和临时 Agent 仅支撑已有截图/手工签收，不能用于新的浏览器或权限验收。 |
| `TC-G0-01` 至 `TC-G0-03` | 不可复用 | 启动图、聊天持久化集成、RAG 指标和冻结 replay 分别需要最小 TestConfig、受控 mock 或冻结 fixture；本资源不能替代其数据口径。 |
| G1 权限、幂等和摄入验收 | 不可复用 | `user_id=9` 仅保留为受控 Recall fixture KB 的 owner。越权、跨用户/跨 KB 和重复上传必须按 G1 RED 契约新建第二个隔离账号、KB 与文档。 |

历史 G0 L3 资源已删除；当前保留的评测 KB 只用于受控 Recall 回归，不能以其结果替代真实 RAG、权限隔离或新的浏览器验收。

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
| G2-RAGAS-01 | G2 | 固定检索与 Router 链路在同一冻结数据集、配置版本和成本口径下对比。 | Router 实现后复用 RAG 评测入口并新增对比报告；关联 `TC-G2-05`。 |
| G2-RAGAS-02 | G2 | 多模态 golden case 的上下文覆盖与引用定位。 | 多模态摄入实现后新增受控评测数据与报告；关联 `TC-G2-06`。 |
| G2-RAGAS-03 | G2 | 无答案、权限越界和拒答 case 不进入无效的 Context Recall 聚合。 | 拒答评测实现后复用 RAGAS 报告的 `evaluated/skipped` 口径；关联 `TC-G2-05`。 |

执行 `TC-G0-03a` 至 `TC-G0-03d` 时，报告必须记录数据集版本、评测配置、`ragas.enabled`、sample size、judge 模型（如启用）、成本、延迟、`evaluated/skipped` 及 skip reason。真实 KB、Router 与多模态能力尚未完成时，对应 G2 用例保持“待该阶段实现”，不得替代为 fixture 通过。

#### 4.7.3.1 外部基准评测契约

本项目首次外部基准评测同时接入 mMARCO 与 CRUD-RAG，但两者保持独立报告、独立指标和独立结论。官方入口分别为 mMARCO 的 [GitHub 仓库](https://github.com/unicamp-dl/mMARCO) 与 [Hugging Face 数据集](https://huggingface.co/datasets/unicamp-dl/mmarco)，以及 CRUD-RAG 的 [GitHub 仓库](https://github.com/IAAR-Shanghai/CRUD_RAG) 和 [论文](https://arxiv.org/abs/2401.17043)：

| 数据集 | 评测对象 | 输入与 gold | 必报指标 | 主要边界 |
| --- | --- | --- | --- | --- |
| mMARCO | 多语言 passage retrieval；向量、VectorChord BM25 和 RRF 融合候选召回 | 官方 query、语言、split、相关 passage 标注；映射到本项目文档/chunk 后保留原始 ID 和映射版本 | `Recall@1/5/10`、`MRR@10`、`nDCG@10`、每语言样本数、p50/p95 检索延迟、索引/embedding 版本 | 不覆盖本项目私有 owner 权限、会话范围、拒答、引用和最终答案质量 |
| CRUD-RAG | 中文 RAG 综合基准中的检索与生成任务；官方仓库提供 `data/crud`、`data/crud_split`、`data/80000_docs` 和任务脚本 | 官方数据划分、文档库、问答/摘要/续写/事实修改任务输入与参考答案；保留官方任务定义和评测脚本版本 | 检索链路另报 `Recall@K`/`MRR@K` 等；官方任务另报其定义的答案质量指标、样本数和 p95 | 不替代 mMARCO 的多语言横向检索，也不单独证明 VectorChord 性能、动态 CRUD 事件一致性或授权链路 |

外部基准执行约束：

1. 下载前登记官方来源 URL、版本/发布日期、许可证和文件 SHA-256；若官方信息未核对，保持 `准备中`，不把结果写成通过。
2. 使用独立测试数据库、独立 KB/文档/chunk ID 命名空间和独立文件目录；禁止写入真实业务库或复用生产索引。所有预处理脚本、字段映射、语言过滤、官方任务选择和数据划分均须版本化。
3. 将 development split 与 untouched test split 分离。embedding 模型、chunk 策略、BM25 词典、RRF 参数和 rerank 配置只能在 development split 决定；test split 只允许一次冻结执行或明确记录重跑原因。
4. mMARCO 按语言分别聚合，不跨语言平均原始分数；CRUD-RAG 按官方任务和 split 分组聚合，不把问答、摘要、续写和事实修改混为未经定义的单一 Recall。两个数据集不计算未经定义的综合分数。
5. 报告同时包含 `datasetVersion`、`license`、`sourceSha256`、`preprocessVersion`、`mappingVersion`、`indexVersion`、`embeddingModel`、`configSha256`、`sampleSize`、`evaluated/skipped`、`skipReasons` 和延迟统计。

建议报告路径：`backend_v2/target/rag-eval/external/mmarco-<version>-report.json` 与 `backend_v2/target/rag-eval/external/crud-rag-<version>-report.json`。外部基准通过只表示对应数据集和配置下的可复现结果；G2 阶段仍须另外通过 `TC-G2-02` 至 `TC-G2-10` 的权限、Router、拒答、引用、独立三路和冻结集验收。

#### 4.7.3.2 `mMARCO-zh-sampled` 的 TEI BGE rerank 评测契约

本契约定义一次可在本机运行的受控诊断，不取代上一节全量 mMARCO 评测。评测库只能包含冻结后的 zh passage 子集，且只能写入 `rag-eval` 隔离 PostgreSQL/VectorChord 库与独立上传目录；不得读取、写入或关联业务数据库、真实 KB、用户会话或生产索引。

**实现边界与交接门禁（2026-08-26）。** 实现使用 Java 测试作用域的 `MmarcoZhSampledDatasetFreezer`、`MmarcoZhSampledManifestImporter`、`MmarcoZhSampledIsolatedImporter`、`MmarcoZhSampledOllamaBatchEmbedder`、`MmarcoZhSampledRuntimeReplayRunner`、`MmarcoZhSampledRuntimeEvaluationTest`、`MmarcoZhSampledEvaluationRunner`、`MmarcoZhSampledEvaluator` 与 `MmarcoZhSampledReportWriter`；不引入 Python 运行时、`datasets` 依赖或生产公共接口。候选 embedding 的响应数量/顺序必须与输入严格一致；300 秒导入专用超时、4 MiB 解码上限和实际批大小进入运行 `configSha`，不改变检索或 TEI 超时。v2 使用每批 64 条；当前 `mmarco-zh-sampled-v3-local-diagnostic` 使用每批 8 条，以避免本机 CPU 上单批超过导入超时。`MmarcoZhSampledManifestImporter` 在同一显式 JDBC 事务内逐批 embedding、BM25 投影和写入，任一失败整体回滚。冻结器只接受本地物化的 collection、queries、qrels 和官方 run，先记录四份文件 SHA-256、zh 语言、固定上游 revision `6d039c4638c0ba3e46a9cb7b498b145e7edc6230`、预处理版本和映射版本，再生成 manifest。上游 Git LFS pointer、未完成下载、对象 OID/文件大小不匹配、revision/language/mappingVersion 不匹配或缺少 source SHA-256 时，运行必须在冻结前停止并标记 `blocked_input_integrity`，不能创建候选库或输出任何检索指标。

**映射实现。** 每个原始 passage 保持一对一 document/chunk，逻辑 ID 为 `mmarco:zh:<passageId>`；runtime chunk UUID 为逻辑 ID UTF-8 字节的 `UUID.nameUUIDFromBytes(...)`。导入器必须显式写入该 UUID，不能依赖会随机生成 ID 的既有 mapper；metadata 至少保存 `datasetVersion`、`candidateManifestSha256`、`mappingVersion`、`logicalChunkId`、`passageId` 和 `candidateSourceType`。只要任一映射或导入版本变化，就必须生成新数据集版本，不得与既有报告比较。

**数据冻结。** 固定 300 条带 qrels 的查询，development 与 untouched test 的 query ID 必须不重叠。候选集合由全部 qrels 正例、每条最多 100 条已校验来源的 hard negative 和 20,000 条固定随机干扰 passage 组成，最终去重规模为 20,000 至 50,000 条。每个原始 passage 必须一对一写为 document/chunk，稳定逻辑 ID 为 `mmarco:zh:<passageId>`；manifest、query 清单、候选映射和检索 replay 都必须记录随机种子、输入 SHA-256、上游 revision 与生成脚本版本。没有官方或可复现的 BM25 run 时，hard negative 来源为空即构成阻塞，不能伪造或无声降级。

**当前冻结版本。** `mmarco-zh-sampled-v1` 的 49,691 candidate 导入未产生检索报告：旧单条调用遭遇 30 秒 embedding 超时，初次批量调用又遭遇 WebClient 256 KiB 解码上限，均未完成 JDBC 导入。当前 `mmarco-zh-sampled-v2` 使用相同四份已验证输入、随机种子、300 query 划分、qrels、embedding 模型和 mappingVersion；只将官方 hard negative 上限固定为每 query 1 条，得到 20,616 candidate（316 qrels positive、300 official hard negative、20,000 random distractor）。这是独立 datasetVersion，任何 v1 尝试、映射或报告均不得与 v2 比较；该选择仍满足本节的 20,000-50,000 candidate 和“每条最多 100 条 hard negative”约束。

**本机 CPU 例外版本。** `mmarco-zh-sampled-v3-local-diagnostic` 使用与 v2 完全相同的上游 revision、四份 source SHA-256、300 query 划分、316 qrels positive、300 official hard negative、随机种子、embedding 模型和 mappingVersion；只将固定 random distractor 降为 500，故冻结为 1,116 candidate，并把导入 batch 固定为 8。该变化独立形成 v3 manifest 与 `configSha`。它是因本机 CPU 对 v2 的 20,616 candidate 导入预估约 19 小时而采用的受控诊断子样本，不满足本节 20,000-50,000 条的一般候选规模；v3 只可用于相同 v3 候选库、相同 300 query、相同有效分母与相同检索配置下的 A/B/C rerank 比较，不得与 v1/v2、全量 mMARCO 或不同候选规模报告比较绝对分数，亦不得据此宣称全量 mMARCO 质量。所有 qrels positive、官方 hard negative 和 deterministic passage/chunk UUID 映射仍完整保留。

**前置门禁。** 冻结数据集加载器和 RAG TestConfig 的既有失败必须先恢复为 GREEN。开始 C 臂前，TEI `/rerank` 健康检查必须证明：50 个候选获得 50 个唯一索引和非空分数；否则不启动对比。运行过程中出现 TEI 超时、HTTP/解析异常、重复/缺失索引或 `RagServiceImpl` 日志中的本地回退时，该 query 记录 `invalid_tei_fallback`，C 臂整体为 `invalid`，不得将回退结果记作 BGE rerank 成绩。

**实验矩阵。** 每臂在同一已导入索引上独立执行两次，随机化执行顺序；固定 KB 范围、Top-K、embedding、BM25 词典、RRF 参数、超时和 query 清单。主对比设 `rag.eval.disable-query-expansion=true`，避免改写结果影响排序；完整链路确认性运行只能使用同一份预先冻结的 query rewrite replay。

| ID | `rag.eval.disable-rerank` | `rag.rerank.enabled` | 预期行为 |
| --- | --- | --- | --- |
| A | `true` | `false` | RRF 融合后直接截断，不执行任何 rerank。 |
| B | `false` | `false` | 对 RRF 前 50 条执行本地规则 rerank。 |
| C | `false` | `true` | 对相同 50 条候选调用 TEI `BAAI/bge-reranker-v2-m3` 并按返回分数重排。 |

**指标与报告。** 每臂对相同有效 query 集报告 `Recall@1/3/5/10`、`MRR@10`、`nDCG@10`、RAGAS `IDBasedContextPrecision`、`IDBasedContextRecall`、p50/p95 检索延迟、TEI 成功率及逐 query rank 变化。主结论比较 B 与 C，并使用 1,000 次逐 query `C - B` bootstrap 输出 `MRR@10`、`nDCG@10` 差值的 95% 置信区间；A 只用于判断任意 rerank 的作用。检索报告路径为 `backend_v2/target/rag-eval/external/mmarco-zh-sampled-<version>-retrieval-ab.json`，字段至少包含 `runId`、`datasetVersion`、`sourceSha256`、`candidateManifestSha256`、`mappingVersion`、`indexVersion`、`embeddingModel`、`rerankerModel`、`configSha256`、`variant`、`sampleSize`、`validCount`、`invalidCount`、`invalidReasons`、指标和延迟分位数，以及各臂的 `queryReplays`（gold、ranked chunk、延迟和 TEI 回退标记）。

**端到端 RAGAS。** 从 untouched test 固定抽取 100 条，用同一回答模型、`temperature=0` 和相同检索上下文生成回答，再用独立记录版本与提示词哈希的 judge 计算 Faithfulness、Response Relevancy、`evaluated/skipped` 和 skip reason。mMARCO 只提供 passage relevance qrels，不提供可用于 Answer Correctness 的参考答案；因此端到端报告不得解释为回答事实正确性。judge 结果单独输出到 `backend_v2/target/rag-eval/external/mmarco-zh-sampled-<version>-ragas-ab.json`，并标明回答模型、judge 模型、延迟和成本（如可得）。

**通过判定。** 只有 C 臂无 TEI 回退、B 与 C 使用完全相同的有效 query 集、`MRR@10` 和 `nDCG@10` 均未低于 B、p95 增幅不超过 15%，且报告哈希/样本分母完整时，才可以标记为 `eligible_for_full_validation`。这不是默认启用条件，也不是全量 mMARCO 分数；任何门禁失败均标记 `invalid` 或 `inconclusive`，保留原始报告和原因。

**本机执行上限与已运行结论（2026-08-28）。** v3 的 300 条 query 仅表示冻结池；受本机 CPU 约束，每次 A/B/C 只允许使用同一份 50 条 development query，后续不得扩样本。已完成的 50 条运行中，A/B/C 的输入、候选、配置、Top-K 和有效分母完全一致，TEI C 臂 `teiSuccessRate=1.0` 且无回退；C 相对 B 的 `MRR@10` 与 `nDCG@10` bootstrap 95% CI 均为正，但 p95 为 `285,816ms`，超过 B `4,767ms` 的 15% 上限。因此该报告为 `inconclusive`，不得默认开启 TEI rerank，也不得表述为全量 mMARCO 结论。完整指标、hash、逐 query replay 与 bootstrap 结果以 v3 retrieval A/B 报告为准。

**术语与审计边界。** 本机“50 条评测”是同一份 50 个 development query，不是 50 条候选文本；300 条是冻结 query 池（development 200、untouched test 100），而 1,116 条是固定检索候选库（316 qrels 正例、300 官方 hard negative、500 固定随机干扰）。每个 query 从该 1,116 条 passage/chunk 库中检索，B/C 仅把全局 RRF Top-50 送入 rerank，最终以 Top-10 计分；C 两次独立执行代表 100 次 TEI rerank 请求。每个 mMARCO passage 保持一对一 chunk，不能按通用文档摄入规则再切块。评测全过程、六份 replay 的留档、随机执行、ID-based RAGAS 与未执行的 LLM judge 边界，以及 R0/R1/R2 不混入 A/B/C 的实际运行记录，统一见路线图 `3.3.2` 和 `3.3.3`。

#### 4.7.3.3 独立三路结构消融契约（G2-3b）

本评测在 rerank A/B/C 之前执行，只检验召回结构。固定 query rewrite replay、`rag.eval.disable-rerank=true`、相同授权 KB 范围、gold、Top-K、embedding、BM25 词典、`RRF_K`、超时、总候选预算和有效 query 集，分别运行 `current-flat`、`two-branch-original` 与 `three-branch-expanded`。评测配置或输入哈希任一不同即拒绝比较，不允许用手工筛选 query 或不同分母制造收益。

`three-branch-expanded` 的第三路只能使用非原问 replay；每个 query 的报告必须记录 original/expanded query ID、三个分支的候选总数和 chunk 去重数、每个 gold 的命中分支、outer RRF 前后 rank、`Recall@1/3/5/10`、`MRR@10`、`nDCG@10`、无答案误召回、越权数和 p50/p95。报告额外记录 `variant`、`branchConfigSha256`、`queryReplaySha256`、`candidateBudget`、`validCount`、`skippedCount` 与 `skipReasons`，建议路径为 `backend_v2/target/rag-eval/three-branch/<datasetVersion>-<variant>.json`。

项目内冻结集至少有一组具备授权会话上下文且 replay 包含非原问扩展 query 的 follow-up case；否则 R2 只能标为 `not_exercised`，不得解释为 Multi-Query 分支无收益。mMARCO-zh-sampled 若不具备此类 replay，继续只用于 Dense/Sparse/RRF 与 `4.7.3.2` 的 rerank 诊断。只有 `TC-G2-09` 和 `TC-G2-10` 全绿、R2 对相同有效 query 集不低于 R0 的 `Recall@5`、`MRR@10`、`nDCG@10`、p95 增幅不超过 15%，且授权/拒答无回归时，R2 才可进入同一链路上的 A/B/C rerank 对比；否则保持 R0 为默认并保存失败报告。

**已运行结论（2026-08-30 复跑）。** `g2-pre-bm25-v1` 使用 9 个 case（7 个可回答、2 个拒答）和 7 个 fixture chunk，在真实 MyBatis/VectorChord/BM25/Ollama 链路运行 R0/R1/R2。输入哈希、scope、gold、query replay、候选预算和有效分母可比；复跑前将生产 Router 的确定性拒答纳入 replay，三臂拒答/权限违规均为 `0`，Recall@5 均为 `1.0`。R2 的 MRR/nDCG 仍低于 R0，因此质量门禁失败，R0 保持默认，R2 不进入默认链路；两个拒答 Bad Case 已标记 `fixed`，不能通过改换分母或将 case 用于同版本调参来重写质量结论。

同一报告中的 `g2-pre-bm25-v1-002` 与 `g2-pre-bm25-v1-009` 已冻结为 P1 development Bad Case：R0 的多通道平铺 RRF 将 vector、title BM25、content BM25 分别计票，而 R1/R2 按 `outer_rrf_one_vote_per_branch` 把词法通道先归并为 Sparse 分支，外层不允许以同一分支的多个通道重复投票。该差异是本次结构消融的受控变量，不是可通过专用权重掩盖的生产缺陷；两条 Case 的处置固定为 `keep_r0_default_not_fixed`，待新的独立结构假设和同分母评测验证后再决定是否变更。

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

### 4.10 G1 运行时强化验收（2026-08-19）

本轮新增两个真实 L2 子项，均先 RED 后 GREEN，未用 mock、静态契约或 lint/build 代替运行时证据。`G1AdvisoryLockRuntimeL2Test` 使用独立 PostgreSQL、真实 MyBatis Mapper、事务管理器和两条 JDBC 连接：外部连接持有同一 owner+key advisory lock，测试通过 `pg_stat_activity.wait_event=advisory` 观察竞争窗口；提交后两请求返回同一任务，跨资源复用同键拒绝，触发器回滚后无任务/幂等残留且后续同键可提交。最小 GREEN 是在 `IngestionTaskServiceImpl.submitDocumentIngestion` 事务入口调用现有 `lockOwnerIdempotencyKey`。

`G1FileCompensationRuntimeL2Test` 使用真实上传门面/Controller 先写物理文件，再由数据库触发器令任务插入失败。RED 实际观察到统一内部错误但遗留 1 个业务文件；GREEN 在上传事务异常路径调用现有 `DocumentStorageService.deleteFile`，错误响应不泄露路径或数据库错误正文。GREEN 还验证移除触发器后同一幂等键重试成功、重放返回原 document/task 且物理文件仍只有成功提交的 1 个。

定向命令（实际值仅存在于进程环境，不写入文档）为两条隔离 Maven 命令：`-Dtest=G1AdvisoryLockRuntimeL2Test` 与 `-Dtest=G1FileCompensationRuntimeL2Test`，均连接本轮临时 PostgreSQL 和临时上传目录；两项 GREEN Surefire 均 `Tests run` 且失败/错误为 `0`，既有 `IngestionTaskServiceImplTest,DocumentFacadeServiceImplTest` 回归退出码 `0`。测试结束已删除 PostgreSQL 容器、临时目录和测试 KB 文件目录；未读取或修改真实业务库。仍未覆盖真实 embedding 成功链路、模型驱动 Agent 工具调用、损坏文件和端到端跨组件恢复。

同日继续以独立 PostgreSQL 和独立上传目录执行部分文件写入补偿。`G1FileCompensationRuntimeL2Test.shouldRemovePartialPhysicalFileAndDirectoryWhenStreamFailsMidWrite` 使用真实 `DocumentStorageServiceImpl`，令上传输入流已经返回首段字节后再抛出 `IOException`；有效 RED 的断言为临时目录中仍有 `1` 个业务文件，而数据库的 `document`、`ingestion_task` 与 `chunk_bge_m3` 均已经回滚。最小 GREEN 只在 `DocumentStorageServiceImpl.saveFile` 的 `Files.copy` 失败分支清理该次目标文件，并在目录为空时删除其文档和 KB 父目录。相同真实 L2 命令还回归任务创建失败补偿、同键成功重试及重放不删除成功文件；GREEN Surefire 为 `2 tests, 0 failures, 0 errors`。该结论只覆盖文件复制中断，不覆盖消费者/RabbitMQ/embedding 的端到端恢复。

### 4.11 G1 Rabbit 消费数据库失败恢复验收（2026-08-20）

`G1RabbitConsumerDatabaseRecoveryRuntimeL2Test` 使用独立 PostgreSQL、独立 RabbitMQ、真实 `RabbitTemplate`/listener、MyBatis Mapper、Spring 事务代理和独立临时存储目录。测试在 `chunk_bge_m3 DELETE` 上创建受控 PostgreSQL trigger，使处理器删除旧 chunk 时抛出数据库异常；异常发生在真实 `DefaultIngestionTaskProcessor @Transactional` 内，随后由真实消费者调用任务失败状态迁移并投递 retry/DLQ。

先执行测试配置编译与真实运行命令：未加引号的 PowerShell Maven 属性导致插件坐标解析失败；修正命令后分别观察到缺少现有 `PgVectorTypeHandler` 注册、以及具体类注入遇到 JDK 事务代理的上下文启动错误。两项均为隔离测试配置问题，分别在测试配置注册现有 handler、启用 class-based transaction proxy 后解决。进入业务后的行为 RED 未复现，因此未人为破坏生产代码、未添加生产 GREEN 修复。

真实运行命令为 `.\mvnw.cmd "-Dtest=G1RabbitConsumerDatabaseRecoveryRuntimeL2Test"` 加隔离 PostgreSQL/RabbitMQ/存储系统属性和 `G1_RABBIT_RECOVERY_L2=true`；同一命令独立重复两次，均为 `1 test, 0 failures, 0 errors`、退出码 `0`。每次运行首次失败后任务为 `RETRYING/attempt_count=1`，文档、任务、chunk 与物理文件均保持 `1`，retry queue 有消息；第二次为 `RETRYING/2`；第三次为 `DEAD_LETTER/3`，DLQ 有消息，数据库与物理文件计数不增加。该证据覆盖真实数据库失败时的事务回滚、任务重试/死信及无重复持久化副作用，不覆盖正常 Markdown/HTML embedding 成功、模型驱动 Agent 工具调用、HTML 结构化提取、SSE 进度或跨外部模型/embedding 的端到端恢复。

### 4.12 G1 模型驱动会话临时范围收窄验收（2026-08-21）

`G1ModelDrivenSessionScopeRuntimeL2Test` 使用独立的临时 PostgreSQL、真实 `DeepSeekChatModel`、`JChatMindFactory`、`KnowledgeTools`、Harness 和消息持久化服务；DS 的 base URL、模型和 API Key 只在测试进程环境中注入。测试数据源只接受 `jdbc:postgresql://127.0.0.1:<49152-65535>/g1_model_scope_<12 位十六进制 nonce>`，且数据库名后缀必须与本次 `g1.pg.nonce` 精确相同；临时 Docker PostgreSQL 采用 trust 认证、固定 `g1scope` 用户名和空密码，不读取 PostgreSQL 用户名或密码，也不读取或写入业务库、RabbitMQ、上传目录及其他本地凭据。测试让同一 owner 的 Agent 绑定 A1/A2，并在会话 metadata 写入 `retrievalContext.kbId=A1`。系统提示和用户消息均要求模型调用 `KnowledgeTool` 时只传 `query`、不传 `kbIds`。

安全复审后的两次独立运行均通过（每次 `1 test, 0 failures, 0 errors`）：真实 DS 实际发起的工具调用参数只有 `query`；测试专用 `RagService` 记录后端传入的有效 KB 集合仅为 A1；最后一个工具消息后的最终 Assistant 含 A1 证据标记且不含 A2 标记；数据库消息顺序至少为 `user -> assistant(tool call) -> tool -> assistant`。配置单测先 RED 后 GREEN，固定业务 URL 在连接前被拒绝，合法隔离 URL 不依赖 PostgreSQL 凭据。该 L2 用例刻意以记录型 `RagService` 隔离会话授权边界，不能替代真实 embedding/召回质量、模型显式传入越权 `kbIds` 的独立路径或跨外部模型/队列故障恢复。

### 4.13 G1 外部 embedding 自动恢复验收（2026-08-21）

`G1EmbeddingRecoveryRuntimeL2Test` 使用随机命名的 PostgreSQL 数据库、RabbitMQ 容器/vhost/用户和上传目录。首次 `RagService.embed` 连接明确不可达端点，随后才委托本机 Ollama `bge-m3:latest`；测试配置将 `g1.storage.dir` 映射为 `document.storage.base-path`，使真实 `DocumentStorageServiceImpl` 的读取位置与测试写入位置一致。

有效 RED 先确认外部 embedding 始终不可用时任务不会误成功；之后以读取前的路径/文件断言定位隔离装配差异。GREEN 的真实队列运行中，首次处理后任务为 `RETRYING(1)`、retry queue 有消息、chunk 为 0、物理文件为 1 份；测试不手工再次投递，由 RabbitMQ TTL/DLX 自动回投。回投后任务为 `SUCCEEDED(1)`，两个 chunk 均持久化非空 1024 维 embedding，物理文件仍为 1 份，Surefire 退出码为 `0`。临时容器、数据库、vhost、用户与目录在 `finally` 中删除。

该验收只覆盖外部 embedding 短暂不可用后的自动恢复；其他外部依赖、图片/OCR、SSE 多实例分发和持久化事件恢复仍需独立用例。

## 5. 项目级 release-v1 就绪契约

本节是跨 G1-G5 的发布门禁，不新增功能阶段，也不否定已完成的阶段证据。任一项未通过时可以继续本地开发和诊断，但不得将仓库标记为 `release-v1 ready`。实现迁移工具、扫描器或观测组件若需要新增依赖，仍须先经 Ban 确认；在此之前可使用仓库现有 Maven、npm、PowerShell、Docker 和 SQL 能力完成最小契约。

| TC-ID | RED / 当前缺口 | GREEN 与证据 |
| --- | --- | --- |
| TC-REL-01 默认构建 | 默认后端命令当前已达到 `0 failures/0 errors`；前端 lint/build 已可执行，但静态契约、L2/RAG/Playwright 的统一入口与 executed/skipped/reason 报告仍未收口。 | 一条文档化入口完成默认后端零 failure/zero error；一条前端入口完成静态契约、lint、build；L2/RAG/Playwright 使用显式 profile/任务并在报告中列出 executed/skipped/reason。 |
| TC-REL-02 数据库生命周期 | 根 README 只列两个 auth SQL，无法从 16 个增量 SQL/说明文件推导唯一 clean install/upgrade 顺序；此前没有可执行 schema migrator。 | 已提供 [`sql/migrations/manifest.json`](../../sql/migrations/manifest.json)，并由 `SchemaMigrationExecutor`/`JdbcMigrationStore` 固定 schemaVersion、16 份脚本的顺序/依赖/事务属性/SHA-256、批准基线、ledger 状态与 catalog fail-closed 校验；真实 VectorChord PostgreSQL 隔离 clean install、前缀 upgrade、重放和失败恢复已通过 `MigrationLifecycleRuntimeL2Test`（`3 tests, 0 failures, 0 errors, 0 skipped`），完整生产对象 catalog 对账和发布入口仍待实现。 |
| TC-REL-03 数据与 Git 边界 | 本地 `datasets/` 未被根忽略规则覆盖，存在误提交公开基准或大文件风险；报告位于 target 但来源 manifest 尚不统一。 | 原始公开数据、真实用户内容、凭据和生成报告被忽略；只提交来源 registry、不可变 manifest、预处理/评测代码和小型脱敏 fixture；测试验证关键目录 ignore 规则及 manifest 必填字段。 |
| TC-REL-04 安全与隐私 | 现有 owner/Harness/MCP 边界较完整，但缺统一的 prompt injection、SSRF/外部 URL、恶意/超限上传、日志脱敏和数据保留负向矩阵。 | 对 REST/SSE/MCP/Webhook/摄入/记忆建立威胁到 TC 的映射；高风险输入在副作用前拒绝；日志不含凭据、用户正文或内部路径；审计、消息、任务、记忆和文件有保留/删除规则。 |
| TC-REL-05 负载与可观测 | G5 只规定局部线程池和相对 p95 增幅，没有固定并发、KB/chunk 数、文件大小、队列积压或观测阈值。 | 固定 release 负载 profile；报告首 token/完整响应 p50/p95/p99、错误率、队列积压、线程池拒绝、SSE 重连、模型/embedding 调用和存储增长；traceId 可贯通 HTTP、任务、Rabbit、Worker、RAG/模型与 SSE。 |
| TC-REL-06 备份恢复 | 只有局部索引/表恢复证据，没有 PostgreSQL 业务数据、上传文件和必要队列状态的整体 RPO/RTO。 | 定义 RPO/RTO；在隔离环境完成一次备份、破坏后恢复和一致性核对；向量/BM25 索引能够从业务事实重建，报告记录耗时、丢失窗口和人工步骤。 |
| TC-REL-07 API 与产品旅程 | REST/SSE/MCP 有局部契约但无统一版本/弃用规则；阶段指标主要是技术指标。 | 发布记录固定 REST/SSE/MCP schema 版本与兼容边界；Onboarding、故障排查、技术决策追溯分别有冻结旅程，报告完成率、引用正确性、人工修正率和响应时间。 |

`TC-REL-*` 的证据不得只引用设计文档；必须包含可执行命令或测试、输入版本、报告路径、执行日期、执行人和结论。首次补齐时先把当前缺口稳定复现为 RED，再做最小 GREEN，不借发布治理重构无关业务代码。
