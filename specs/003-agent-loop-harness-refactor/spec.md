# Feature: Agent Loop 与 Harness 管线重构（借鉴 pi-main 设计：hook 语义统一 + 取消/截断/并行能力）

**Feature Branch**: [独立 spec 目录，与 git 分支解耦]
**Created**: 2026-11-19
**Status**: Draft
**Input**: D:\coding\pi-main `packages/agent/src/agent-loop.ts`（952 行）设计研读 + grill-me 评审收敛（Session 2026-11-19，10 项决策全记录见 Clarifications）

## 概述

当前 Agent 主循环全部内嵌在 `JChatMind`（919 行上帝类）：`run/step/think/execute` 循环骨架、SSE 推送、内存压缩、审批事件、工具执行与超时混杂；harness 守卫层存在三处结构性问题：before/after 触发点割裂（before 批量在 `execute()`、after 逐个在 `HarnessToolCallbackProxy`）、`afterExecution` 只能旁路观察不能改写结果、`HarnessExecutionContextHolder` ThreadLocal 传递存在"上下文丢失静默降级"路径。

借鉴 pi-main 的三个核心设计：**循环与策略分离**（`runLoop` 纯骨架 + `finishTurn/beforeToolCall/afterToolCall` 可配置 hook）、**统一事件出口**（类型化事件 + 单一 emit）、**单调用栈成对工具 hook**（`prepareToolCall → 执行 → finalizeExecutedToolCall`，粒度统一为单个 tool call）。本次改造把 pi 语义对位移植进现有 Spring AI 栈，并补齐三个缺失能力：用户取消、截断安全、并行工具执行。

**明确非目标**（本轮不做）：

- Steering/follow-up（运行中插入用户消息）——需消息队列与会话状态机配套，违反宪法原则 I 单机演示校准取向
- SSE 线上契约任何变化——`SseMessage` 类型、事件语义、`SseController` 手工 401 门卫逐字节保持
- 引入新框架（AgentScope）、升级 Spring AI（2.x 仍为顺序执行，字节码已验证；升级与本特性正交，记入备选）
- 取消由 SSE 断连联动触发（网络抖动误杀运行中 agent，风险大于收益）
- `WorkflowPlanExecutor` 自身编排逻辑的改造（仅适配共享的 hook 管线）

## Clarifications

### Session 2026-11-19（grill-me，10 项）

- Q: 借鉴动机？ → A: 架构一致性（统一到 pi hook 语义、解决上帝类）+ 能力补齐（取消/截断/并行）双驱动
- Q: 能力取舍？ → A: 取消 + 截断安全 + 并行进范围；Steering 排除（单机演示校准，宪法原则 I）
- Q: hook 统一深度？ → A: 方案 C——触发点收敛到 Proxy 成对 hook；审批留 loop 层（人的参与不藏在工具调用栈）；ThreadLocal → ToolContext 显式传递，fallback 静默降级改显式失败；`HarnessRunner` 5 个 telescoping 重载收敛为 1 个
- Q: 取消触发源与语义？ → A: 显式停止端点（SSE 断连联动不做）+ 步间协作检查 + 中断在飞工具（复用/扩展 `AgentToolExecutor` 现成 `Cancellation` 机制）
- Q: 并行放哪层？ → A: 自定义 `ToolCallingManager`（Spring AI 1.1.0 与 2.0.2 的 `DefaultToolCallingManager` 均已反编译验证为顺序执行，升级拿不到该能力）；结果按 toolCall 顺序回填（对齐 pi `executeToolCallsParallel` 语义）
- Q: 并行是否违反"不为多实例准备"约束？ → A: 不违反——同步保护的是"单会话内一轮并行"的数据一致性，不是多实例扩展
- Q: Spring AI 2.0 / AgentScope / 两者混搭？ → A: 全部否决（2.x 无并行、AgentScope 为整体换引擎、混搭需双框架适配胶水且无额外收益），记入本 spec 备选节
- Q: 循环抽取边界？ → A: 新 `AgentLoop` 持状态机/步数预算/事件出口/取消检查/工具编排；`JChatMind` 留 agent 定义 + prompt 组装（`buildThinkPrompt` 等）+ SSE 事件桥（内部 `AgentEvent` → 现有 `SseMessage`）+ 审批编排；内存压缩变 loop 的 next-turn hook
- Q: 分阶段？ → A: 三阶段（见阶段拆分节），每阶段独立交付并全量回归
- Q: `.claude/CLAUDE.md`「临时：线上限时迭代测试规则」节？ → A: 已过时，本次流程不受其约束，spec 备注该节可删

## 用户场景与测试

### 场景 1：工具调用被守卫拦截（hook 管线，阶段 1）

模型发起工具调用，某调用命中熔断/审批/白名单守卫而被拒绝。拒绝以结构化方式进入 tool result（模型可读到拒绝原因），调用栈内 before→执行→after 成对发生，审计记录完整。

**验收**：单测（stub LLM 返回含工具调用的响应）：before hook 返回 block 时工具实现零执行、tool result 携带拒绝原因；after hook 改写结果时模型收到的即改写后内容；每条 tool call 恰好一条审计记录。

### 场景 2：截断响应的工具调用判废（阶段 1）

LLM 响应因输出 token 上限被截断（finishReason=LENGTH），消息内携带的 tool call 参数可能不完整。

**验收**：单测（stub 响应 finishReason=LENGTH 且含 toolCall）：全部 tool call 被判废为错误 result（提示重新发起完整调用），无任何工具实现被真实执行——对齐 pi `failToolCallsFromTruncatedMessage` 语义。

### 场景 3：上下文显式传递（阶段 1）

工具执行期间守卫层需要的会话上下文（userId/agentId/sessionId/stepNumber）经 `ToolContext` 显式到达 Proxy，不再依赖 ThreadLocal。

**验收**：单测：`HarnessExecutionContextHolder` 静默降级路径移除后，上下文缺失时行为为显式失败（可观测的错误），而非 FALLBACK 标记静默继续；存量集成路径测试（Factory/Workflow/HarnessedSkill 三处 Proxy 构造方）不回退。

### 场景 4：用户停止运行中的 Agent（取消，阶段 2）

用户在 agent 多步执行中点击停止，后端 `POST /api/chat-sessions/{sessionId}/stop` 收到请求后：当前在飞工具调用被中断、循环不再进入下一步、SSE 流以明确的结束事件收尾、无资源泄漏（线程池任务清退）。

**验收**：单测（stub LLM 每轮都要求继续调工具）：发起 stop 后——在飞工具实现收到中断信号、循环步数停止增长、SSE 收到结束事件；重复 stop 幂等；对非本人会话 stop 返回 403 语义（`ApiResponse.code`）。

### 场景 5：一轮内多个工具调用并行执行（阶段 3）

模型单轮返回多个相互独立的工具调用，并行执行，总耗时应接近最慢一个而非串行累加；结果顺序与 toolCall 声明顺序一致；取消/超时语义在并行模式下依然成立。

**验收**：单测（两个 stub 工具分别 sleep 300ms，串行需 ≥600ms）：并行路径耗时 < 500ms（时钟断言，容忍调度误差）；结果列表顺序与 toolCall 顺序一致；并行执行中触发 stop，全部在飞调用被中断且结果列表完整（每个 call 都有 result）。

### 场景 6：并行下守卫层状态一致（阶段 3）

同一轮并行执行中，审计记录与熔断计数无丢失、无交叉污染。

**验收**：并发压测单测（如 8 工具 × 50 轮，`Executors` 驱动）：审计记录数 == 实际执行数、熔断计数正确递增；两个 InMemory store 的同步改造以最小实现为限（synchronized 或并发容器，不引入新依赖）。

### 场景 7：循环骨架可独立单测（阶段 2）

`AgentLoop` 不依赖 Spring 上下文、SSE、真实 LLM 即可单测：注入 stub 的轮次决策与工具执行器，驱动 20 步预算、正常结束、预算耗尽、中途取消四条路径。

**验收**：`AgentLoop` 单测零 Spring 依赖（纯 JUnit + stub 协作者）；`JChatMind` 既有行为由存量测试兜底不回退。

### 场景 8：线上契约与存量行为不回退（贯穿）

SSE 线上契约（事件类型、字段、顺序语义、`SseController` 401 门卫）、意图路由、MCP 知识工具、工作流执行、审批交互（`awaitApprovals` + SSE 审批事件 + webhook 回调）全部不变。

**验收**：`mvnw.cmd test` 全量基线通过（1106 项 0 失败口径，宪法原则 VII，门控跳过项口径不变）；前端 `npm run check` 通过（阶段 2 涉及停止按钮）。

### 边界场景

- stop 请求到达时 agent 已自然结束 → 幂等成功，无副作用
- stop 请求到达时工具执行恰好全部完成、进入下一轮 think 前 → 步间检查捕获，不发起下一次 LLM 调用
- 单轮仅一个工具调用 → 并行路径退化为与现状等价的单调用执行（性能与行为无回退）
- 上下文在 ToolContext 中缺失（如非 agent 链路误入 Proxy）→ 显式失败并日志定位，不静默降级
- 并行工具中某个调用抛异常 → 该调用产错误 result、其余正常完成、结果列表完整（不因单个失败丢弃整轮）

## 需求

### 功能需求

**阶段 1：hook 管线收口 + 截断安全**

- R1.1 `HarnessToolCallbackProxy` 内部实现成对 hook：执行前调用 before 守卫（可拦截并给出原因）、执行后调用 after（可改写返回结果）、异常走 onError；`HarnessInterceptor` 接口语义按此对位升级
- R1.2 审计/熔断/白名单守卫从"批量 beforeExecution"迁移至 Proxy 内单调用栈成对路径；`JChatMind.execute()` 与 `WorkflowPlanExecutor` 中的对应守卫调用点收缩
- R1.3 审批流保持现状：`HarnessRunner`/`awaitApprovals`/审批事件/webhook 回调行为不变（留在 loop 层）
- R1.4 工具上下文经 Spring AI `ToolContext` 显式传递；`HarnessExecutionContextHolder` ThreadLocal 及 FALLBACK 静默降级路径移除，缺失改显式失败
- R1.5 `HarnessRunner.beforeExecution` 的 5 个 telescoping 重载收敛为 1 个（调用方：`JChatMind`、`WorkflowPlanExecutor`）
- R1.6 截断安全：finishReason=LENGTH 的响应，其消息内全部 tool call 判废为错误 result（提示模型重新发起），不执行任何工具

**阶段 2：循环抽取 + 取消**

- R2.1 新建 `agent/loop/AgentLoop`：状态机、步数预算（沿用 20）、类型化事件出口、步间取消检查、工具执行编排（委托 ToolCallingManager/`AgentToolExecutor`）；零 Spring 上下文依赖
- R2.2 `JChatMind` 瘦身为：agent 定义 + prompt 组装 + SSE 事件桥（内部事件 → `SseMessage`，线上契约不变）+ 审批编排；内存压缩作为 loop 的 next-turn hook 挂载
- R2.3 新增停止端点 `POST /api/chat-sessions/{chatSessionId}/stop`（路由已定稿，与现有资源路由一致；wiki/01 接口总表已登记）：宪法原则 II 三层齐备（`@NeedLogin` + service 层 owner 校验；SecurityConfig 放行策略不变）；幂等
- R2.4 取消语义：步间协作检查 + 中断在飞工具（复用 `AgentToolExecutor` 现成 `Cancellation`，触发源从超时扩展为超时/用户取消二选一先到）；被取消的 run 以明确结束事件收尾
- R2.5 前端停止按钮接入停止端点（沿用现有交互模式，不引入新状态方案）

**阶段 3：并行工具**

- R3.1 自定义 `ToolCallingManager`（或等价的 per-call 编排层）实现并行执行：无序发起、结果按 toolCall 声明顺序回填；单调用轮次退化为顺序等价行为
- R3.2 并发上限固定为现有线程池容量 4，不新增配置项
- R3.3 `InMemoryAuditStore`、`InMemoryCircuitBreakerRegistry` 最小同步改造（synchronized 或并发容器，零新依赖），并附并发压测单测证明无丢失
- R3.4 取消/超时/守卫 hook 语义在并行模式下全部成立（与 pi 的 parallel 分支语义对齐：aborted 检查前置、失败不丢弃整轮）

### 约束

- 宪法原则 I（单机演示防御校准）：InMemory store 同步的唯一触发场景是"单会话内一轮并行工具"（本 spec 场景 6），不为多实例预置任何机制；Steering 类队列机制明确不做
- 宪法原则 II（鉴权三层）：停止端点三层齐备，公开端点锁定清单不变
- 宪法原则 III（错误契约）：stop 的业务错误（非本人/不存在）走 `ApiResponse.code`，HTTP 恒 200
- 宪法原则 V（最小精准修改）：各阶段改动限于本 spec 列出的类；发现无关问题只备注不顺手改
- 宪法原则 VI（依赖锁定）：零新依赖
- 宪法原则 VII（基线先行）：每阶段前后跑 `mvnw.cmd test` 全量基线；阶段 2 另跑 `npm run check`
- **TDD 纪律**：每个验收场景的测试从本 spec 场景/验收标准推导，在对应实现之前编写并先行失败（红→绿→重构）；MUST NOT 根据实现答案倒写测试；截断判定（finishReason 取值通道）等实现前提在 plan 阶段先做技术验证，验证结论回填 spec 后才允许出测试
- 附带备注：`.claude/CLAUDE.md`「临时：线上限时迭代测试规则」节已过时（Session 2026-11-19 确认不生效），可在本次收口时删除该节

## 成功标准

1. 工具调用守卫在单一调用栈内成对触发，after 可改写结果，审计逐 call 记录（场景 1）
2. 截断响应的工具调用全部判废，零误执行（场景 2）
3. ThreadLocal 与静默降级路径消除，上下文显式传递（场景 3）
4. 用户可停止运行中的 agent：在飞工具中断、循环停步、SSE 明确收尾、幂等、越权 403（场景 4）
5. 多工具调用并行执行且结果有序，守卫状态无丢失（场景 5/6）
6. `AgentLoop` 可脱离 Spring 独立单测（场景 7）
7. 全量基线与线上契约零回退（场景 8）；三阶段各自独立交付

## 关键实体

- **AgentLoop**（新）：循环骨架，pi `runLoop` 的 Java 对位物；状态机 + 预算 + 事件出口 + hook 挂载点
- **AgentEvent**（新，内部）：类型化事件（loop 生命周期/消息增量/工具执行/审批/结束），仅内部模型，映射到现有 `SseMessage` 后上线
- **HarnessInterceptor / Chain / Proxy**：守卫管线，重构后为 Proxy 内成对 hook 的实现载体
- **HarnessRunner**：审批编排入口，重载收敛，行为语义保持
- **AgentToolExecutor**：工具执行线程池与取消机制（现有），扩展用户取消触发源
- **停止端点**：`POST /api/chat-sessions/{sessionId}/stop`（命名以 plan 阶段对齐现有路由惯例为准）
- **pi-main 参照物**：`packages/agent/src/agent-loop.ts`（`runLoop`/`beforeToolCall`/`afterToolCall`/`finishTurn`/`failToolCallsFromTruncatedMessage`/`executeToolCallsParallel` 语义）

## 假设

- ✅ **已验证（Session 2026-11-19）**：Spring AI 1.1.0 `ChatGenerationMetadata.getFinishReason()` 通道存在；deepseek/zhipuai 两 provider 均有 `LENGTH` 枚举（反编译确认）；`JChatMind.streamChatResponse` 合成响应时已保留 chunk 的 generation metadata——截断判定通道现成，无需新增采集逻辑
- ✅ **已验证（Session 2026-11-19）**：`ToolCallingChatOptions.setToolContext(Map)` 在 1.1.0 存在，`DefaultToolCallingManager` 从 prompt options 构造 `ToolContext` 传入 `ToolCallback.call`；且 `JChatMind.chatOptions` 本就是 `DefaultToolCallingChatOptions`（155 行）——ToolContext 承载方案在现有调用链成立，含 Workflow 路径（同走 manager）
- ✅ **已验证（Session 2026-11-19）**：活跃工具清单 5 项——`databaseQuery`（JDBC 只读 SELECT）、EmailTools（HTTP）、KnowledgeTools（MyBatis/PG + reranker HTTP）、TerminateTool（无副作用）、McpKnowledgeTool（MCP HTTP）——均无文件锁/长驻原生操作，线程中断安全；JDBC 中断后服务端查询可能继续至完成，由超时语义托底，plan 记录该边界
- 现有 1106 基线中 harness 相关单测将在阶段 1 随接口语义升级同步改写，属预期内改动而非回退

## 依赖

- pi-main 源码（设计参照，只读）
- 现有校验入口：`mvnw.cmd test`、`npm run check`
- docs/wiki/01（接口总表：stop 端点需同步登记）、docs/wiki/02（鉴权约定）

## 备选方案（已否决，记录决策依据）

1. **升级 Spring AI 2.0.x**：`DefaultToolCallingManager` 字节码验证仍为顺序执行（无 ExecutorService/invokeAll 痕迹），付出 Boot 4/Framework 7 连带升级成本却拿不到目标能力；其 `ToolCallLimits` 等改进与本特性正交，留独立事项
2. **引入 AgentScope（整体替换）**：自带并行工具/中断/记忆管理，但属于换引擎而非借鉴，harness/intent/workflow/SSE 全量重写移植，且引入重依赖违反宪法原则 VI 的确认程序；若未来 pi 式自建维护成本失控，可重启独立 POC 评估
3. **Spring AI + AgentScope 混搭**：官方无 Spring AI 桥接模块（Maven 模块清单核查），需自写模型/工具双向 Adapter，成本高于自建且收益相同，为最差组合
4. **SSE 断连联动取消**：演示场景网络抖动误杀运行中 agent，风险大于收益；不做
5. **hook 统一浅改（保留 Chain 结构仅补语义）**：不解决 before/after 触发点割裂、粒度不一致、ThreadLocal 静默降级三个结构问题，收益不抵成本

## 阶段拆分（步骤 → 验收项）

| 阶段 | 步骤 | 验收项 |
| --- | --- | --- |
| P1 | 技术验证：finishReason 通道 / ToolContext 承载 / 工具中断安全盘点 | 验证结论回填 spec 假设节，不通过项触发需求降级评审 |
| P1 | TDD：按场景 1/2/3 先写失败测试 | 测试红（因功能缺失），且测试内容可追溯到本 spec 验收标准 |
| P1 | Proxy 成对 hook + 守卫迁移 + ThreadLocal 移除 + 重载收敛 + 截断判废 | 场景 1/2/3 单测绿；`mvnw.cmd test` 全量基线绿 |
| P2 | 技术盘点：停止端点路由命名对齐 wiki/01 登记惯例 | 端点名定稿并回填 spec |
| P2 | TDD：按场景 4/7 先写失败测试 | 同上 TDD 纪律 |
| P2 | `AgentLoop` 抽取 + `JChatMind` 瘦身 + 停止端点 + 在飞中断 + 前端停止按钮 | 场景 4/7 单测绿；后端基线 + `npm run check` 双绿；wiki/01 接口总表更新 |
| P3 | TDD：按场景 5/6 先写失败测试 | 同上 TDD 纪律 |
| P3 | 并行编排 + InMemory store 同步 + 并发压测 | 场景 5/6 单测绿（含时钟断言与压测）；`mvnw.cmd test` 全量基线绿 |
| 收口 | `.claude/CLAUDE.md` 临时规则节删除 + 文档同步 | 宪法/CLAUDE.md/架构文档一致性检查通过 |
