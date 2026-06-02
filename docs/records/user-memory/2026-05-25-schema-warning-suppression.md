# User Memory 缺列日志收口记录 - 2026-05-25

## 1. 现象

前端发送消息已经可以正常获得回答，但后台仍反复出现：

```text
BadSqlGrammarException
ERROR: column "importance" does not exist
```

典型 SQL 为：

```sql
SELECT
    id,
    user_id,
    session_id,
    memory_type,
    content,
    importance,
    evidence_message_id,
    evidence_text,
    embedding,
    created_at,
    updated_at
FROM user_memory
WHERE user_id = ?
ORDER BY updated_at DESC
```

## 2. 结论

这说明两件事同时成立：

1. 主聊天链路已经被前面的降级保护住，所以用户功能表面正常
2. 记忆子链路仍有若干直接访问 `user_memory` / `user_memory_candidate` 的入口，会继续命中未迁移完成的表结构

所以这是“已知数据库缺列问题仍在侧路刷日志”，不是新的聊天主流程故障。

## 3. 本次补充修复

在 `UserMemoryFacadeServiceImpl` 中补了统一收口：

1. `getUserMemoryCandidates()` 缺列时返回空列表
2. `selectByUserIdAndContent()` 缺列时按“未命中”处理
3. 候选记忆持久化缺列时直接跳过
4. 识别到已知 schema mismatch 时，只打一条受控 `warn`，后续转 `debug`

目标是：

- 不让已知缺列问题继续刷整段 SQL 堆栈
- 让“数据库还没迁移完成”只表现为记忆功能降级
- 保持正常聊天链路干净可观测

## 4. 边界

这不是最终修复，只是日志和运行时收口。

要彻底恢复用户记忆能力，仍然必须执行 `docs/plans/user-memory.md` 中定义的数据库迁移，至少补齐：

```sql
ALTER TABLE user_memory_candidate ADD COLUMN importance VARCHAR(16) DEFAULT 'medium';
ALTER TABLE user_memory_candidate ADD COLUMN evidence_message_id UUID;

ALTER TABLE user_memory ADD COLUMN importance VARCHAR(16) DEFAULT 'medium';
ALTER TABLE user_memory ADD COLUMN evidence_message_id UUID;
ALTER TABLE user_memory ADD COLUMN evidence_text TEXT;
```
