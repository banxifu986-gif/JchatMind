# 工具 Harness

> 页面定位：工具执行的安全链路——白名单、重复调用拒绝、熔断、人工审批、审计、代理执行与线程池取消语义。动工具安全、审批、新工具接入前必读。
>
> 最后核对：2026-11-19（specs/003 P1/P2/P3 落地后按源码核验）。

## 1. 定位

所有 ToolCallback（本地 + 外部）都由 `HarnessToolCallbackProxy` 包装，避免仅依赖 Agent prompt 判断权限——**任何新工具都不得绕过该代理**。执行链分两级（specs/003 P1 收口后）：

- **loop 层（批处理级）**：`HarnessRunner.beforeExecution`（单重载）做重复/白名单直接拒绝 + 审批闸门（`HumanApprovalInterceptor` 由 Runner 直调，同名工具合并审批）——审批是 loop 层一等阶段，不进入工具调用栈；批准的调用由 `AgentToolExecutor` 在独立有界线程池执行。
- **Proxy 层（per-call 级）**：守卫为 `HarnessToolCallbackProxy` 单一调用栈内的成对 hook——`before`（可拦截，熔断在此）→ 执行 → `after`（可改写返回结果）/`onError`/`onBlocked`（审计逐 call 记录，order=9000 链尾，所见即改写后最终结果）。上下文经 `ToolContext` 显式传递（`HarnessToolContexts` 单点组装，签名 `toolName|toolInput` 的 ConcurrentLinkedQueue 按 poll 消费）；上下文缺失一律显式失败 `[HARNESS_CONTEXT_MISSING]`，无静默降级。截断安全：finishReason=length 的响应其全部 toolCall 判废，不进守卫/审批/执行。
- **并行编排（specs/003 P3）**：`AgentToolExecutor.executeParallel` 对单轮多 call 无序发起（并发上限 = 线程池容量 4，池满延后提交）、结果按 toolCall 声明顺序回填、失败隔离（单 call 异常产错误 result，其余正常完成）、aborted 检查前置；批级超时/用户取消沿用 `TOOL_OUTCOME_UNKNOWN` 收口。单 call 轮次走原 `execute` 顺序等价路径。

审批/熔断/审计存储当前为**内存实现，服务重启丢失**（持久化属于后续演进项）。

## 2. 关键文件索引

包根 `backend_v2/src/main/java/com/kama/jchatmind/agent/harness/`：

| 文件 | 职责 | 行数 |
| --- | --- | --- |
| [HarnessRunner.java](../../backend_v2/src/main/java/com/kama/jchatmind/agent/harness/HarnessRunner.java) | 执行前决策（单重载）、等待审批、合成结果、截断判废（isOutputTruncated/recordDiscardedToolCalls） | 237 |
| [interceptor/HarnessInterceptorChain.java](../../backend_v2/src/main/java/com/kama/jchatmind/agent/harness/interceptor/HarnessInterceptorChain.java) | per-call 成对 hook：before 短路/after 链式改写/onError/onBlocked | 62 |
| [interceptor/CircuitBreakerInterceptor.java](../../backend_v2/src/main/java/com/kama/jchatmind/agent/harness/interceptor/CircuitBreakerInterceptor.java) | **order=50**（per-call，Proxy 内 before 返回拦截决策） | 78 |
| [interceptor/HumanApprovalInterceptor.java](../../backend_v2/src/main/java/com/kama/jchatmind/agent/harness/interceptor/HumanApprovalInterceptor.java) | loop 层审批闸门（Runner 直调，不实现 HarnessInterceptor），同名工具合并审批 | 103 |
| [interceptor/AuditTrailInterceptor.java](../../backend_v2/src/main/java/com/kama/jchatmind/agent/harness/interceptor/AuditTrailInterceptor.java) | **order=9000（链尾）**，记录改写后最终结果 | 139 |
| [circuit/CircuitBreaker.java](../../backend_v2/src/main/java/com/kama/jchatmind/agent/harness/circuit/CircuitBreaker.java) | CLOSED→OPEN→HALF_OPEN 状态机（全 synchronized） | 56 |
| [approval/InMemoryApprovalStore.java](../../backend_v2/src/main/java/com/kama/jchatmind/agent/harness/approval/InMemoryApprovalStore.java) | CompletableFuture 阻塞等待 | 133 |
| [audit/InMemoryAuditStore.java](../../backend_v2/src/main/java/com/kama/jchatmind/agent/harness/audit/InMemoryAuditStore.java) | per-session 上限裁剪 + getRecords 快照读 | 51 |
| [proxy/HarnessToolCallbackProxy.java](../../backend_v2/src/main/java/com/kama/jchatmind/agent/harness/proxy/HarnessToolCallbackProxy.java) | 所有回调的包装（成对 hook + callWithOutcome） | 108 |
| [HarnessToolContexts.java](../../backend_v2/src/main/java/com/kama/jchatmind/agent/harness/HarnessToolContexts.java) | ToolContext 单点组装（并发队列） | 42 |

入口：[HarnessController.java](../../backend_v2/src/main/java/com/kama/jchatmind/controller/HarnessController.java)（审批 API，按所属会话校验归属）。

## 3. 执行前决策（`HarnessRunner.beforeExecution`，单重载，specs/003 P1 后）

1. **链前直接拒绝**（不进守卫）：
   - 重复签名 → `REJECTED "[TOOL_DUPLICATE] 工具 %s 使用相同参数重复调用"`
   - 白名单外 → `REJECTED "[TOOL_NOT_ALLOWED] 工具 %s 不在当前回合允许集合内"`
2. 审批闸门（Runner 直调 `HumanApprovalInterceptor`）只对 decision==ALLOW 的 context 执行；`webhookSourceId` 参数携带 webhook 来源，供审批写 outbox。
3. 截断安全：`isOutputTruncated`（finishReason=length）→ `recordDiscardedToolCalls` 逐 call 审计并返回 `[TOOL_CALLS_TRUNCATED]` 判废消息（被截断的调用不进守卫/审批/执行）。

合成响应前缀约定（`classifyToolOutcome` 依据）：`[TOOL_...` / `[APPROVAL_...` → REJECTED；`"错误："` → FAILED。

## 4. 拦截器链

| order | 拦截器 | 行为 |
| --- | --- | --- |
| 50 | `CircuitBreakerInterceptor` | per-tool 熔断：连续失败 ≥3 → OPEN（记录 `openedAt`）；超过 `recovery-timeout-seconds`（60s）→ HALF_OPEN 试探放行；成功回 CLOSED。开路 → 合成 `"[CIRCUIT_...]"` 拒绝 |
| 100 | `HumanApprovalInterceptor` | 仅当 `human-approval.enabled` 且工具在白名单；**同一步同名工具合并**为一个审批请求（`callCount` 记数量，已有 PENDING 组复用同一 `approvalRequestId`）；webhook 来源时同步 `webhookApprovalOutboxService.enqueue`；组内全部 context 写 `ATTRIBUTE_APPROVAL_REQUEST_ID` + pending |
| 9000（链尾） | `AuditTrailInterceptor` | before 打点 / after 记录改写后最终结果 / onError / onBlocked 记录被拦截调用；带 `IntentDecisionMetadata`；store per-session 上限 1000 条裁剪 |

**审批等待**：`HarnessRunner.awaitApprovals`（L179-197）在 **Agent 线程内同步阻塞**，逐 pending `approvalStore.awaitDecision(requestId, timeoutSeconds)`（默认 300s）；`applyApprovalDecision`（L243-273）把同组 context 一起更新：APPROVED→allow / REJECTED→`"[APPROVAL_REJECTED]..."` / EXPIRED→`"[APPROVAL_EXPIRED]..."`。前端流程见 [10](10-SSE实时链路.md) §5 与 [15](15-前端架构.md)。

## 5. 代理执行与取消语义（specs/003 P1/P3 后）

`HarnessToolCallbackProxy.callWithOutcome(toolInput, toolContext)`：

1. `resolveContext`：从 ToolContext 显式读取 run 元数据（`HarnessRunMetadata`）与 per-call 队列（`HarnessToolContexts.signature(toolName|toolInput)` poll，并发队列）；任何缺失 → `IllegalStateException "[HARNESS_CONTEXT_MISSING]"`（**无 fallback、无静默降级**）。
2. 入口 `AgentToolExecutor.checkActive()`（取消标记检查，aborted 前置）。
3. `interceptorChain.executeBefore(context)`：首个拦截决策短路；被拦截 → `executeOnBlocked`（审计记录）→ 返回拦截原因字符串（manager 路径）或 `ToolCallOutcome.blocked`（Workflow/Skill 直调路径）。
4. 成功后再次 `checkActive()` + `executeAfter(context, result)`（链式改写，审计在链尾所见即最终结果）；异常 `executeOnError` 后 **rethrow 原异常**。

`AgentToolExecutor`：`ThreadPoolExecutor(0 core, 4 max, 60s keepAlive, SynchronousQueue, AbortPolicy)`，daemon `agent-tool-N`，**无排队**（满 → `TOOL_EXECUTOR_BUSY`）。执行前保存 `RequestContextHolder`，线程内恢复请求属性 + 绑定取消标记（`CURRENT`），退出清理。分片等待（50ms）：批级超时/用户取消先到先断 → `cancellation.cancelled=true` + `task.cancel(true)`——**已发出的外部操作无法由 Java 中断撤回**。并行路径（`executeParallel`）所有子任务共享同一批级 `Cancellation`。`@PreDestroy` 全部 active 置 cancelled + `shutdownNow()`。

## 6. 不变量与契约

1. 每个新工具必须经 `HarnessToolCallbackProxy` 包装（Factory `wrapToolCallback` 统一处理）。
2. 审批、熔断、审计必须保持同一条执行路径（拦截器链），不得为单个工具旁路。
3. 默认审批工具白名单：`sendEmail`、`databaseQuery`（yaml）；熔断白名单同。
4. 合成响应与真实响应同形（同前缀约定），持久化与 SSE 一致。
5. 审批请求归属校验在 `HarnessController`（按所属会话），不信任前端传入的 sessionId。

## 7. 配置项（`jchatmind.harness.*`，application.yaml L84-101）

| Key | yaml 值（默认） |
| --- | --- |
| `human-approval.enabled` / `.tools` / `.timeout-seconds` | true / **[sendEmail, databaseQuery]** / 300 |
| `circuit-breaker.enabled` / `.tools` / `.failure-threshold` / `.recovery-timeout-seconds` | true / 同上 / 3 / 60 |
| `audit.enabled` / `.max-records-per-session` | true / 1000 |

工具超时 30s 是 `JChatMind` 代码常量（`DEFAULT_TOOL_TIMEOUT_SECONDS`），**不可配置**。

## 8. 验证入口

`HarnessRunnerTest`、`CircuitBreakerTest`、`CircuitBreakerInterceptorTest`、`HumanApprovalInterceptorWebhookOutboxTest`、`InMemoryApprovalStoreTest`、`HarnessToolCallbackProxyPairedHookTest`、`HarnessToolCallbackProxyToolContextTest`、`TruncatedToolCallDiscardTest`、`AgentToolExecutorTest`、`AgentToolExecutorParallelTest`、`JChatMindToolGuardTest`、`JChatMindToolTimeoutTest`（均默认跑）

## 9. 关联文档

- 调用方与白名单来源：[03-Agent核心循环](03-Agent核心循环.md)、[05-意图路由与工具选择](05-意图路由与工具选择.md)
- 审批 SSE 与前端交互：[10-SSE实时链路](10-SSE实时链路.md)、[15-前端架构](15-前端架构.md)
- Skill/工作流的 Harness 复用：[12-Skill与工作流](12-Skill与工作流.md)
- 审批事件出站（webhook）：[02-认证鉴权与异步邮件](02-认证鉴权与异步邮件.md) §5
- 归档：[../archive/2026-08-15/plans/done/harness.md](../archive/2026-08-15/plans/done/harness.md)
