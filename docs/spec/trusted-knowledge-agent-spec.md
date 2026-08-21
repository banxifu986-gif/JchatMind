# 可信研发知识协作 Agent 实施 Spec

> 状态：当前唯一实施 Spec
> 对应计划：[可信研发知识协作 Agent 升级总计划](../plans/active/trusted-knowledge-agent-roadmap.md)
> 当前实施阶段：G1 owner-only 知识库硬权限、任务中心与异步摄入已完成 L0/L1 契约；隔离 PostgreSQL/RabbitMQ 已完成 HTTP/JWT、重试/死信、advisory lock、文件补偿、Rabbit 消费失败恢复、Markdown/HTML/PDF 结构化提取、真实 embedding、PDF 成功/损坏 golden case、外部 embedding 短暂不可用后的 RabbitMQ 自动恢复、任务 SSE HTTP 多连接、单实例事件序号/有限回放及终态内存清理、本机 Edge Playwright L3 旅程。生产业务库已完成任务/MCP 迁移，真实模型 Agent 工具调用、模型驱动会话临时范围收窄和 MCP `STREAMABLE` 协议调用已通过；图片/OCR、多实例 SSE 与持久化恢复仍待验收。

## 1. 文档定位与范围

本 Spec 是总计划的实现级约束，定义需求到测试的追溯、测试优先开发方式、隔离环境和验收证据。总计划负责阶段目标、优先级和退出条件；架构文档负责当前实现与源码导航；本 Spec 负责“在当前阶段具体要实现什么、先写什么测试、如何证明通过”。

当前版本保留 G0 验收事实，并已记录 G1 的 owner-only 知识库硬权限、任务状态机、异步摄入、幂等、重试、脱敏任务进度查询及前端 SSE/轮询兜底实现。隔离真实数据库、队列、HTTP/JWT、MCP、advisory lock、文件补偿、Markdown/HTML embedding、外部 embedding 自动恢复、已认证任务 SSE HTTP 多连接、浏览器 Bearer SSE 连接后事件、真实模型 Agent 工具调用和生产 MCP 主体协议均已补齐对应证据；G2-G5 不构成提前开发授权。

不新增平行 Spec。RAGAS 指标作为本 Spec 的专项章节维护；任务、Router、记忆、Webhook、并发和浏览器 E2E 的实施契约同样在本文件持续补充。

## 2. SDD 需求追溯

每项实现必须从“阶段交付 -> 行为契约 -> `TC-ID` -> 测试代码/报告”单向追溯，并可从报告反查需求。未绑定 `TC-ID` 的需求不得进入实现。

| 阶段 | 实现级契约 | 必需测试入口 | 当前状态 |
| --- | --- | --- | --- |
| G0 | 聊天、RAG、SSE、审批在隔离环境可观测且可回归；不改变现有公开 API。 | `TC-G0-01` 至 `TC-G0-06` | 2026-08-18 已完成全部 G0 必需证据；当时 G1 尚未开始。 |
| G1 | owner-only KB 硬权限、Agent 默认范围关系表、任务状态机、异步摄入、幂等、重试和任务轮询均必须有确定状态与隔离边界。 | `TC-G1-01` 至 `TC-G1-10` | 已完成隔离 L2 的 HTTP/JWT、真实队列重试/死信、MCP 主体授权、advisory lock、文件补偿、Rabbit 消费数据库失败恢复、Markdown/HTML/PDF 结构化提取与真实 embedding、PDF 成功/损坏 golden case、外部 embedding 短暂不可用后的 TTL/DLX 自动恢复、已认证任务 SSE HTTP 多连接、单实例事件回放与终态内存清理、真实模型 Agent 工具调用和生产 `STREAMABLE` MCP 协议；Edge L3 已覆盖登录、上传、轮询、隔离和失败提示。模型驱动会话范围收窄已通过；图片/OCR、多实例 SSE 与持久化恢复仍待对应验收。 |
| G2 | Router 必须输出受限 schema，并按权限、证据与用户授权决定检索或拒答。 | `TC-G2-01` 至 `TC-G2-05` | `RagRouterTest` 已固定确定性 schema、授权/无证据/外部许可拒答和多模态 route；模型 Router、真实链路接入与评测数据仍待实现。 |
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

**授权判定。** `KnowledgeBaseAccessService` 是服务端唯一 owner 判定入口：当前用户 ID 必须与 KB owner 相等。KB 列表、CRUD、文档 CRUD、上传、Markdown 索引及文档删除的 chunk 索引清理均在副作用前校验；无 owner、缺失或非 owner 均返回统一拒绝，不暴露 KB 是否存在。当前没有 tenant、成员、共享 ACL 或角色模型，因此它们不构成任何隐式放行规则。

**三层范围。**

1. 后端硬权限是当前用户 owner 范围，不能由 Agent、会话或工具参数扩大。
2. `agent_knowledge_base` 是 Agent 默认范围；API `allowedKbs` 只是其读写投影，不是 JSONB，也不是资源授权模型。创建和更新时，每个传入 ID 必须存在、去重且属于当前用户；显式空数组允许保存并表示无私有 KB 检索范围，未传该字段的局部更新保留既有关系绑定。
3. 会话上下文和 `KnowledgeTool` 传入的 `kbIds` 只能从 Agent 已注入的默认范围继续收窄。`JChatMindFactory` 在运行时再次校验 Agent owner，从关系表读取绑定并过滤已删除或失权 KB。

**MCP 边界。** MCP V1 代码只从服务端解析有效主体凭据指纹到单一内部 `user_id`，再走同一 owner-only 判定；不得信任 MCP 参数中的 `kbIds` 或调用方自报的用户 ID。生产库已执行 MCP 迁移并创建主体 `principal_id=1` 到 `user_id=10` 的单一有效 grant；服务端显式使用 `STREAMABLE` `/mcp` 协议，过滤器覆盖该根端点及其子路径。在无有效主体、无有效 grant 或 KB 越权时，私有 KB 检索不进入 RAG。

**本地历史数据决策。** 依据 [2026-05-29-eval-improvements.md](../archive/2026-08-15/records/rag/2026-05-29-eval-improvements.md) 的四份 fixture，保留 `RAG Recall Fixture KB`（`a57df226-122b-481f-992a-935c5cc72a81`）的 4 条文档、32 个 chunk；其 owner 为测试账号 `g0l3_20260817_114535`（`user_id=9`）。账号已存在，未读取、未记录或写入密码、JWT 或其他凭据。此前的 G0 L3 KB、文档和 Agent 已删除，该账号只保留为这套评测 KB 的 owner，不能再将 G0 L3 资源视为可复用 fixture。按 Ban 确认，另 17 个候选 KB 与其 46,979 条文档、49,152 个 chunk、候选文件目录已清理；当前关系表无遗留绑定。

**非目标与后续迁移。** 当前没有 tenant、共享 ACL、角色授权、反向查询、完整绑定历史审计或 KB 删除时文档/chunk/文件的通用应用层物理级联。`agent_knowledge_base` 已提供当前绑定的数据库级级联，不等于共享授权或完整审计。出现共享、角色授权、tenant 或审计需求时，必须先扩展授权数据模型；API `allowedKbs` 投影不能承担授权、审计或级联语义。受控 fixture `Recall@5=1.0` 仅证明 gold chunk Top-5 覆盖和检索链路可回归，不证明真实 RAG 效果、真实权限隔离、引用准确性或答案忠实性。

**MCP 身份映射（代码已实现，生产迁移已执行）。** [2026-08-18-create-mcp-principal-access.sql](../../sql/mcp/2026-08-18-create-mcp-principal-access.sql) 定义四张表：`mcp_principal`（稳定外部主体与启停状态）、`mcp_principal_credential`（主体、凭据指纹/版本、有效期和撤销状态，明文只留在受控密钥系统）、`mcp_principal_user_grant`（V1 每个主体至多一条未撤销的内部 `user_id` grant，含审批者、授予/撤销时间和原因）和 `mcp_access_audit`（追加记录主体、解析出的用户、动作、目标 KB、决定、关联 ID、时间和脱敏请求元数据）。`McpApiKeyFilter` 仅以 `X-API-Key` 的 SHA-256 指纹解析启用主体和有效 grant，并传播主体与关联 ID；`McpKnowledgeTool` 以该 `user_id` 走 owner 校验。认证和知识检索的允许/拒绝均追加审计，审计不存原始凭据或查询正文。当前无 tenant 模型，V1 不伪造 tenant 字段；审计查询/保留，以及未来 tenant/共享 ACL 的独立 grant 迁移仍不在本次范围。

**Ban 已确认的后续实施选择（2026-08-18）。** 共享维持 owner-only，不在 MCP V1 或删除任务中引入 tenant/ACL。MCP 不再把共享 API Key 当作可审计主体：每个 `mcp_principal` 必须有独立、可轮换、可撤销的凭据，生效时只映射一个内部 `user_id`。KB 删除的目标语义为：提交时做 owner 判定并写审计，事务内清理数据库文档/chunk 和绑定，文件清理由可重试、幂等的异步任务完成；在该专用删除任务落地前不得宣称已实现。验收需新增第二隔离账号的 L2 跨用户验证与 Playwright L3。其他环境只能先产出 owner 认领清单、人工确认后再执行迁移，不能自动回填。

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

**处理与文件范围。** HTTP 请求不执行解析或 embedding。RabbitMQ 消费者领取任务后调用默认处理器：先定位同一 KB 的文档、读取受控存储路径，再在数据库事务内清理该文档旧 chunk、构造 metadata、embedding 并写入新 chunk；任一 `insert` 返回非正数即抛业务异常，使本次数据库替换回滚。Markdown 保持已有章节语义；HTML 使用标题结构解析并保留路径 metadata；PDF 使用 PDFBox 逐页提取非空文本，并把 `pageNumber` 写入 metadata；`txt` 或无章节内容作为单个原文 chunk。损坏或无可提取文本的 PDF 返回稳定业务错误，再由既有重试/死信状态机处理。消费者在 `RUNNING`、`SUCCEEDED`、`RETRYING`、`DEAD_LETTER` 发布脱敏进度事件；`/sse/ingestion/{taskId}` 先经任务 owner 校验，SSE 帧带单调 `id`，以任务级锁串行化回放、连接注册和实时发送；每任务最多保存 64 条事件，终态且无连接 30 分钟后在后续任务活动时清理，可对 `Last-Event-ID` 在本进程有限历史中回放。独立 HTTP/JWT 验收已覆盖同任务多连接接收和跨 owner 无资源泄露；多实例或进程重启恢复仍未覆盖。

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

定向 GREEN 命令：`cd backend_v2; .\mvnw.cmd -q "-Dtest=DefaultIngestionTaskProcessorTest,DocumentFacadeServiceImplTest,IngestionTaskServiceImplTest,IngestionTaskControllerTest,IngestionTaskStateMachineTest,IngestionTaskLifecycleTest,RabbitIngestionTaskPublisherTest,IngestionTaskMigrationContractTest,IngestionTaskPersistenceContractTest,IngestionTaskSpringWiringContractTest" test`，已退出 `0`。前端执行 `node ui/tests/document-upload-idempotency.contract.mjs`、`node ui/tests/ingestion-task-progress.contract.mjs`、`npm.cmd run lint`、`npm.cmd run build` 均退出 `0`；lint 只输出 `baseline-browser-mapping` 数据新鲜度提示，build 的 bundle 体积提示不等于 lint 结果。所有这些验证不读取本地敏感配置，不访问真实数据库、模型或网络。

**2026-08-19 运行时 L2/L3 证据。** 在不读取本地敏感配置、不写业务库且禁用外部模型的前提下，独立 PostgreSQL、独立 RabbitMQ vhost 和 `18080` 隔离后端执行五份 G1 迁移。真实 HTTP/JWT 证明 B 不能读取、更新、删除、上传 A 的 KB/文档，也不能绑定 A 的 KB；越权与不存在资源的拒绝相同且不返回资源身份或内容。A 的同资源幂等重放返回相同文档/任务，跨 KB 重用键拒绝；不支持 PDF 经真实消费者进入 `RETRYING`，三次尝试后为 `DEAD_LETTER` 且隔离 DLQ 可见消息。删除 KB 后不存在 orphan 文档、chunk、任务或绑定。两个实际缺陷均经 RED 后最小修复：PostgreSQL advisory lock 查询不能映射为 `void`，Rabbit JSON 字符串 UUID 必须在消费者解包。补充的 `G1FactoryKnowledgeToolRuntimeL2Test` 在独立 PostgreSQL/RabbitMQ、完整 Spring 上下文和真实 `JChatMindFactory` 中，以 `agent_knowledge_base` 关系数据装配运行时，再从 Factory 生成的本地 `KnowledgeTool` 回调获取实际工具实例：A 只注入 A1/A2，B 的 KB 不进入运行时；空绑定与 B 的 `kbIds` 在调用 RAG 前返回统一不可检索结果；会话 A1 默认收窄，A2/B 请求只解析为 A2。该测试先 RED 暴露 `chat_session.user_id BIGINT` 与 Factory 传入字符串参数的 PostgreSQL 比较错误，`ChatSessionMapper.selectByIdAndUserId` 改为显式 `BIGINT` 转换后 GREEN（Surefire `1 test, 0 failures, 0 errors`）。

**2026-08-21 真实模型、生产 MCP 与成功路径证据。** 生产业务库已实际存在 `agent_knowledge_base`、`ingestion_task`、`mcp_principal`、`mcp_principal_credential`、`mcp_principal_user_grant` 和 `mcp_access_audit`，主体 `principal_id=1` 仅有一条至 `user_id=10` 的有效 grant；凭据轮换只写入 SHA-256 指纹，原始值未写入仓库、文档或日志。真实外部聊天模型驱动隔离 Agent 完成 `KnowledgeTool` 调用，消息顺序为 `user -> assistant(tool call) -> tool -> assistant`；真实 HTML 摄入生成两个结构化 chunk 和非空 1024 维 Ollama embedding，最终回答包含 `Codex Runtime Guide > Tool Calling` 路径和精确标记。服务端显式配置 Spring AI `STREAMABLE` 协议，`/mcp` 的 `initialize`、`tools/list` 和 `mcpKnowledgeQuery` `tools/call` 均返回成功；无效或缺失凭据仍由最高优先级 Filter 返回 `401`，认证与知识查询审计追加成功。该证据不扩展为模型驱动会话范围收窄、多实例 SSE、重连恢复或跨组件故障恢复。

**2026-08-21 PDF golden case 与单实例终态清理证据。** `G1IngestionSuccessRuntimeL2Test` 在独立 PostgreSQL、RabbitMQ vhost、上传目录和本机 Ollama `bge-m3:latest` 中，以测试内生成的两页 PDF 先验证直调处理器：两个 chunk、非空 embedding 和 `pageNumber=1/2`。真实 Rabbit 消费另验证两个 `sourceType=pdf`、`pageNumber=1/2`、非空 1024 维 embedding 的 chunk，任务最终为 `SUCCEEDED`。同一隔离链路以损坏 PDF 验证真实 PDFBox 解析失败后首次进入 `RETRYING(1)` 并有 retry queue 消息；测试手工重投到真实消费者后依次观察 `RETRYING(2)` 和 `DEAD_LETTER(3)`，DLQ 有消息，document/task/物理文件始终各为单份且 chunk 为 0。该用例未验证 retry TTL/DLX 自动回投。脱敏运行摘要为 `backend_v2/target/g1-runtime-l2/pdf-ingestion-l2-summary.txt`。`IngestionTaskProgressServiceTest` 固定单实例内存边界：终态、无连接且超过 30 分钟的任务会在下一次活动时清理 latest/history/sequence/lock。PDFBox 首次字体扫描会对本机损坏字体发出警告，不影响通过结论。

**2026-08-19 浏览器 L3 证据。** 新增 `ui/playwright.config.ts` 与 `ui/tests/g1-runtime.spec.ts`，使用本机 `msedge` channel，不下载 Chromium。Edge 对隔离 UI/后端执行登录、KB 创建、PDF 上传、轮询至 `DEAD_LETTER`、当前 KB 切换、跨账号直达拒绝无泄露以及取消/重试双击冲突的实际错误提示；截图和 HTML 报告只写入 `backend_v2/target/g1-playwright/`。该旅程先因缺少项目 Playwright 配置及“新建 KB 后详情页使用陈旧列表”失败，后者通过详情路由变更时刷新 KB 列表修复后转绿。它不是 Chromium 冒烟、静态契约、lint 或 build 的替代描述。

**2026-08-20 成功摄入与进度发布证据。** 独立 PostgreSQL、独立 RabbitMQ、独立上传目录和本机 Ollama 中，`G1IngestionSuccessRuntimeL2Test` 先 RED 暴露 HTML 标题未解析：任务成功却只生成一个原始 HTML chunk，结构化断言预期两个。最小 GREEN 仅新增 HTML 标题解析入口；随后同一测试类的 Markdown 直调、Markdown Rabbit、HTML Rabbit 三项均为 `0 failures, 0 errors`。两种格式都产生两个带 section metadata 的 chunk、非空 1024 维 embedding 和持久化物理文件；真实消费者完成后，`IngestionTaskProgressServiceImpl` 的最终事件为 `SUCCEEDED`。脱敏摘要位于 `backend_v2/target/g1-runtime-l2/ingestion-success-summary.txt`，对应隔离容器和目录已删除。

**2026-08-20 任务 SSE HTTP 证据。** `G1IngestionTaskSseHttpRuntimeL2Test` 使用独立 PostgreSQL、嵌入式 HTTP、项目 `TokenInterceptor`、真实 JWT 和两个同 owner 客户端。它先 RED：两条连接均收到 `QUEUED`，发布 `RUNNING` 后首条连接五秒超时，根因是进度服务每任务仅存一个 emitter，第二条连接覆盖第一条。最小 GREEN 改为每任务并发 emitter 集合并按连接完成、超时、错误和发送失败精确注销；两项 GREEN 为 `0 failures, 0 errors`，两条授权连接均收到 `RUNNING`，另一 owner 的响应不含任务、KB 或文档标识。脱敏摘要位于 `backend_v2/target/g1-runtime-l2/sse-http-summary.txt`，隔离容器和原始报告已删除。

**2026-08-20 浏览器任务 SSE 证据。** Edge 在真实隔离后端、独立 PostgreSQL/RabbitMQ 与真实 Bearer JWT 上执行 `node .\node_modules\@playwright\test\cli.js test tests/g1-runtime.spec.ts --project=edge --grep "retry progress published"`。RED 先将第二条 SSE 建立在 `DEAD_LETTER` 后，再通过页面真实重试触发 Rabbit 消费；15 秒后该流仍仅为 `["DEAD_LETTER"]`，而轮询 UI 已为 `RETRYING`，表明 Spring 误选三参消费者构造器并使用空进度服务。最小修复将 `@Autowired` 放到四参生产构造器，保留三参构造器给既有单测；`subscribeIngestionTaskProgress` 以 Bearer `fetch + ReadableStream` 解析 `ingestion-progress`，KB 切换、组件卸载和任务终态均由 `AbortController` 清理。洁净重建时临时 schema 缺少既有 UUID 主键默认值而阻止 KB 创建；仅在该临时库补齐默认值后重跑同一用例，实际退出码 `0`，预先建立的第二条流收到后续 `RUNNING`，浏览器连接后事件 GREEN 签收。脱敏结论为 `backend_v2/target/g1-runtime-l2/ui-sse-browser-summary.txt`。

**仍未覆盖。** 图片/OCR 与表格解析，以及 SSE 多实例分发和持久化恢复仍待独立验收。进度服务当前为单实例内存状态：前端已支持事件序号和 `Last-Event-ID` 重连，服务端只回放当前进程的有限历史。`TC-G1-06` 覆盖真实 Rabbit 消费入口在受控 PostgreSQL 数据库失败时的处理器事务回滚、重试和死信；`TC-G1-10` 覆盖外部 embedding 短暂不可用后的 TTL/DLX 自动恢复，但不外推为其他外部依赖故障。生产业务库迁移、主体创建和 MCP 协议调用已完成，不应再写作“未执行”。文件系统不参与数据库事务，KB 删除的受控应用层文件异步清理仍是后续任务。

### 3.1.4 G2 原生 BM25、改写校准与多模态证据契约（待实施）

**范围与非范围。** G2 只把现有标题/正文 BM25 从 JVM 全量候选计算迁为 PostgreSQL 原生倒排索引，并校准查询改写、RRF、Router 接线和证据资产模型；不重写已存在的 pgvector 向量检索、owner-only 授权模型或标题精确/包含/关键词/Trigram 行为。RAG-Anything 仅作为多模态摄入和证据关系的设计参考，不引入其 LightRAG 图谱、Python 运行时或通用多 Agent 架构；用户长期记忆也不属于本阶段。

**唯一 provider 原则。** 首个隔离 PoC 优先验证 ParadeDB `pg_search`，同时以 VectorChord-bm25 作为备选进行同口径比较。落地前必须记录目标 PostgreSQL 版本、扩展版本、pgvector 镜像兼容性、许可证、建索引/重建时长、删除传播、备份恢复和 `EXPLAIN` 证据；通过后只保留一个生产 provider。不得把两个插件同时部署到生产，不得采用长期双读/双写，也不得把 JVM BM25 与数据库 BM25 的结果混合为“兼容层”。

**数据与 Mapper 契约。** `chunk_bge_m3` 是检索事实的唯一来源。若 provider 需要搜索字段投影或索引表，投影由同一摄入事务随 chunk 写入/删除，带 `chunkId` 与 `indexVersion`，禁止独立业务写入口。BM25 Mapper 入参必须是已通过授权收窄的 `kbIds`、原问或受控 standalone query、`HARD` 上下文字段与候选上限；`kb_id`、`sourceName`、`sourceType`、规范化 `contentPath` 过滤都在数据库 `LIMIT` 前完成。返回最少包含 `chunkId`、通道名、通道内 rank、可选 lexical score 和展示必需字段。应用只能将 rank/provenance 送入 RRF/rerank，禁止直接把 BM25 score 与 pgvector distance 相加。

**改写与融合契约。** 原问为不可删除的主 query；只有低信息 follow-up 且存在已授权会话证据时，才能产生最多一个 standalone 补全 query。原问和补全 query 都需要记录来源、触发原因和超时/失败回退；改写失败、超时、空输出或范围不一致时只能回退原问。标题精确通道继续只使用原问，避免标题稳定性被改写污染；正文 BM25 是否消费补全 query 必须由冻结评测证明收益。RRF 使用通道内 rank 而不是异构原始分数，并在每个最终候选保留 `channel/query provenance`，以便诊断某个改写或通道的影响。

**范围、候选与会话契约。** 必须在实施前冻结“未传 `kbIds`”的产品语义：推荐默认搜索该 Agent 的全部已授权 KB，会话 context 仅作为排序偏置；若需要 sticky scope，必须由显式会话或用户选择收窄，不能隐式采用上次 Top-1 的 KB。`FOLLOW_UP` 至少需要代词/续问标记与已授权上下文，短新标题、代码标识符和 API 路径仍保留标题通道；只有受控层级路径可进入导航 auto-context。RRF 后必须在 rerank 前按明确预算截断候选，rank penalty 必须有界或移除，避免深层精确命中数学上不可能升序。保存 `retrievalContext` 前必须满足相关性阈值及 Top-1/Top-2 gap；无答案、拒答和低置信结果不得更新会话 context。

**Router 与证据资产契约。** Router 只能在 `KnowledgeTools`/MCP 已收窄的可访问 KB 范围内输出计划，不能自行访问未授权 KB 或外部工具。`ABSTAIN`、`CLARIFY`、`EXTERNAL_TOOL` 需在真实入口生效，而非仅由 Router 单测断言。非文本能力增加前，先定义资产的 `assetType`、文档 ID、页码/坐标、关联 chunk、内容哈希、解析版本和状态；回答引用须可回跳资产及关联文本。当前 PDF 文本/页码并不等价于图片、表格或公式检索已实现。

| TC-ID | 先写的失败测试 | GREEN 与边界 |
| --- | --- | --- |
| TC-G2-02 | 隔离 PostgreSQL 中，原生 BM25 对标题/正文精确术语不能返回正确 chunk，或 Provider 未按 `kb_id`/上下文过滤。 | 真实扩展索引返回预期 Top-N；`EXPLAIN` 和大于 fixture 的冻结语料证明不调用 `selectLexicalCandidatesByKbIds` 全量扫描 JVM；仅比较候选排名，不将插件 score 和 vector distance 相加。 |
| TC-G2-03 | `HARD` context 外的高分 lexical chunk 挤掉 context 内 gold，或不同 owner/Agent/会话范围出现结果。 | 所有 BM25 过滤在 `LIMIT` 前；范围内 gold 可进入候选，范围外内容和元数据均不返回。 |
| TC-G2-04 | 原问被改写替换、改写失败改变召回、多个改写通道等权放大，或标题 exact 因改写退化。 | 原问始终保留，最多一个受控补全 query；输出保留 provenance；改写失败稳定回退；标题通道不使用改写 query。 |
| TC-G2-05 | `RagRouterTest` 通过但真实工具入口仍固定检索，或 Router 触发未授权外部访问。 | 从 `KnowledgeTools` 与 MCP 入口验证路由、拒答、外部许可和范围收窄；与固定链路的质量/p95/token 成本报告可复跑。 |
| TC-G2-06 | 图片、表格或公式只能产生无位置的文本，或引用不能回到原文元素。 | 每种已启用资产类型独立通过解析、幂等、授权、召回和页码/坐标引用；未启用类型保持未实现状态。 |
| TC-G2-07 | 默认多 KB 搜索被上次 context 隐式收窄，短新标题/代码标识符被误判 follow-up，或 `/api/...` 被误判导航。 | 默认范围符合冻结产品语义；仅显式 scope 可缩窄；标题通道和导航上下文只在对应信号满足时启用。 |
| TC-G2-08 | RRF 第 35 名的精确候选因线性 penalty 无法升序，重复通道被重复投票，或低相关 Top-1 污染下一轮。 | rerank 截断/有界 penalty 可验证；同源通道组内校准；低置信和无答案不更新 retrieval context。 |

**G2 阶段门禁。** 在冻结集的标题、正文精确匹配、中文/代码术语、multi-turn follow-up、topic switch、无答案、越权及 PDF 页码 case 上，现有主要召回指标不得退化；新增正文 BM25/standalone query/Router 必须报告分组收益、p95、token 成本、数据和索引版本。只有当所有授权/拒答测试通过且收益可复现时，才能切换唯一 provider；否则保留现有已签收链路并记录失败原因，不把 PoC 标记为上线。

### 3.2 后续阶段的测试先行要求

G1 起，每个 `TC-ID` 在实现前必须补充测试类/文件、方法名、固定 fixture、RED 预期失败和 GREEN 回归命令。G1 的 Playwright 用例先失败后再实现 UI；G2-G5 的路由、状态机、签名、并发和恢复逻辑先以 L0/L1 契约测试固定，随后再补 L2/L3 集成验证。

每次 RED/GREEN 证据写入总计划的逐用例验收台账：记录数据/配置版本、命令或报告路径、执行日期、执行人和结论。仅在测试确实失败过且失败原因符合预期时，才允许进入生产实现。

### 3.3 测试数据与副作用边界

- L0 使用受控 fixture，不启动 Spring 或外部服务。
- L1 对邮件、MCP/Web 使用替身；不得断言替身自身行为而忽略业务结果。
- L2 只连接隔离 Docker 中的 PostgreSQL、Redis、RabbitMQ 和 Ollama，使用可清理测试账号与知识库。
- L3 从 G1 起使用 Playwright；截图和报告写入构建产物，不提交生产数据或凭据。
- 默认报告路径为 `backend_v2/target/surefire-reports/`、`backend_v2/target/rag-eval/` 和 Playwright 报告目录。

#### 3.3.1 G0 L3 隔离资源的历史记录与当前状态（2026-08-17）

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
