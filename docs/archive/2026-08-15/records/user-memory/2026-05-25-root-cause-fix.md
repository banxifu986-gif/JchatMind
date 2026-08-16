# User Memory 根因修复记录 - 2026-05-25

## 背景

- 在回收 `schema mismatch` 临时止血逻辑后，继续审阅发现仍有一个残留根因：
  - `UserMemoryFacadeServiceImpl.getConfirmedMemories()` 仍可能被通用调用链复用，一旦在错误边界上处理不当，就会把数据库异常错误地转成空列表语义。
- 这类问题会同时影响：
  - 用户记忆查询接口的故障可见性
  - 长时记忆召回链路的降级边界
  - 自动持久化更新型记忆时的写入正确性

## 本次实际修复

### 1. 收紧 `getConfirmedMemories()` 语义

- 文件：`backend_v2/src/main/java/com/kama/jchatmind/service/impl/UserMemoryFacadeServiceImpl.java`
- 改动：
  - `getConfirmedMemories()` 不再吞数据库异常，改为直接抛出。
- 结果：
  - `/api/users/{userId}/memories` 不再静默返回 `200 + []` 掩盖数据库错误。
  - 通用读方法不再向写路径泄漏“空列表即成功”的错误语义。

### 2. 仅在召回链路内部保留降级

- 文件：`backend_v2/src/main/java/com/kama/jchatmind/service/impl/UserMemoryFacadeServiceImpl.java`
- 改动：
  - `recallRelevantMemories()` 内部保留 3 处定向 fallback：
    - `ragService == null` 时回退最近记忆
    - 语义结果不足时尝试补无 embedding 记忆
    - 语义召回整体失败时回退最近记忆
  - 如果 fallback 自身也失败，则记录 warning 并返回空列表或已拿到的部分语义结果。
- 结果：
  - 聊天主链路仍具备必要降级能力。
  - 降级边界被收紧在召回方法内部，不再污染通用查询方法。

### 3. 给 Agent 创建主链路补保护

- 文件：`backend_v2/src/main/java/com/kama/jchatmind/agent/JChatMindFactory.java`
- 改动：
  - `loadLongTermMemory()` 外层增加 `try/catch`
  - 长时记忆加载失败时只记 warning，返回空记忆列表，继续创建 Agent
- 结果：
  - 记忆故障不会阻断聊天主流程。

## 本次确认的真实高风险入口

- 当前代码里，真正需要防止“读降级污染写路径”的入口已不是早期的 `confirmCandidate`
- 当前真实入口是：
  - `extractMemoryCandidates`
  - `persistAutomatically`
  - `handleConflictUpdate`
  - `getConfirmedMemories`
- 本次修复后，这条更新型自动持久化链路在旧记忆查询失败时会直接失败，不再误判为空后继续写入。

## 测试调整

- 文件：`backend_v2/src/test/java/com/kama/jchatmind/service/impl/UserMemoryFacadeServiceImplTest.java`
- 调整为围绕当前真实实现的最小测试集：
  - `getConfirmedMemories()` 查询失败时应抛异常
  - 自动持久化更新型记忆在旧记忆查询失败时不得继续插入
  - `recallRelevantMemories()` 在语义召回失败时可回退最近记忆
  - `recallRelevantMemories()` 在所有 fallback 路径均失败时返回空列表

## 验证

- 已执行：

```powershell
.\mvnw.cmd -q clean "-Dtest=GlobalExceptionHandlerTest,UserMemoryControllerTest,UserMemoryFacadeServiceImplTest" test
```

- 结果：
  - 测试通过
  - 控制台中出现的 `Async request timed out`、`ollama unavailable` 为测试中主动构造的 warning，不是失败

## 相关文档

- `docs/records/user-memory-fallback-reclaim-2026-05-25.md`
- `docs/records/error-summary-followup-2026-05-25.md`

## 结论

- `schema mismatch` 止血逻辑已经完成回收。
- `getConfirmedMemories()` 不再承担全局降级职责。
- 聊天主链路保留了必要降级，但写路径与接口查询路径恢复了更严格、更可观测的失败语义。
