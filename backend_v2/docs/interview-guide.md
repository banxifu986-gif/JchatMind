# JChatMind Agent 面试要点

## 目录

1. [Agent Loop 实现](#一agent-loop-实现)
2. [工具调用实现](#二工具调用实现)
3. [RAG 检索实现](#三rag-检索实现)
4. [多模型支持实现](#四多模型支持实现)
5. [SSE 实时推送实现](#五sse-实时推送实现)

---

## 一、Agent Loop 实现

### 1.1 核心代码

```java
public void run() {
    if (agentState != AgentState.IDLE) {
        throw new IllegalStateException("Agent is not idle");
    }

    try {
        for (int i = 0; i < MAX_STEPS && agentState != AgentState.FINISHED; i++) {
            step();
            if (currentStep >= MAX_STEPS) {
                agentState = AgentState.FINISHED;
            }
        }
        agentState = AgentState.FINISHED;
    } catch (Exception e) {
        agentState = AgentState.ERROR;
    }
}

private void step() {
    if (think()) {
        execute();
    } else {
        agentState = AgentState.FINISHED;
    }
}
```

### 1.2 每行代码作用

| 代码 | 作用 |
|------|------|
| `agentState != AgentState.IDLE` | 状态检查，防止重入 |
| `for (i < MAX_STEPS)` | 循环上限兜底，防止无限循环 |
| `step()` | Think + Execute 的最小循环单元 |
| `AgentState.FINISHED` | 正常结束状态 |

### 1.3 设计思路

**为什么需要 Agent Loop？**

V1 是"问一句答一句"，V2 需要让 Agent 能自主决定是否调用工具。Loop 实现了：
- Think（决策）→ Execute（执行）→ Think（再决策）→ ... → 结束

**为什么用 MAX_STEPS = 20 兜底？**

模型可能一直决定要调用工具，没有上限会导致死循环。

**为什么关闭自动工具执行？**

```java
this.chatOptions = DefaultToolCallingChatOptions.builder()
        .internalToolExecutionEnabled(false)
        .build();
```

如果让 Spring AI 自动执行，Agent 就失去：
- 执行轨迹的记录能力
- 中断/回滚的机会
- 对"是否继续"的判断权

### 1.4 改进方向

- **动态 MAX_STEPS**：根据任务复杂度调整上限
- **执行超时机制**：单个 step 超时则中断
- **执行轨迹可视化**：记录每步决策原因

---

## 二、工具调用实现

### 2.1 核心代码

```java
// Think 阶段：让模型决定是否调用工具
private boolean think() {
    String thinkPrompt = """
        现在你是一个智能的「决策模块」
        请根据当前对话上下文，决定下一步的动作。
        【额外信息】
        - 你目前拥有的知识库列表以及描述：%s
        """.formatted(this.availableKbs);

    this.lastChatResponse = this.chatClient
        .prompt(prompt)
        .system(thinkPrompt)
        .toolCallbacks(this.availableTools.toArray(new ToolCallback[0]))
        .call()
        .chatResponse();

    AssistantMessage output = this.lastChatResponse.getResult().getOutput();
    List<AssistantMessage.ToolCall> toolCalls = output.getToolCalls();

    // 关键：有工具调用才执行
    return !toolCalls.isEmpty();
}

// Execute 阶段：执行工具
private void execute() {
    ToolExecutionResult result = toolCallingManager.executeToolCalls(prompt, this.lastChatResponse);

    this.chatMemory.clear(this.chatSessionId);
    this.chatMemory.add(this.chatSessionId, result.conversationHistory());

    // 检查终止工具
    if (toolResponseMessage.getResponses()
            .stream()
            .anyMatch(resp -> resp.name().equals("terminate"))) {
        this.agentState = AgentState.FINISHED;
    }
}
```

### 2.2 每行代码作用

| 代码 | 作用 |
|------|------|
| `.toolCallbacks(availableTools)` | 向模型暴露可用工具 |
| `output.getToolCalls()` | 获取模型的工具调用请求 |
| `ToolCallingManager.executeToolCalls()` | 执行工具调用 |
| `chatMemory.clear() + add()` | 更新记忆，保留完整历史 |
| `toolResponseMessage.getResponses()` | 获取工具执行结果 |
| `.name().equals("terminate")` | 检查是否调用了终止工具 |

### 2.3 设计思路

**为什么有 tool_calls 时不立刻写入记忆？**

防御性思维：如果工具执行失败，记忆里就不应该有"说要调用工具"的记录。

```java
if (toolCalls.isEmpty()) {
    chatMemory.add(sessionId, output);  // 最终答案 → 写入
}
// 有 tool_calls → 暂不写入，等 execute() 成功后再统一写入
```

**为什么要 clear 再 add？**

因为 `conversationHistory()` 返回的是新对象，直接 add 会导致记忆重复。

**为什么要持久化 ToolResponseMessage？**

确保对话历史完整，支持后续恢复和审计。

### 2.4 改进方向

- **并行工具执行**：多个独立工具可并行调用
- **工具调用失败重试**：失败后自动重试 N 次
- **工具执行超时**：单个工具执行超时则跳过

---

## 三、RAG 检索实现

### 3.1 核心代码

```java
// KnowledgeTools.java - Agent 调用入口
public String knowledgeQuery(String kbsId, String query) {
    List<String> strings = ragService.similaritySearch(kbsId, query);
    return String.join("\n", strings);
}

// RagServiceImpl.java - Embedding 生成
private float[] doEmbed(String text) {
    EmbeddingResponse resp = webClient.post()
        .uri("/api/embeddings")
        .bodyValue(Map.of("model", "bge-m3", "prompt", text))
        .retrieve()
        .bodyToMono(EmbeddingResponse.class)
        .block();
    return resp.getEmbedding();
}

// 向量检索
public List<String> similaritySearch(String kbId, String title) {
    String queryEmbedding = toPgVector(doEmbed(title));
    List<ChunkBgeM3> chunks = chunkBgeM3Mapper.similaritySearch(kbId, queryEmbedding, 3);
    return chunks.stream().map(ChunkBgeM3::getContent).toList();
}

// SQL 检索
<select id="similaritySearch" resultMap="BaseResultMap">
    SELECT ... FROM chunk_bge_m3
    WHERE kb_id = CAST(#{kbId} AS uuid)
    ORDER BY embedding <-> #{vectorLiteral}::vector
    LIMIT #{limit}
</select>
```

### 3.2 每行代码作用

| 代码 | 作用 |
|------|------|
| `webClient.post().uri("/api/embeddings")` | 调用 Ollama 的 Embedding API |
| `bge-m3` | Embedding 模型名称（支持多语言） |
| `toPgVector()` | 将 float[] 转为 PostgreSQL 向量格式 `[0.123,-0.456,...]` |
| `embedding <-> vector` | pgvector 欧氏距离计算 |
| `LIMIT 3` | 返回最相似的 3 个 chunk |

### 3.3 设计思路

**为什么要用 bge-m3 模型？**

- 支持多语言（中文效果好）
- 生成 1024 维向量
- 通过 Ollama 本地部署，无需 API 调用

**为什么不直接用 pgvector 存储原始文本？**

向量数据库的优势是**语义相似度检索**，而不是精确匹配。

**为什么返回 top-3 而不是 top-1？**

单一结果可能不完整，3 个结果可以提供更多上下文。

### 3.4 改进方向

- **混合检索**：结合关键词检索（BM25）和向量检索
- **重排序**：用更大的模型对 top-K 结果重排序
- **动态 top-K**：根据查询复杂度动态调整返回数量

---

## 四、多模型支持实现

### 4.1 核心代码

```java
// ChatClientRegistry.java - 模型注册表
@Component
public class ChatClientRegistry {
    private final Map<String, ChatClient> chatClients;

    public ChatClientRegistry(Map<String, ChatClient> chatClients) {
        this.chatClients = chatClients;
    }

    public ChatClient get(String key) {
        return chatClients.get(key);
    }
}

// JChatMindFactory.java - 根据配置创建 Agent
private JChatMind buildAgentRuntime(Agent agent, ...) {
    ChatClient chatClient = chatClientRegistry.get(agent.getModel());
    if (Objects.isNull(chatClient)) {
        throw new IllegalStateException("未找到对应的 ChatClient: " + agent.getModel());
    }
    return new JChatMind(..., chatClient, ...);
}
```

### 4.2 每行代码作用

| 代码 | 作用 |
|------|------|
| `Map<String, ChatClient> chatClients` | 存储多个模型的 ChatClient |
| Spring 自动注入 | 所有 ChatClient Bean 会被自动注入 |
| `.get(key)` | 根据模型名称获取对应 ChatClient |
| `agent.getModel()` | 从数据库读取 Agent 配置的模型 |

### 4.3 设计思路

**为什么用注册表模式？**

- 避免 if-else 判断模型类型
- 新增模型只需配置 Bean，无需改代码
- 符合开闭原则

**为什么用 Spring 自动注入 Map？**

```java
public ChatClientRegistry(Map<String, ChatClient> chatClients) {
    this.chatClients = chatClients;
}
```

Spring 会自动把所有 ChatClient 类型的 Bean 注入到这个 Map，key 是 Bean 名称。

### 4.4 改进方向

- **模型降级**：主模型失败时自动切换到备选模型
- **模型路由**：根据任务类型自动选择最优模型
- **负载均衡**：多实例时分配不同模型

---

## 五、SSE 实时推送实现

### 5.1 核心组件

| 组件 | 作用 |
|------|------|
| `SseController` | HTTP 端点，建立 SSE 连接 |
| `SseService` | 管理连接池，发送消息 |
| `SseEmitter` | Spring 封装的 SSE 发送器 |
| `SseMessage` | 消息格式定义 |

### 5.2 连接建立（SseController）

```java
@RestController
@RequestMapping("/sse")
public class SseController {

    @RequestMapping(value = "/connect/{chatSessionId}",
                    produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter connect(@PathVariable String chatSessionId) {
        return sseService.connect(chatSessionId);
    }
}
```

**关键点**：
- `produces = MediaType.TEXT_EVENT_STREAM_VALUE` 声明返回类型是 SSE
- `chatSessionId` 作为连接标识

### 5.3 连接管理（SseServiceImpl）

```java
public class SseServiceImpl implements SseService {

    // 用 ConcurrentHashMap 存储所有活跃的 SSE 连接
    private final ConcurrentMap<String, SseEmitter> clients = new ConcurrentHashMap<>();

    @Override
    public SseEmitter connect(String chatSessionId) {
        // 创建 SSE emitter，超时时间 30 分钟
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        clients.put(chatSessionId, emitter);

        // 发送连接成功事件
        emitter.send(SseEmitter.event().name("init").data("connected"));

        // 设置回调：连接断开时移除
        emitter.onCompletion(() -> clients.remove(chatSessionId));
        emitter.onTimeout(() -> clients.remove(chatSessionId));
        emitter.onError((error) -> clients.remove(chatSessionId));

        return emitter;
    }

    @Override
    public void send(String chatSessionId, SseMessage message) {
        SseEmitter emitter = clients.get(chatSessionId);
        if (emitter != null) {
            String sseMessageStr = objectMapper.writeValueAsString(message);
            emitter.send(SseEmitter.event().name("message").data(sseMessageStr));
        }
    }
}
```

### 5.4 消息格式（SseMessage）

```java
@Data
@Builder
public class SseMessage {
    private Type type;        // 消息类型
    private Payload payload;  // 消息内容
    private Metadata metadata;// 元数据

    public enum Type {
        AI_GENERATED_CONTENT,  // AI 生成内容
        AI_PLANNING,           // AI 规划中
        AI_THINKING,           // AI 思考中
        AI_EXECUTING,          // AI 执行中
        AI_DONE,               // AI 完成
    }
}
```

### 5.5 Agent 中使用 SSE（JChatMind）

```java
// 待发送的消息队列
private final List<ChatMessageDTO> pendingChatMessages = new ArrayList<>();

// 持久化并发送消息
private void saveMessage(Message message) {
    // 1. 持久化到数据库
    CreateChatMessageResponse chatMessage = chatMessageFacadeService.createChatMessage(chatMessageDTO);
    pendingChatMessages.add(chatMessageDTO);
}

// 刷新 pendingMessages，将数据通过 SSE 发送给前端
private void refreshPendingMessages() {
    for (ChatMessageDTO message : pendingChatMessages) {
        ChatMessageVO vo = chatMessageConverter.toVO(message);
        SseMessage sseMessage = SseMessage.builder()
                .type(SseMessage.Type.AI_GENERATED_CONTENT)
                .payload(SseMessage.Payload.builder()
                        .message(vo)
                        .build())
                .metadata(SseMessage.Metadata.builder()
                        .chatMessageId(message.getId())
                        .build())
                .build();
        sseService.send(this.chatSessionId, sseMessage);
    }
    pendingChatMessages.clear();
}
```

### 5.6 完整流程图

```
前端                              后端                              Agent
 │                                 │                                 │
 │  GET /sse/connect/session1     │                                 │
 │────────────────────────────────►│                                 │
 │                                 │ 创建 SseEmitter                 │
 │                                 │ 加入 clients Map                 │
 │◄────────────────────────────────│                                 │
 │   SSE: init = connected        │                                 │
 │                                 │                                 │
 │                                 │  chat("用户问题")               │
 │                                 │────────────────────────────────►│
 │                                 │                                 │
 │                                 │  Think() 决策中...              │
 │                                 │◄────────────────────────────────│
 │◄────────────────────────────────│  SSE: AI_THINKING              │
 │   SSE: AI_THINKING              │                                 │
 │                                 │                                 │
 │                                 │  Execute() 执行工具中...        │
 │                                 │◄────────────────────────────────│
 │◄────────────────────────────────│  SSE: AI_EXECUTING              │
 │   SSE: AI_EXECUTING             │                                 │
 │                                 │                                 │
 │                                 │  返回最终答案                   │
 │                                 │◄────────────────────────────────│
 │◄────────────────────────────────│  SSE: AI_GENERATED_CONTENT      │
 │   SSE: AI_GENERATED_CONTENT     │                                 │
```

### 5.7 设计思路

**为什么用 pendingChatMessages？**

- 减少 SSE 请求次数（批量发送）
- 保证消息顺序
- 支持重试机制

**为什么用 ConcurrentHashMap？**

- SSE 是长连接，多线程并发访问
- 需要线程安全

**为什么有多种消息类型？**

- 前端可以根据 type 显示不同的 UI 状态（思考中、执行中、完成）

---

## 面试常见问题

### Q1: Agent Loop 和 ReAct 是什么关系？

**答**：ReAct（Reasoning + Acting）是一种让模型交替进行推理和执行的策略。Agent Loop 是 ReAct 的工程实现，通过 Think-Execute 循环模拟这个过程。

### Q2: 为什么关闭自动工具执行？

**答**：
1. **控制权**：Agent 需要知道执行到哪一步
2. **可观测性**：需要记录执行轨迹
3. **灵活性**：支持中断、回滚、动态决策

### Q3: RAG 和向量数据库是什么关系？

**答**：
- RAG = 检索 + 生成，是一种架构模式
- 向量数据库（如 pgvector）是 RAG 的存储层
- RAG 利用向量数据库做语义检索

### Q4: 短期记忆和长期记忆的区别？

**答**：
- **短期记忆**：ChatMemory，内存中，会话结束丢失
- **长期记忆**：持久化存储，跨会话保留
- 当前项目：对话用 ChatMemory，知识库用 pgvector

### Q5: 多模型切换的原理？

**答**：通过 ChatClientRegistry 注册表，根据 Agent 配置的 model 字段，从 Map 中获取对应的 ChatClient 实例。
