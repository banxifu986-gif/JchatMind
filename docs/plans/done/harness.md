# Agent Harness MVP 开发方案

> 合并自：`Agent-Harness机制开发方案.md` + `Harness代码Review收紧点.md`

## 概述

为 JChatMind 的 Agent 工具执行链路引入 Harness 机制，提供三类执行安全托底能力：

- 人工审批：高风险工具执行前暂停，等待用户确认
- 熔断器：同一工具连续失败达到阈值后临时阻断
- 审计追踪：结构化记录每次工具调用的输入、结果、耗时与结局

本期目标是后端 MVP，只处理执行安全，不处理事实型幻觉，不改前端审批卡片，不做数据库持久化。

本期边界：

- Harness 不是普通 Tool，不暴露给模型
- 所有本地与外部 `ToolCallback` 都要统一包装
- `JChatMind.execute()` 需要有 pre-flight 预判，不只是在 callback 层做透明代理
- 审批、熔断、审计默认采用单机内存实现，但接口先抽象，便于后续升级

## 范围

本期实现：

- 新增 `agent/harness` 基础包
- 在 `JChatMindFactory` 统一包装本地和外部 `ToolCallback`
- 在 `JChatMind.execute()` 增加 pre-flight、审批等待、synthetic tool response 合并
- 扩展 SSE 协议，增加审批事件
- 新增 Harness 审批控制器
- 新增 Harness 单元测试

本期不实现：

- 前端审批卡片与交互
- 幻觉优化、知识优先约束、反事实校验
- 数据库存储审批/审计记录
- 多实例一致性
- Tool 实现类改造
- 无关模块重构

## 核心架构

当前 Agent 执行主链路：

```text
ChatMessageController
  -> ChatMessageFacadeServiceImpl.createChatMessage()
  -> publish ChatEvent
  -> ChatEventListener.handle()
  -> JChatMindFactory.create()
  -> JChatMind.run()
      -> think()
      -> execute()
```

Harness 的实际接入点有两个：

1. `JChatMindFactory.buildToolCallbacks()` / `buildExternalToolCallbacks()`
   统一包装本地工具和外部 `ToolCallbackProvider` 产出的 callback，保证不绕过 Harness。

2. `JChatMind.execute()`
   在真正交给 `ToolCallingManager.executeToolCalls(...)` 之前，先执行 pre-flight：
   - 识别需审批的调用
   - 识别熔断中的调用
   - 等待审批结果
   - 将允许执行与不允许执行的调用拆分
   - 合并真实执行结果与 synthetic 结果

整体链路：

```text
JChatMind.run()
  -> think()
  -> execute()
      -> HarnessRunner.beforeExecution()
          -> HumanApprovalInterceptor
          -> CircuitBreakerInterceptor
          -> AuditTrailInterceptor(before)
      -> SSE 推送 TOOL_APPROVAL_REQUIRED
      -> HarnessRunner.awaitApprovals()
      -> ToolCallingManager.executeToolCalls() 执行允许放行的 tool calls
      -> HarnessToolCallbackProxy.after/onError()
      -> 合并 synthetic ToolResponse
      -> saveMessage() / refreshPendingMessages()
```

## 设计方案

### 一、拦截器框架

新增核心对象：

- `HarnessContext`
  表示单次工具调用上下文，至少包含：
  - `sessionId`
  - `agentId`
  - `userId`
  - `toolCallId`
  - `toolName`
  - `toolInput`
  - `stepNumber`
  - `attributes`

- `HarnessDecision`
  描述某个 tool call 的最终判定：
  - `ALLOW`
  - `PENDING_APPROVAL`
  - `REJECTED`
  - `EXPIRED`
  - `CIRCUIT_OPEN`

- `HarnessResult`
  聚合一轮 tool calls 的判定结果，按 `toolCallId` 建索引，并提供待审批列表。

拦截器接口：

```java
public interface HarnessInterceptor {
    void beforeExecution(HarnessContext context, HarnessResult result);
    void afterExecution(HarnessContext context, String toolResult);
    void onError(HarnessContext context, Exception exception);
    int getOrder();
}
```

`HarnessInterceptorChain` 按 `order` 排序，统一调度 before / after / onError。

### 二、人工审批流

审批相关抽象：

- `ApprovalStatus`
  - `PENDING`
  - `APPROVED`
  - `REJECTED`
  - `EXPIRED`

- `ApprovalRequest`
  - `id`
  - `sessionId`
  - `toolName`
  - `toolInput`
  - `callCount`
  - `status`
  - `createdAt`
  - `expiresAt`

- `ApprovalStore`
  - `createRequest(...)`
  - `approve(...)`
  - `reject(...)`
  - `getPendingBySession(...)`
  - `awaitDecision(...)`
  - `getRequest(...)`

第一版只提供 `InMemoryApprovalStore`，内部使用 `ConcurrentHashMap` 管理审批请求和等待句柄。

审批规则：

- 仅对 `jchatmind.harness.human-approval.tools` 命中的工具生效
- 同一 step 内按 `toolName` 聚合审批，请求只创建一次
- 用户批准后，该 step 内对应同名 tool call 全部放行
- 用户拒绝或超时后，生成 synthetic tool response

拒绝型返回文本固定为：

- `[APPROVAL_REJECTED] 工具 {name} 执行被用户拒绝`
- `[APPROVAL_EXPIRED] 工具 {name} 审批超时，未执行`

### 三、熔断器

熔断器抽象：

- `CircuitBreakerState`
  - `CLOSED`
  - `OPEN`
  - `HALF_OPEN`

- `CircuitBreaker`
  - `allowRequest()`
  - `recordSuccess()`
  - `recordFailure()`
  - `getState()`
  - `getFailureCount()`

- `CircuitBreakerRegistry`
  - `get(String toolName)`

第一版只提供 `InMemoryCircuitBreakerRegistry`。

规则：

- 仅对 `jchatmind.harness.circuit-breaker.tools` 命中的工具生效
- 连续失败达到阈值后切到 `OPEN`
- 恢复时间到后进入 `HALF_OPEN`
- HALF_OPEN 成功则回到 `CLOSED`
- HALF_OPEN 失败则重新回到 `OPEN`
- 审批拒绝和审批超时不计入失败次数

熔断时返回 synthetic 文本：

`[CIRCUIT_BREAKER_OPEN] 工具 {name} 暂时不可用，请稍后重试`

### 四、审计追踪

审计抽象：

- `ToolCallRecord`
  - `id`
  - `sessionId`
  - `agentId`
  - `toolCallId`
  - `toolName`
  - `toolInput`
  - `toolResult`
  - `success`
  - `outcome`
  - `errorMessage`
  - `durationMs`
  - `timestamp`
  - `stepNumber`

- `AuditStore`
  - `record(ToolCallRecord record)`
  - `getBySession(String sessionId)`

第一版只提供 `InMemoryAuditStore`，按 `sessionId` 保存列表，并按最大条数做 FIFO 淘汰。

`outcome` 至少区分：

- `SUCCESS`
- `ERROR`
- `REJECTED`
- `EXPIRED`
- `CIRCUIT_OPEN`

### 五、SSE 状态推送

现有 `SseMessage.Type` 基础上新增：

- `TOOL_APPROVAL_REQUIRED`

`SseMessage.Payload` 新增可选字段：

- `approvalRequestId`
- `toolName`
- `toolInput`
- `callCount`
- `expiresAt`
- `stepNumber`

状态推送时机：

- `AI_PLANNING`：`run()` 开始
- `AI_THINKING`：每次 `think()` 前
- `AI_EXECUTING`：每次 `execute()` 前
- `TOOL_APPROVAL_REQUIRED`：产生待审批时
- `AI_DONE`：完成或异常结束前

本期前端不改，只补后端协议。

### 六、配置项

```yaml
jchatmind:
  harness:
    human-approval:
      enabled: true
      tools:
        - sendEmail
        - databaseQuery
      timeout-seconds: 300
    circuit-breaker:
      enabled: true
      tools:
        - sendEmail
        - databaseQuery
      failure-threshold: 3
      recovery-timeout-seconds: 60
    audit:
      enabled: true
      max-records-per-session: 1000
```

## 文件变更清单

### 新增文件

`backend_v2/src/main/java/com/kama/jchatmind/agent/harness/`

- `HarnessProperties.java`
- `HarnessContext.java`
- `HarnessDecision.java`
- `HarnessResult.java`
- `HarnessRunner.java`
- `approval/ApprovalStatus.java`
- `approval/ApprovalRequest.java`
- `approval/ApprovalStore.java`
- `approval/InMemoryApprovalStore.java`
- `audit/AuditStore.java`
- `audit/InMemoryAuditStore.java`
- `audit/ToolCallRecord.java`
- `circuit/CircuitBreakerState.java`
- `circuit/CircuitBreaker.java`
- `circuit/CircuitBreakerRegistry.java`
- `circuit/InMemoryCircuitBreakerRegistry.java`
- `interceptor/HarnessInterceptor.java`
- `interceptor/HarnessInterceptorChain.java`
- `interceptor/HumanApprovalInterceptor.java`
- `interceptor/CircuitBreakerInterceptor.java`
- `interceptor/AuditTrailInterceptor.java`
- `proxy/HarnessToolCallbackProxy.java`

新增控制器：

- `controller/HarnessController.java`

新增测试：

- `agent/harness/...` 相关单测

### 修改文件

- `agent/JChatMind.java`
- `agent/JChatMindFactory.java`
- `message/SseMessage.java`
- `src/main/resources/application.yaml`

### 不改动文件

- 所有 Tool 实现类
- `ToolFacadeService` 及工具选择逻辑
- RAG、知识库、文档、用户记忆相关服务
- 数据库 Schema 与 MyBatis XML
- `ui` 前端代码
- `ChatClientRegistry` 与模型注册结构

## 验证步骤

1. 执行 `backend_v2/mvnw.cmd compile`
2. 执行 Harness 相关单元测试：
   - 审批创建、批准、拒绝、超时
   - 熔断状态流转
   - 审计成功、失败、拒绝、超时、熔断记录
   - `HarnessRunner.beforeExecution()` 聚合与判定
3. 手工触发 `sendEmail`
   - 收到 `TOOL_APPROVAL_REQUIRED`
   - approve 后继续执行
   - reject 后返回拒绝型 tool response
4. 手工触发 `databaseQuery`
   - 同样走审批流
5. 人工制造连续失败
   - 达到阈值后熔断
   - 恢复时间后允许试探
6. 调 `GET /api/harness/pending/{sessionId}`
   - 返回当前会话待审批列表
7. 回归普通工具链路
   - `KnowledgeTool`
   - `terminate`
   - 普通聊天消息链路

## 后续扩展

后续升级方向：

- 将 `ApprovalStore`、`AuditStore` 替换为数据库实现，实现可重启恢复
- 将 `CircuitBreakerRegistry` 替换为共享状态存储，实现多实例一致性
- 增加输入护栏与输出护栏拦截器
- 增加工具级速率限制
- 前端补审批卡片、待审批恢复与按钮交互

---

## 代码 Review 收紧点（已全部修复）

> 原文档 `Harness代码Review收紧点.md` 提出的 6 项问题。

### 中严重度（2 项，已修复 ✅）

1. **CircuitBreaker.recordFailure() 在 OPEN 状态重置恢复计时** — 已添加 `if (state != CircuitBreakerState.OPEN)` 守卫，仅在首次切换为 OPEN 时设置 `openedAt`。

2. **CircuitBreakerInterceptor.beforeExecution() 缺少非 ALLOW 决策守卫** — 已添加前置决策检查，与 `HumanApprovalInterceptor` 保持一致。

### 低严重度（4 项，已修复 ✅）

3. **InMemoryApprovalStore.complete() TOCTOU 竞态** — 已使用 `computeIfPresent` 做 CAS，仅在 status 仍为 PENDING 时写入。

4. **JChatMindFactory.harnessProperties 注入未使用** — 已移除冗余注入。

5. **HarnessToolCallbackProxy fallback 上下文字段缺失** — 已修复。

6. **"approvalRequestId" 属性键散落三处** — 已统一到 `HarnessConstants.ATTRIBUTE_APPROVAL_REQUEST_ID`。

### 主链路确认通过项

- 拦截器链按 order 升序调度，接口签名匹配
- 审批按 toolName 聚合，同名工具共享审批请求，批准后整组放行
- 审批/熔断拒绝文本与设计文档逐字一致
- 熔断 CLOSED → OPEN → HALF_OPEN → CLOSED 状态流转正确
- 审计 FIFO 淘汰（`synchronized` + `remove(0)`）
- SSE `TOOL_APPROVAL_REQUIRED` 类型及 Payload 字段齐全
- `JChatMind.execute()` pre-flight → awaitApprovals → synthetic 合并流程完整
- `HarnessExecutionContextHolder` bind/clear 配对，finally 确保无 ThreadLocal 泄漏
- 审批拒绝/超时不触发熔断失败计数

---

## 实现状态

- **状态**: ✅ 已完成
- **最后验证**: 2026-05-24
- **未完成项**: 无（本期 MVP 范围全部实现；后续扩展见上方"后续扩展"节）
