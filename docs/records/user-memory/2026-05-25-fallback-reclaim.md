# User Memory 缺列止血逻辑回收记录 - 2026-05-25

## 背景

- 早些时候为应对 PostgreSQL 缺少 `importance`、`evidence_message_id`、`evidence_text` 字段，后端临时加入了多处“吞异常返回空结果”的止血逻辑。
- 之后已完成真实修复：
  - 新增脚本：`sql/user-memory/2026-05-25-add-user-memory-columns.sql`
  - 本地数据库已执行该脚本，`user_memory` 与 `user_memory_candidate` 缺失字段已补齐。

## 本次回收内容

- 回收 `UserMemoryController` 中仅用于 schema mismatch 的 GET 接口空数组兜底。
- 回收 `UserMemoryFacadeServiceImpl` 中以下临时逻辑：
  - `isMemorySchemaMismatch(...)`
  - `isMemorySchemaMismatchMessage(...)`
  - `logMemorySchemaWarning(...)`
  - `memorySchemaWarningLogged`
  - 针对 mapper 缺列异常的专门吞异常分支
- 同步重写测试，移除“缺列时也应正常返回空结果”的临时行为断言。

## 保留项

- 保留 `RagServiceImpl` 对 Ollama embedding 接口的正式修复：
  - `POST /api/embed`
  - `input`
  - `keep_alive: "-1"`
- 保留 `GlobalExceptionHandler` 对 `AsyncRequestTimeoutException` 的专门处理，避免向 `text/event-stream` 写入 `ApiResponse`。
- 保留 `getConfirmedMemories()` 在一般查询失败时返回空列表的既有降级能力，用于避免聊天主链路因记忆读取失败而中断；这不再区分 schema mismatch。

## 当前结论

- “数据库缺列”已经从代码止血阶段切回数据库真实修复阶段，临时兼容逻辑已回收。
- 若后续再次出现 `importance` 等列不存在，应直接视为数据库迁移未执行或环境漂移，而不是由业务代码继续吞掉错误。
