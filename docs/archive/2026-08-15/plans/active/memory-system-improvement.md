# 记忆系统改进方案

> 状态：方案设计中
> 创建：2026-06-02
> 基于：上一轮记忆系统缺陷分析

## 1. 背景与目标

当前记忆系统存在两个阻塞级（P0）和两个高影响级（P1）缺陷，需要在其他功能完成后修复。本方案按优先级分阶段推进，每阶段独立可验证。

### 核心缺陷回顾

| 优先级 | 问题 | 影响 |
|--------|------|------|
| **P0** | 摘要无限膨胀：每次压缩 `conversationSummary = old + new`，永不二次压缩 | 长对话中摘要自身超出模型 context window，API 调用失败 |
| **P0** | 字符数替代 token 数：用 `String.length()` 判断阈值，无 tokenizer | 中文/英文/代码 token 占比差异巨大，单一阈值无法准确保护 context |
| **P1** | 提取触发过于频繁：每条消息都触发 LLM 提取（包括"你好"） | 浪费 API 调用，增加延迟 |
| **P1** | 无语义去重：仅精确字符串匹配去重 | 稍改写就产生重复记忆，记忆库逐渐膨胀 |

### 技术环境

- Java 17, Spring Boot 3.5.8, Spring AI 1.1.0
- PostgreSQL + pgvector, MyBatis
- ChatClient: deepseek-chat / glm-4.6
- Ollama bge-m3 做 embedding
- 项目无 tokenizer 依赖（无 jtokkit 等）

---

## 2. 实施方案

### 阶段 0：基础设施——Token 估算工具

**不引入 tokenizer 依赖**，用字符数近似（`chars / 2.5`）估算 token 数。中英混合场景下 2.5 是保守值（通常中文 1.5-2 chars/token，英文 4 chars/token）。

#### 新建文件

**`backend_v2/src/main/java/com/kama/jchatmind/util/TokenEstimator.java`**

```java
public final class TokenEstimator {
    private static final double CHARS_PER_TOKEN = 2.5;

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) return 0;
        return (int) Math.ceil(text.length() / CHARS_PER_TOKEN);
    }
}
```

#### 新增配置

**`backend_v2/src/main/resources/application.yaml`** 追加：

```yaml
jchatmind:
  memory:
    compression:
      token-threshold: 6000        # 超过此 token 数触发压缩
      max-summary-tokens: 1500     # 摘要自身超过此值时做"摘要的摘要"
      keep-recent-messages: 8      # 压缩时保留最近 N 条消息
    extraction:
      min-new-messages: 3          # 至少新增 N 条用户消息才触发提取
      debounce-seconds: 30         # 同一 session 提取最小间隔
    dedup:
      cosine-threshold: 0.15       # 语义去重阈值（距离 < 0.15 ≈ 相似度 > 85%）
```

---

### 阶段 1：P0-1——修复摘要无限膨胀

**修改文件**：`backend_v2/src/main/java/com/kama/jchatmind/agent/JChatMind.java`

#### 1.1 替换常量（line 68-69）

```java
// 删除
private static final int COMPRESSION_CHAR_THRESHOLD = 8000;
private static final int KEEP_RECENT_MESSAGES = 8;

// 替换为（从配置注入，默认值如下）
private static final int COMPRESSION_TOKEN_THRESHOLD = 6000;
private static final int MAX_SUMMARY_TOKENS = 1500;
private static final int KEEP_RECENT_MESSAGES = 8;
```

#### 1.2 重写 compressMemoryIfNeeded()（line 286-320）

核心变更：摘要不再无限累积，超限时对摘要本身做"摘要的摘要"（collapse）。

```java
private void compressMemoryIfNeeded() {
    List<Message> allMessages = this.chatMemory.get(this.chatSessionId);
    int totalTokens = allMessages.stream()
            .mapToInt(m -> TokenEstimator.estimate(messageText(m)))
            .sum();

    if (totalTokens < COMPRESSION_TOKEN_THRESHOLD) {
        return;
    }

    int keepFrom = Math.max(1, allMessages.size() - KEEP_RECENT_MESSAGES);
    if (keepFrom <= 1) return;
    keepFrom = adjustKeepFromForToolPairs(allMessages, keepFrom);
    if (keepFrom <= 1) return;

    try {
        List<Message> toCompress = new ArrayList<>(allMessages.subList(1, keepFrom));
        String newSummary = generateSummary(toCompress);
        if (!StringUtils.hasText(newSummary)) return;

        // 新摘要替换旧摘要（被压缩的消息已被清除，旧摘要不再有意义）
        // 累积摘要超限时做 collapse
        if (conversationSummary != null && StringUtils.hasText(conversationSummary)) {
            String combined = conversationSummary + "\n" + newSummary;
            if (TokenEstimator.estimate(combined) > MAX_SUMMARY_TOKENS) {
                conversationSummary = collapseSummary(combined);
            } else {
                conversationSummary = combined;
            }
        } else {
            conversationSummary = newSummary;
        }

        allMessages.subList(1, keepFrom).clear();
        allMessages.add(1, new SystemMessage("【对话历史摘要】\n" + conversationSummary));
        log.info("Memory compressed: {} msgs summarized, tokens before: {}", toCompress.size(), totalTokens);
    } catch (Exception e) {
        log.warn("Failed to compress memory", e);
    }
}
```

#### 1.3 新增 collapseSummary()——"摘要的摘要"

当累积摘要超过 `MAX_SUMMARY_TOKENS`（1500 tokens）时，调用 LLM 将摘要二次压缩至关键要点。LLM 调用失败时降级为硬截断。

```java
private String collapseSummary(String summary) {
    try {
        String response = this.chatClient.prompt()
                .system("将以下对话摘要压缩为不超过500字的关键要点，保留用户目标、重要决策和未完成任务。")
                .user(summary)
                .call()
                .content();
        return response != null ? response.trim() : summary;
    } catch (Exception e) {
        log.warn("Failed to collapse summary, truncating");
        return summary.length() > 2000 ? summary.substring(0, 2000) : summary;
    }
}
```

#### 1.4 generateSummary() 中使用 token 估算

将 `messageText(content).length() > 1000` 改为 `TokenEstimator.estimate(content) > 400` 判断截断。

---

### 阶段 2：P0-2——Token 化阈值

已在阶段 1 中一并通过 `TokenEstimator` 实现。额外在 `buildThinkPrompt()` 开头增加 context window 接近上限的 warn 日志。

**修改方法**：`buildThinkPrompt()`（line 237）

```java
private String buildThinkPrompt() {
    // 诊断日志
    int totalTokens = this.chatMemory.get(this.chatSessionId).stream()
            .mapToInt(m -> TokenEstimator.estimate(messageText(m)))
            .sum();
    if (totalTokens > 10000) {
        log.warn("Context size large: ~{} tokens for session {}", totalTokens, chatSessionId);
    }
    // ... 原有逻辑
}
```

---

### 阶段 3：P1-1——提取节流

**修改文件**：`backend_v2/src/main/java/com/kama/jchatmind/service/impl/UserMemoryFacadeServiceImpl.java`

#### 3.1 新增 session 级别提取状态跟踪

```java
private final ConcurrentHashMap<String, ExtractionState> extractionStates = new ConcurrentHashMap<>();

private static class ExtractionState {
    long lastExtractionTime;
    int lastUserMessageCount;
    ExtractionState(long time, int count) {
        this.lastExtractionTime = time;
        this.lastUserMessageCount = count;
    }
}
```

#### 3.2 在 extractMemoryCandidates() 开头增加短路判断

```java
// 节流：至少需要 minNewMessages 条新用户消息
int currentUserMsgCount = userMessages.size();
ExtractionState prev = extractionStates.get(sessionId);
long now = System.currentTimeMillis();

if (prev != null) {
    int newMessages = currentUserMsgCount - prev.lastUserMessageCount;
    if (newMessages < minNewMessagesForExtraction) {
        log.debug("跳过记忆提取 session={}: 仅 {} 条新消息", sessionId, newMessages);
        return;
    }
    if (now - prev.lastExtractionTime < extractionDebounceSeconds * 1000L) {
        log.debug("跳过记忆提取 session={}: 距上次仅 {}ms", sessionId, now - prev.lastExtractionTime);
        return;
    }
}
// ... 原有提取逻辑 ...
extractionStates.put(sessionId, new ExtractionState(now, currentUserMsgCount));
```

#### 3.3 注入配置值

```java
@Value("${jchatmind.memory.extraction.min-new-messages:3}")
private int minNewMessagesForExtraction;

@Value("${jchatmind.memory.extraction.debounce-seconds:30}")
private int extractionDebounceSeconds;
```

---

### 阶段 4：P1-2——语义去重

#### 4.1 UserMemoryMapper 新增方法

**`backend_v2/src/main/java/com/kama/jchatmind/mapper/UserMemoryMapper.java`**：

```java
List<UserMemory> similaritySearchByContent(
    @Param("userId") String userId,
    @Param("vectorLiteral") String vectorLiteral,
    @Param("limit") int limit,
    @Param("threshold") double threshold
);

int updateImportance(@Param("id") String id, @Param("importance") String importance);
```

**`backend_v2/src/main/resources/mapper/UserMemoryMapper.xml`** 追加：

```xml
<select id="similaritySearchByContent" resultMap="BaseResultMap">
    SELECT id, user_id, session_id, memory_type, content, importance,
           evidence_message_id, evidence_text, embedding, created_at, updated_at,
           embedding &lt;=&gt; #{vectorLiteral}::vector AS distance
    FROM user_memory
    WHERE user_id = #{userId}
      AND embedding IS NOT NULL
      AND embedding &lt;=&gt; #{vectorLiteral}::vector &lt; #{threshold}
    ORDER BY embedding &lt;=&gt; #{vectorLiteral}::vector
    LIMIT #{limit}
</select>

<update id="updateImportance">
    UPDATE user_memory SET importance = #{importance}, updated_at = NOW()
    WHERE id = CAST(#{id} AS uuid)
</update>
```

#### 4.2 修改 persistAutomatically()

在精确字符串匹配之后增加语义去重检查。如果语义相似度 > 85%（距离 < 0.15），不插入重复记忆，改为合并 importance（取高值）。

---

### 阶段 5：P2——后续改进（仅记录，不实施）

以下改进标记为后续迭代事项，不在本次实施范围：

- **记忆衰减**：增加 `importance_score`（float 0-1）和 `last_recalled_at` 字段，低活跃记忆自动降权
- **重要性动态调整**：定期批量重评估或召回侧懒评估
- **关键词提取补充 FACT 类型**
- **冲突更新精确匹配**：`handleConflictUpdate()` 中 `findFirst()` 按同 type 删除改为按语义相似度删除
- **记忆类型差异化注入**：CONSTRAINT 用更强指令前缀，FACT 用更轻量前缀
- **短期→长期记忆打通**：`conversationSummary` 内容经提取管线升级为长期记忆

---

## 3. 测试策略

### 新建测试文件

| 文件 | 类型 | 覆盖内容 |
|------|------|---------|
| `TokenEstimatorTest.java` | 纯单元 | 空文本、中文、英文、混合文本、null |
| `JChatMindMemoryCompressionTest.java` | 单元（mock ChatClient） | 阈值以下不压缩、超阈值触发、摘要超限时 collapse、tool pair 完整性、摘要替换非追加 |
| `UserMemoryFacadeServiceImplTest.java` | 集成（@SpringBootTest + test profile） | 节流跳过/触发、精确去重、语义去重、有效记忆持久化、低 importance 过滤、LLM 不可用降级、冲突更新 |

### 验证步骤

1. `mvn test -Dtest="TokenEstimatorTest,JChatMindMemoryCompressionTest,UserMemoryFacadeServiceImplTest"` 通过
2. 长对话测试：同一 session 发送 30+ 条消息，确认 `chatMemory` 规模可控，摘要 token 数不超过 2000
3. 节流验证：同一 session 连续发送 3 次"你好"，确认仅触发 1 次提取
4. 语义去重验证：插入"用户喜欢喝咖啡"后发"我喜欢咖啡"，确认只保留一条记忆
5. 回归验证：`mvn test -Dtest='!*EvaluationTest,!*MultiCpr*'` 确保现有测试通过

---

## 4. 影响范围与风险

| 变更区域 | 风险 | 缓解措施 |
|----------|------|---------|
| JChatMind 压缩逻辑重写 | 压缩行为变化可能影响长对话质量 | 保留最近 8 条不变；summary 仍然注入 SystemMessage |
| Token 估算替代字符计数 | 中英混合/代码场景估算偏差 | 2.5 是保守值；后续可按需替换为 jtokkit |
| 提取节流 | 快速连续重要信息可能漏提取 | 阈值可调（配置驱动）；默认 3 条新消息即触发 |
| 语义去重误判 | 相似但不同的记忆被误去重 | 阈值保守（0.15/85%）；精确匹配仍作为第一道防线 |
| conversationSummary 替换旧摘要 | 极早期信息可能在摘要中被简化 | LLM collapse 时保留"关键话题、重要决策、未完成任务" |

---

## 5. 文件清单

### 新建
- `backend_v2/src/main/java/com/kama/jchatmind/util/TokenEstimator.java`
- `backend_v2/src/test/java/com/kama/jchatmind/util/TokenEstimatorTest.java`
- `backend_v2/src/test/java/com/kama/jchatmind/agent/JChatMindMemoryCompressionTest.java`
- `backend_v2/src/test/java/com/kama/jchatmind/service/impl/UserMemoryFacadeServiceImplTest.java`

### 修改
- `backend_v2/src/main/java/com/kama/jchatmind/agent/JChatMind.java`
- `backend_v2/src/main/java/com/kama/jchatmind/service/impl/UserMemoryFacadeServiceImpl.java`
- `backend_v2/src/main/java/com/kama/jchatmind/mapper/UserMemoryMapper.java`
- `backend_v2/src/main/resources/mapper/UserMemoryMapper.xml`
- `backend_v2/src/main/resources/application.yaml`
- `backend_v2/src/main/resources/application.example.yaml`

### 不改
- `backend_v2/src/main/java/com/kama/jchatmind/agent/JChatMindFactory.java`（JChatMind 构造函数签名如变更需同步调整）
- `backend_v2/src/main/java/com/kama/jchatmind/event/listener/ChatEventListener.java`（提取入口不变，节流逻辑在 service 内部）
