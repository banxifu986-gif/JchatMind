# Hook 管线契约（P1/P2/P3 共同遵守）

**Status**: Draft | **Scope**: `agent/harness/**` 与工具执行链路

## 1. 工具调用 hook 配对语义

单次 `ToolCallback.call(toolInput, toolContext)` 的执行栈内，按以下顺序成对触发，粒度恒为**单个 tool call**：

```
before(toolCall, args, context)
  ├─ 返回 BLOCK(reason)   → 工具实现零执行；返回错误 tool result（含 reason）；仍触发 after? 否 → 记审计(被拦截)
  ├─ 返回 PROCEED          → 执行工具实现
  │     ├─ 成功 → after(toolCall, result, context) → after 可改写 result（改写后返回给模型）
  │     └─ 异常 → onError(toolCall, exception, context) → 异常上抛由 manager 错误处理兜底
```

- before/after/onError 对同一 tool call 在**同一调用栈**内成对发生（对齐 pi `prepareToolCall → execute → finalizeExecutedToolCall`）
- after 的改写结果即模型最终收到的结果（pi `afterToolCall` 语义）；审计拦截器 order 置于链尾（9000），故审计记录的即改写后的最终结果
- 每个 tool call 恰好一条审计记录（含被拦截与被判废的）

## 2. 上下文传递契约

- 守卫所需上下文字段：`userId / agentId / sessionId / stepNumber / toolName / toolInput / intentDecisionMetadata`
- 传递通道：run 级注入 `ToolCallingChatOptions.toolContext` → `ToolContext` → Proxy 读取；**禁止** ThreadLocal
- 上下文缺失：**显式失败**（可观测错误 + 日志定位），不得静默降级继续执行
- 批量级元数据（如 intentDecisionMetadata）随 run 注入，不逐 call 重复

## 3. 截断判废契约

- 判定：合成响应的 generation metadata `finishReason`（deepseek/zhipuai 均输出 LENGTH 语义；实际字符串大小写以运行时实测为准，测试由 stub 控制注入）
- 行为：该响应消息内**全部** tool call 判废——每个 call 产错误 result（提示模型以完整参数重新发起），工具实现零执行
- 对齐 pi `failToolCallsFromTruncatedMessage`：判废的 call 仍计审计、仍发 tool_execution 事件（isError）

## 4. 审批边界（不变项）

- 审批是 loop 层一等阶段：`HarnessRunner.beforeExecution`（收敛后）产出待审批项 → `awaitApprovals` 等待 → SSE 审批事件 / webhook 回调放行
- 审批不进入工具调用栈的 before hook；Proxy 不做审批判断
- 存量审批行为（事件顺序、`HarnessController` 端点、webhook 放行）为锁定契约

## 5. 取消契约（P2）

- 触发源：`POST /api/chat-sessions/{sessionId}/stop`（幂等；非本人会话 → `ApiResponse.code` 403 语义）
- 语义：步间协作检查（不发起下一次 LLM 调用）+ 在飞工具中断（`AgentToolExecutor.Cancellation` 置位 + `task.cancel(true)`）
- 收尾：run 以明确结束事件收尾（SSE 既有类型内表达，不新增线上事件类型）；线程池任务清退无泄漏
- 触发源扩展点：超时 / 用户取消，先到先断

## 6. 并行契约（P3）

- 编排层：per-call 并行发起，**结果按 toolCall 声明顺序回填**（对齐 pi `executeToolCallsParallel`）
- 单 call 轮次退化为顺序等价行为；并发上限固定 4（现有池容量，不新增配置）
- 失败隔离：单个 call 异常 → 该 call 错误 result，其余正常完成，结果列表完整
- 取消/超时/hook 语义在并行下全部成立；aborted 检查前置（对齐 pi：signal.aborted 优先于 hook）
- 线程安全前提：`InMemoryAuditStore`、`InMemoryCircuitBreakerRegistry` 最小同步（零新依赖），并发压测单测托底
