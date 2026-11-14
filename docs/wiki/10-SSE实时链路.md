# SSE 实时链路

> 页面定位：会话级聊天 SSE（Redis 帧存储 + 跨实例 Pub/Sub + Last-Event-ID 回放）与任务级摄入 SSE（纯内存）两套独立链路。改实时推送、断线恢复前必读。
>
> 最后核对：2026-11-14（按源码核验）。

## 1. 定位

聊天 SSE 的目标：断线重连不丢帧、多实例部署下事件可达。实现分三层：Redis 帧存储（原子 Lua + 64 帧窗口 + 30 分钟 TTL）、Redis Pub/Sub 跨实例转发、前端 Last-Event-ID 游标推进 + replay-gap 全量刷新。**不承诺**持久化事件、网络分区或宿主机故障下的恢复保证。

## 2. 关键文件索引

包根 `backend_v2/src/main/java/com/kama/jchatmind/`：

| 文件 | 职责 | 行数 |
| --- | --- | --- |
| [controller/SseController.java](../../backend_v2/src/main/java/com/kama/jchatmind/controller/SseController.java) | `GET /sse/connect/{chatSessionId}`；手工 401/404 门卫；接受 `Last-Event-ID` | 47 |
| [message/SseMessage.java](../../backend_v2/src/main/java/com/kama/jchatmind/message/SseMessage.java) | 8 种事件 Type；Payload/Metadata 字段 | 56 |
| [service/impl/SseServiceImpl.java](../../backend_v2/src/main/java/com/kama/jchatmind/service/impl/SseServiceImpl.java) | per-session emitter 状态机、回放、gap 判定 | 314 |
| [service/impl/RedisChatSseEventStore.java](../../backend_v2/src/main/java/com/kama/jchatmind/service/impl/RedisChatSseEventStore.java) | Lua 原子 append/snapshot、key/channel 定义 | 147 |
| [service/impl/RedisChatSseEventSubscriber.java](../../backend_v2/src/main/java/com/kama/jchatmind/service/impl/RedisChatSseEventSubscriber.java) | Pub/Sub → 本地 `acceptEvent` | 31 |
| [config/RedisConfig.java](../../backend_v2/src/main/java/com/kama/jchatmind/config/RedisConfig.java) | `chatSseEventListenerContainer` 绑定 PatternTopic；`@Profile("!test")` | 52 |
| [ingestion/IngestionTaskProgressServiceImpl.java](../../backend_v2/src/main/java/com/kama/jchatmind/ingestion/IngestionTaskProgressServiceImpl.java) | 摄入任务级 SSE（纯内存，无 Redis） | 184 |
| [ui/src/hooks/useChatStream.ts](../../ui/src/hooks/useChatStream.ts) | 前端 SSE 消费 | 279 |
| [ui/src/api/api.ts](../../ui/src/api/api.ts) | 帧解析器、`runSseReconnectLoop`、`subscribeChatSessionEvents` | L350-495 |

## 3. 事件类型全清单（`SseMessage.Type`）

`AI_PLANNING`、`AI_THINKING`、`AI_EXECUTING`、`AI_CONTENT_DELTA`、`AI_GENERATED_CONTENT`、`AI_DONE`、`AI_ERROR`、`TOOL_APPROVAL_REQUIRED`。

发送点（`JChatMind.java`）：`AI_PLANNING`(L752 run 入口)、`AI_THINKING`(L282)、`AI_EXECUTING`(L541)、`AI_CONTENT_DELTA`(每个有效文本分块)、`AI_GENERATED_CONTENT`(L259 最终消息，**唯一携带 `metadata.chatMessageId` 的事件**)、`AI_DONE`(L769/L777)、`AI_ERROR`(L737/L766 步数用尽、L771 结果未确认、L773 资源满 `TOOL_EXECUTOR_BUSY`、L775 工具失败、L792 兜底)、`TOOL_APPROVAL_REQUIRED`(L855-877，payload 含 `approvalRequestId/toolName/toolInput/callCount/expiresAt/stepNumber`)。`done` 字段恒 `type == AI_DONE`。

另有连接帧：`init`（event name `init`，data `connected`，**无 id 不参与序号**）与 `replay-gap`（见 §4）。

## 4. 连接与回放链路

1. **门卫**：`SseController` 校验 Bearer 对应登录用户 + 会话 owner；未登录 401、非 owner/不存在 404（真 HTTP 状态码，不走 ApiResponse 封套）。
2. **连接** `SseServiceImpl.connect(sessionId, lastEventId)`（emitter 超时 30 分钟）：先读 Redis 快照 `reconcileSnapshot`；`lastEventId == null` → `sendInit`；否则 `replay`。
3. **回放** `replay`（L108-128）：gap 判定 `lastEventId < oldestSequence - 1 || lastEventId > latestSequence` → `sendReplayGap`（event name `replay-gap`，**帧 id = latestSequence**，data `history_window_exceeded`）；逐帧过滤 `sequence() > lastEventId`，event name `message`、`id:<sequence>` 发送。
4. **写路径** `JChatMind → SseServiceImpl.send → eventStore.append`：Lua 脚本原子执行 `INCR sequence → RPUSH history → LTRIM -64 → EXPIRE 两 key → PUBLISH`；本地实例经 `applyEvent` 广播 emitter，其他实例经 `RedisChatSseEventSubscriber.onMessage → acceptEvent → 同一 applyEvent`。

**Redis key**：`jchatmind:chat-sse:{base64url(sessionId)}:sequence|history`（hash tag 保证 cluster 同 slot）；channel pattern `jchatmind:chat-sse:*:events`；窗口 `MAX_HISTORY_EVENTS=64`，TTL `HISTORY_RETENTION_SECONDS=1800`。

**本地序收敛** `applyEvent`（L130-154）：事件序号落后本地且 Redis 最新序也落后 → 读快照发 replay-gap 并重建本地 history；跳帧（`> latest + 1`）→ 先读快照补齐，补齐后仍落后则 return。`reconcileSnapshot`（L156-214）要求补发窗口**连续无缺口**（首帧 = latest+1 且逐帧递增），不连续则向所有 client 发 replay-gap。

**可回放区间**：`oldestSequence - 1 <= lastEventId <= latestSequence`，否则 replay-gap。

## 5. 前端消费契约（`useChatStream` + `api.ts`）

- 连接：Bearer token（`localStorage["jchatmind.token"]`）+ `fetch + ReadableStream`；`VITE_SSE_BASE_URL` 可覆盖（默认推导 `/sse`）。
- 游标：`lastEventIdRef` 单调推进，`eventId <= lastEventIdRef` 的帧丢弃（L141-145）。
- 渲染：`AI_CONTENT_DELTA` 累积临时气泡；`AI_GENERATED_CONTENT` 以持久化消息替换；assistant 无 `toolCalls` 时收起执行状态（防 SSE 断连丢 `AI_DONE` 残留"思考中"）。
- replay-gap 处理（L219-246）：先同步推进游标为 gap 帧 id → `getChatMessages` 拉全量历史 → `mergeChatHistoryWithLiveMessages`（按消息 id Map 合并，live 不覆盖已有 id）→ 刷新待审批。
- 重连：`runSseReconnectLoop` 固定 **1000ms** 间隔，4xx 停止重试。

## 6. 摄入任务 SSE（独立实现）

`GET /sse/ingestion/{taskId}`：`@NeedLogin` + `getTask` owner 校验 → `IngestionTaskProgressServiceImpl`（**纯内存，无 Redis、无跨实例分发**）：任务级锁串行化回放/注册/发送；事件名 `ingestion-progress`，单调 `AtomicLong` 序号；历史 64 帧，`Last-Event-ID` 仅回放进程内；终态（`SUCCEEDED/FAILED/DEAD_LETTER/CANCELLED`）且无连接 30 分钟后清理；无 Last-Event-ID 时发送最新事件。前端对 SSE 事件与轮询结果均再次核对 `kbId`，轮询为跨实例/长断线兜底。

## 7. 不变量与契约

1. 聊天 SSE 超时/保留均为代码常量：`SSE_TIMEOUT_MILLIS=30min`、`SESSION_RETENTION_MILLIS=30min`；摄入同 30min/`TERMINAL_EVENT_RETENTION_MILLIS=30min`。
2. 单连接发送失败只清理该 emitter，不影响其他连接。
3. **会话删除不清理 Redis SSE key**：`RedisChatSseEventStore.delete()` 在 main 代码中无调用方，依赖 30 分钟 TTL 过期。
4. 摄入 SSE 与聊天 SSE 实现完全独立，不能混用对方的回放语义。
5. replay-gap 后前端必须"先推进游标、再刷历史、暂存合并刷新期间新消息"——契约由前端 contract 测试锁定（见 §8）。

## 8. 验证入口

| 测试 | 门控 |
| --- | --- |
| `SseServiceImplTest`、`SseSequenceGapRecoveryTest`、`SseMessageStreamingContractTest`、`SseControllerTest` | 默认跑 |
| `RedisChatSseEventStoreTest`（Mockito 模拟 Redis 执行 Lua） | 默认跑 |
| `G5FullApplicationHttpPreflightL2Test`（跨实例 Pub/Sub 回环） | `-Dg5.chat.full.l2=true` |
| 前端 contract：SSE 历史恢复/replay-gap/content-delta 渲染/审批事件 | `ui/npm test` |
| e2e：`chat-sse-real-backend-recovery.spec.ts`、`chat-sse-replay-gap.spec.ts` | `ui/npm run test:e2e` |

## 9. 关联文档

- 事件的生产方：[03-Agent核心循环](03-Agent核心循环.md)、[06-工具Harness](06-工具Harness.md)（审批事件）
- 队列与 worker：[04-异步任务调度](04-异步任务调度.md)
- 摄入任务：[07-RAG摄入与索引](07-RAG摄入与索引.md)
- 前端架构：[15-前端架构](15-前端架构.md)
