# JChatMind 用户记忆系统

> 合并自历史方案文档，并补充 2026-05-25 自动长期记忆升级记录。

## 1. 目标

为 JChatMind 增加用户级长期记忆能力，让 Agent 在当前会话短期上下文之外，还能读取用户稳定、可复用、对后续回答有帮助的长期信息，例如：

- 背景信息
- 偏好
- 约束
- 学习目标
- 已知事实

当前阶段聚焦“用户级长期记忆”的提取、持久化、召回和运行时注入，不扩展到登录体系、MCP 编排或多 Agent 协作。

## 2. 演进概览

### 初版

已完成：

- 双表架构：`user_memory` + `user_memory_candidate`
- 4 个 REST API
- Agent 运行时加载已确认长期记忆
- 用户消息后做轻量候选提取

初版限制：

- 候选提取主要依赖关键词匹配
- 候选记忆必须人工确认后才能进入长期记忆

### P0：LLM 结构化提取 + evidence 溯源

已完成：

- 用 LLM 替代关键词匹配作为主提取路径
- 输出结构化 JSON：`type` / `content` / `importance` / `evidence_message_index`
- 为 `user_memory` / `user_memory_candidate` 增加 `importance`、`evidence_message_id`
- 为 `user_memory` 增加 `evidence_text`
- LLM 不可用时降级到关键词提取

### P1：记忆 Embedding + pgvector 语义检索

已完成：

- `user_memory` 增加 `embedding vector(1024)`
- 建立 pgvector `ivfflat` 索引
- 用户发消息时先做 embedding，再按 cosine distance 做 Top-K 召回
- 语义召回结果不足时，用无 embedding 记忆补齐
- `RagService` 不可用时降级到最近记忆加载

### P2：短期记忆压缩 + 冲突合并

已完成：

- 全部消息超过 8000 字符时触发压缩
- 保留最近 8 条原始消息
- 旧消息交给 LLM 压缩为 2-3 句摘要
- 摘要以 `SystemMessage` 前置注入
- 长期记忆冲突时，LLM 用 `"更新："` 前缀标注
- 冲突写入时按 `memoryType` 删除旧记录并插入新记录

### P3：自动长期记忆升级

已完成，替代原人工确认主链路。

目标：

- 让系统像 Codex 一样，自主判断哪些记忆值得长期保存
- 轻量实现，不引入复杂审批流
- 保留候选审计，但取消人工确认依赖

核心策略：

- 提取结果新增 `should_persist`
- 双门槛自动写入：
  - `should_persist = true`
  - `importance in (high, medium)`
- `low` 或 `should_persist = false` 的候选只保留审计记录，不进入长期记忆
- `user_memory_candidate` 保留，作为系统判定记录，不再作为人工待确认队列

## 3. 数据设计

### user_memory

用于保存已进入长期记忆的用户信息。

当前关键字段：

- `id`
- `user_id`
- `session_id`
- `memory_type`
- `content`
- `importance`
- `evidence_message_id`
- `evidence_text`
- `embedding`
- `created_at`
- `updated_at`

### user_memory_candidate

用于保存提取结果及系统判定审计记录。

当前关键字段：

- `id`
- `user_id`
- `session_id`
- `memory_type`
- `content`
- `evidence`
- `importance`
- `evidence_message_id`
- `status`
- `created_at`
- `updated_at`

当前 `status` 取值：

- `PENDING`：刚提取，尚未完成后续状态更新
- `PERSISTED`：已自动写入长期记忆
- `DISCARDED`：被系统判定为不应长期保存

## 4. 后端接口

当前对外接口：

| 方法 | 路径 | 功能 |
|------|------|------|
| GET | `/api/users/{userId}/memories` | 查询长期记忆 |
| GET | `/api/users/{userId}/memory-candidates` | 查询候选审计记录 |
| DELETE | `/api/users/{userId}/memories/{memoryId}` | 删除长期记忆 |

已下线接口：

| 方法 | 路径 | 原功能 |
|------|------|------|
| POST | `/api/users/{userId}/memory-candidates/{candidateId}/confirm` | 人工确认候选并写入长期记忆 |

说明：

- `memory-candidates` 现在用于观测“系统提取与判定结果”
- 不再承担“待用户确认”的职责

## 5. Agent 运行时设计

`JChatMindFactory.create(...)`：

```java
public JChatMind create(String userId, String agentId, String chatSessionId)
```

Agent 构建时加载：

1. 当前会话短期记忆
2. 当前 `userId` 的长期记忆

长期记忆注入规则：

- 只注入 `user_memory`
- 不注入 `user_memory_candidate`
- 删除后的长期记忆不应再次进入上下文

## 6. 当前提取与持久化流程

触发点：

- 用户消息完成后
- `ChatEventListener` 在 Agent 运行后调用 `extractMemoryCandidates(userId, sessionId)`

主流程：

1. 读取最近 8 条用户消息
2. 优先调用 LLM 进行结构化提取
3. 若 LLM 不可用，则降级到关键词提取
4. 每条提取结果先写入 `user_memory_candidate`
5. 系统根据 `should_persist + importance` 决定是否写入 `user_memory`
6. 写入完成后更新 candidate 状态：
   - `PERSISTED`
   - `DISCARDED`

冲突更新规则：

- 若 `content` 带 `"更新："` 前缀，视为语义冲突更新
- 按 `memoryType` 删除旧长期记忆
- 写入新的长期记忆

## 7. Prompt 与降级策略

当前 LLM 提取输出字段：

- `type`
- `content`
- `importance`
- `should_persist`
- `evidence_message_index`

提取约束：

- 只保留稳定、可复用、对未来回答有帮助的信息
- 不保存一次性任务、短期上下文、敏感信息、纯闲聊信息
- 与现有记忆冲突时，使用 `"更新："` 前缀

降级策略：

| 场景 | 降级行为 |
|------|---------|
| LLM 提取失败 | 回退到 `extractWithKeywords()` |
| embedding 生成失败 | 长期记忆仍入库，`embedding = null` |
| 语义召回异常 | 回退到最近长期记忆 |
| `RagService` 不可用 | 同样回退到最近长期记忆 |

## 8. 前端影响

前端记忆面板如果接入当前接口，语义应调整为：

- 长期记忆：已进入系统长期上下文的记忆
- 候选记忆：系统提取/判定记录

不应再展示：

- “确认候选”
- “忽略候选” 这类人工审批动作

前端可展示的 candidate 信息：

- `memoryType`
- `content`
- `importance`
- `evidence`
- `status`

## 9. 数据库脚本

相关脚本位于：

- [2026-05-25-add-user-memory-columns.sql](D:/coding/Java/project/JchatMind/sql/user-memory/2026-05-25-add-user-memory-columns.sql)
- [2026-05-25-add-user-memory-candidate-status.sql](D:/coding/Java/project/JchatMind/sql/user-memory/2026-05-25-add-user-memory-candidate-status.sql)

用途：

- 第一个脚本补齐 `importance` / `evidence` 相关字段
- 第二个脚本补齐 `user_memory_candidate.status`

第二个脚本还会把历史 `status is null` 的记录补成 `PERSISTED`

## 10. 本次改动记录

2026-05-25 自动长期记忆升级：

- 移除 `confirmCandidate()` 服务接口
- 删除 `POST /memory-candidates/{candidateId}/confirm`
- `UserMemoryCandidate` 增加 `status`
- `UserMemoryCandidateVO` 增加 `status`
- `UserMemoryCandidateMapper` 增加 `updateStatusById`
- `extractMemoryCandidates()` 改为自动提取 + 自动持久化
- 提取 prompt 增加 `should_persist`
- 候选状态改为 `PERSISTED` / `DISCARDED`
- 新增数据库脚本：
  - `2026-05-25-add-user-memory-candidate-status.sql`

验证结果：

- 定向测试通过：`.\mvnw.cmd -q -Dtest=UserMemoryFacadeServiceImplTest test`
- 全量测试仍存在既有基线问题：
  - `RagRecallEvaluationTest` 调用本地 embedding 服务返回 `400`
  - 与本次记忆升级改动无关

## 11. 实现状态

- **状态**：已完成
- **最后验证**：2026-05-25
- **当前主链路**：自动长期记忆
- **未完成项**：无（见后续路线）

## 12. 后续路线

本期未实现：

- 长期记忆自动衰减/过期
- 群组或团队共享记忆
- 周期性批量重评估记忆
- 前端冲突记忆可视化
