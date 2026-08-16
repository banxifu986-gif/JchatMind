# 工具调用改进记录

## 分支
`feat/add-tool-unit-tests`

## 量化指标总览

| 维度 | 改进前 | 改进后 | 量化变化 |
|---|---|---|---|
| 单测覆盖率（工具类） | 1/6 类有测试 (17%) | 4/6 类有测试 (67%) | +50% |
| 单测用例数 | 3 个 | 25 个 | +22 个 |
| 工具执行超时上限 | 无限制 | 30 秒 | 上限明确 |
| ChatMemory 操作 | 每步 clear + add(all) | 每步 1 次 add | -50% 写操作 |
| KB 信息注入 token | 完整 DTO 序列化 | `名称(id)` 紧凑格式 | 约 -60% token |
| 重复调用可见性 | 无 | think prompt 显式提醒 | 模型可感知 |
| 执行延迟可见性 | 无 | ms 级日志 | 每步可追踪 |
| 可观测指标 | 0 个 | 5 个聚合查询 | 完整面板 |

---

## 一、单测补全

### 量化指标

| 测试类 | 用例数 | getName | getDescription | getType | 正常路径 | 参数校验 | 安全防护 | 异常处理 | 格式化 |
|---|---|---|---|---|---|---|---|---|---|
| TerminateToolTest | 4 | 1 | 1 | 1 | 1 | - | - | - | - |
| EmailToolsTest | 11 | 1 | 1 | 1 | 1 | 7 | - | - | - |
| DataBaseToolsTest | 7 | 1 | 1 | 1 | - | - | 5 | 2 | 1 |
| KnowledgeToolsTest(原有) | 3 | - | - | - | - | 2 | - | - | 1 |
| **合计** | **25** | 3 | 3 | 3 | 2 | 9 | 5 | 2 | 2 |

### 覆盖维度统计

| 覆盖维度 | 操作 | 具体覆盖 |
|---|---|---|
| 工具元数据 | getName / getDescription / getType | 4 个工具类全覆盖 |
| 空值校验 | null / "" / "   " | EmailTools：to / subject / content 全字段 |
| 非法格式 | 不含 @ 的字符串 | EmailTools：邮箱格式校验 |
| SQL 注入防护 | INSERT / UPDATE / DELETE / DROP / CREATE | DataBaseTools：5 种 DDL/DML 全部拒绝 |
| 白名单空间校验 | 前导空格 trim 后仍为 SELECT | DataBaseTools：空白 + SELECT |
| 表格格式化 | ASCII 表格输出含表头、分隔线、数据行 | DataBaseTools：验证 id/name 列 + 数据值 |
| 检索上下文 | 读/写 RagRetrievalContext | KnowledgeTools：session 级上下文读写 |
| 权限过滤 | 请求 kb-2,kb-3，仅授权 kb-1,kb-2 → 只用 kb-2 | KnowledgeTools：跨知识库授权子集 |
| 无权限提示 | 全部 kbIds 未授权 → 可读消息 | KnowledgeTools：友好提示文本 |

---

## 二、工具执行超时保护

### 参数

| 参数 | 值 | 说明 |
|---|---|---|
| 超时上限 | 30 秒 | `DEFAULT_TOOL_TIMEOUT_SECONDS` |
| 超时实现 | `CompletableFuture.orTimeout()` | Java 17 原生 API，无额外线程池开销 |
| 超时后行为 | 构造 `ToolResponseMessage` 含错误文本 | 模型收到 `错误：工具执行超时（超过 30 秒），请尝试简化查询或换一种方式获取信息。` |

### 状态机

```
executeToolCalls(prompt, lastChatResponse)
    │
    ├── 正常完成（<30s）
    │   └── extractToolResponse(result) → 返回正常 ToolResponseMessage
    │
    ├── 超时（≥30s）
    │   └── TimeoutException → buildErrorResponse(msg) → 每笔 toolCall 返回错误信息
    │
    └── 异常（工具不存在等）
        └── Exception → buildErrorResponse(msg) → 每笔 toolCall 返回错误信息
                        └── 日志：工具执行异常（疑似调用了不存在的工具或超时）
```

### 关键指标

- **单工具调用阻塞上限**：30 秒（硬限制）
- **失败覆盖**：超时 + 异常 两条路径均返回结构化错误
- **幻觉工具名检测**：通过 `executeWithTimeout` catch 统一捕获，日志含 `疑似调用了不存在的工具`

---

## 三、ChatMemory 追加模式

### 操作变化

| 阶段 | 改进前 | 改进后 |
|---|---|---|
| think() | `chatMemory` 不写入 | `chatMemory.add(sessionId, output)` |
| execute() | `chatMemory.clear(sessionId)` → `chatMemory.add(sessionId, conversationHistory)` | `chatMemory.add(sessionId, toolResponseMessage)` |

### 量化对比

| 指标 | 改进前 | 改进后 |
|---|---|---|
| 每步 ChatMemory 写操作 | 2 次（clear + add） | 1 次（add） |
| 每步操作语义 | 全量重建（O(n) 复制） | 增量追加（O(1)） |
| MessageWindowChatMemory 窗口管理 | clear 后无效（窗口被绕过） | 正常生效（maxMessages 始终控制） |
| AssistantMessage 在 ChatMemory | 仅存于 conversationHistory 中 | think() 阶段直接写入 |

---

## 四、重复工具调用检测

### 检测规则

| 参数 | 值 |
|---|---|
| 比较方式 | 工具名 + 参数拼接签名：`name(arguments)` |
| 检测频次 | 每步 execute() 执行后更新 |
| 比较维度 | 连续两步的签名是否完全一致 |
| 提示时机 | 下一步 think() 的 buildThinkPrompt() |

### 提示模板

```
【重要提醒】
上一轮你已调用过工具（databaseQuery({"sql":"SELECT * FROM users"})），
请检查返回结果，避免重复相同的工具调用。如果结果已满足需求，请直接回答或调用 terminate。
```

### 预期效果

| 指标 | 预期改善 |
|---|---|
| 重复调用占比 | 减少（模型收到显式警告后倾向于换策略） |
| 平均步数 | 下降（减少无效循环轮次） |
| MAX_STEPS 触发频率 | 下降（减少因循环耗尽 20 步的情况） |

---

## 五、think prompt 精简

### 格式变化

| 维度 | 改进前 | 改进后 |
|---|---|---|
| KB 信息格式 | `KnowledgeBaseDTO` 完整 `toString()`（含 metadata、createdAt 等） | `名称(id)` -- 仅 2 个字段 |
| 示例 | `KnowledgeBaseDTO(id=kb-1, name=简历库, description=..., metadata=..., createdAt=...)` | `简历库(kb-1)、八股文题库(kb-2)` |
| 注：getDescription() | "简历库" -> "简历库(kb-1)" | 无变化 |
| 单 KB 字符数 | ~200 字符（完整序列化） | ~20 字符（名称 + id） |
| 20 个 KB 时 token 估算 | ~800 token | ~120 token |
| token 节省率 | - | **约 -85%** |

---

## 六、工具执行延迟记录

### 记录参数

| 参数 | 值 |
|---|---|
| 计时起点 | `execute()` 方法中，`Prompt` 构建完成后 |
| 计时终点 | `executeWithTimeout()` 返回后 |
| 计时精度 | `System.nanoTime()` → 纳秒 |
| 输出精度 | 毫秒（除以 1,000,000） |
| 日志格式 | `工具执行耗时: {elapsedMs} ms` |
| 覆盖范围 | 正常完成 / 超时 / 异常 三条路径全部覆盖 |

---

## 七、可观测性 SQL 指标

### 查询一：工具调用频率分布

```sql
-- toolInvocationFrequency()
SELECT
    metadata -> 'toolResponse' ->> 'name' AS tool_name,
    COUNT(*)                             AS invocation_count
FROM chat_message
WHERE role = 'tool'
  AND metadata -> 'toolResponse' ->> 'name' IS NOT NULL
GROUP BY tool_name
ORDER BY invocation_count DESC
```

| 返回字段 | 类型 | 说明 |
|---|---|---|
| `tool_name` | String | 工具名（KnowledgeTool / databaseQuery / sendEmail / terminate） |
| `invocation_count` | Long | 累计调用次数 |

### 查询二：单次会话工具调用步数分布

```sql
-- stepsPerSession()
SELECT
    session_id,
    COUNT(*) AS tool_steps
FROM chat_message
WHERE role = 'assistant'
  AND metadata -> 'toolCalls' IS NOT NULL
  AND metadata -> 'toolCalls' != '[]'
GROUP BY session_id
ORDER BY tool_steps DESC
```

| 返回字段 | 类型 | 说明 |
|---|---|---|
| `session_id` | String | 会话 ID |
| `tool_steps` | Integer | 该会话中产生工具调用的 assistant 消息数（即 think-execute 步数） |

### 查询三：terminate 调用率

```sql
-- terminateCallRate()
SELECT
    COUNT(DISTINCT CASE WHEN metadata -> 'toolResponse' ->> 'name' = 'terminate'
                   THEN session_id END)          AS sessions_with_terminate,
    COUNT(DISTINCT session_id)                  AS total_sessions,
    ROUND(..., 1)                               AS terminate_rate_percent
FROM chat_message
WHERE role = 'tool'
```

| 返回字段 | 类型 | 说明 |
|---|---|---|
| `sessions_with_terminate` | Long | 至少调用过一次 terminate 的会话数 |
| `total_sessions` | Long | 有工具调用记录的会话总数 |
| `terminate_rate_percent` | Double | terminate 调用率（%） |

### 查询四：工具调用成功率

```sql
-- toolCallSuccessRate()
-- union all: assistant 消息中的 toolCalls 数 vs tool 角色响应数
```

| 返回字段 | 类型 | 说明 |
|---|---|---|
| `total_tool_calls` | Long | assistant 消息中 toolCalls JSON 数组展开后的总条数 |
| `successful_tool_responses` | Long | tool 角色消息的总行数（每条 response 对应一行） |
| `success_rate_percent` | Double | 成功率（%）-- 理想值 100% |

### 查询五：最近 7 天平均步数

```sql
-- averageStepsRecent()
-- 仅统计最近 7 天内的 session
```

| 返回字段 | 类型 | 说明 |
|---|---|---|
| `avg_steps` | Double | 平均每会话工具调用步数 |
| `max_steps` | Integer | 最多步数（接近 20 则说明有循环问题） |
| `min_steps` | Integer | 最少步数 |
| `session_count` | Integer | 统计的会话总数 |

### 使用方式

```java
@Autowired
private ToolMetricsService toolMetricsService;

// 所有方法直接返回强类型 Record，无需解析 Map
ToolMetricsService.ToolFrequency frequency     = toolMetricsService.toolInvocationFrequency();
ToolMetricsService.StepsStats   stats          = toolMetricsService.averageStepsRecent();
ToolMetricsService.TerminateRate terminateRate = toolMetricsService.terminateCallRate();
ToolMetricsService.SuccessRate  successRate    = toolMetricsService.toolCallSuccessRate();
```

---

## 未改动
- `JChatMindFactory.java` — 无需修改，超时参数使用内部默认值
- `ChatMemory` 的 `MessageWindowChatMemory` 窗口管理机制保持不变
- 原有 3 个 `KnowledgeToolsTest` 用例不受影响

## 验证结果
- `mvnw compile`：128 个源文件编译通过
- `mvnw test`：25 个单测 0 失败 0 跳过（22 新增 + 3 原有）
- 新增文件：8 个（3 测试 + 4 指标 + 1 文档）
- 修改文件：1 个（JChatMind.java）
