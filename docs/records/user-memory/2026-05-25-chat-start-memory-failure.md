# 新建对话时报错排查记录 - 2026-05-25

## 1. 现象

- 时间：`2026-05-25`
- 场景：前端新建对话，输入“你好”
- 结果：后端异步聊天链路报错，前端无法正常开始本轮对话

关键异常分为两条：

1. `POST http://localhost:11434/api/embeddings` 连接失败
2. `user_memory` 表查询时报 `column "importance" does not exist`

## 2. 根因拆解

### 2.1 Embedding 服务不可达

调用链：

```text
JChatMindFactory.loadLongTermMemory
-> UserMemoryFacadeServiceImpl.recallRelevantMemories
-> RagServiceImpl.embed
-> POST /api/embeddings
```

当前本地 `Ollama` embeddings 端点未连通，导致语义记忆召回第一跳失败。

### 2.2 记忆表结构未完成迁移

代码与 mapper 已经依赖：

- `user_memory.importance`
- `user_memory.evidence_message_id`
- `user_memory.evidence_text`
- `user_memory_candidate.importance`
- `user_memory_candidate.evidence_message_id`

但当前数据库中的 `user_memory` 至少缺少 `importance` 列，因此：

- 语义召回失败后的“最近记忆回退”
- LLM 记忆提取时的“已有记忆格式化”

都会再次命中同一张表并抛 SQL 异常。

## 3. 为什么会拖垮聊天主链路

问题不只是依赖缺失，而是降级链路不完整：

1. `recallRelevantMemories()` 在 embeddings 失败后，会回退到 `getConfirmedMemories()`
2. 但 `getConfirmedMemories()` 之前直接查库，没有兜底
3. 一旦表结构未迁移，回退链路再次抛错
4. `JChatMindFactory.loadLongTermMemory()` 因异常中断，导致对话创建失败

同时：

1. `ChatEventListener.handle()` 的 `finally` 中无保护地调用 `extractMemoryCandidates()`
2. 该分支查询已有记忆时同样会命中缺列 SQL
3. 最终表现为异步未捕获异常刷屏

## 4. 本次修复决策

采用“先保主链路，再补迁移”的最小修复：

1. `UserMemoryFacadeServiceImpl.getConfirmedMemories()` 查询失败时返回空列表并记录告警
2. `formatExistingMemories()` 改为复用 `getConfirmedMemories()`，避免直接裸查库
3. `ChatEventListener.handle()` 对 `extractMemoryCandidates()` 增加异常隔离，避免异步副作用污染主链路

## 5. 必须补的数据库迁移

本次代码修复只解决“服务可降级运行”，不等于数据层问题已消失。

仍需尽快执行 `docs/plans/user-memory.md` 中定义的迁移，至少包括：

```sql
ALTER TABLE user_memory_candidate ADD COLUMN importance VARCHAR(16) DEFAULT 'medium';
ALTER TABLE user_memory_candidate ADD COLUMN evidence_message_id UUID;

ALTER TABLE user_memory ADD COLUMN importance VARCHAR(16) DEFAULT 'medium';
ALTER TABLE user_memory ADD COLUMN evidence_message_id UUID;
ALTER TABLE user_memory ADD COLUMN evidence_text TEXT;
```

如继续使用语义记忆召回，还需要确保 embeddings 依赖服务可达。

## 6. 验证口径

- embeddings 不可达时，新建对话仍可继续，只是拿不到长期记忆召回
- `user_memory` 缺列时，新建对话仍可继续，只是记忆读取/提取降级为空
- 异步线程不再抛未捕获的记忆提取异常
