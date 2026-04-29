# JChatMindV1 & V2 构造函数解析

## V1 构造函数

```java
public JChatMindV1(String name,
                  String description,
                  String systemPrompt,
                  ChatClient chatClient,
                  Integer maxMessages,
                  String sessionId)
```

### V1 参数解析

| 参数 | 类型 | 含义 |
|------|------|------|
| `name` | String | Agent 名称 |
| `description` | String | Agent 描述 |
| `systemPrompt` | String | 系统提示词，定义 Agent 行为 |
| `chatClient` | ChatClient | Spring AI 聊天客户端，负责调用模型 |
| `maxMessages` | Integer | ChatMemory 最大消息数，控制上下文长度 |
| `sessionId` | String | 会话 ID，区分不同对话 |

### V1 核心初始化

```java
this.agentState = AgentState.IDLE;

// 初始化聊天记忆
this.chatMemory = MessageWindowChatMemory.builder()
        .maxMessages(maxMessages != null ? maxMessages : DEFAULT_MAX_MESSAGES)
        .build();

// 添加系统提示词
if (StringUtils.hasLength(systemPrompt)) {
    this.chatMemory.add(this.sessionId, new SystemMessage(systemPrompt));
}
```

V1 的核心职责：**初始化基础组件**（ChatClient、ChatMemory、系统提示词）

---

## V2 构造函数

```java
public JChatMindV2(String name,
                  String description,
                  String systemPrompt,
                  org.springframework.ai.chat.client.ChatClient chatClient,
                  Integer maxMessages,
                  String sessionId,
                  List<ToolCallback> availableTools)
```

### V2 新增参数

| 参数 | 类型 | 含义 |
|------|------|------|
| `availableTools` | List\<ToolCallback\> | Agent 可调用的外部工具列表 |

### V2 构造函数的三个关键动作

#### 1. 调用父类构造

```java
super(name, description, systemPrompt, chatClient, maxMessages, sessionId);
```

继承自 V1，V1 已经初始化了 `chatMemory`、`chatClient` 等基础组件。

#### 2. 关闭自动工具执行

```java
this.chatOptions = DefaultToolCallingChatOptions.builder()
        .internalToolExecutionEnabled(false)
        .build();
```

**重要**：Spring AI 默认会自动执行工具，但这里选择**手动接管**，让 Agent Loop 控制执行权。

#### 3. 初始化工具调用管理器

```java
this.toolCallingManager = ToolCallingManager.builder().build();
```

统一管理工具的执行、结果处理等。

---

## V1 vs V2 架构变化

| | V1 | V2 |
|---|---|---|
| 对话模式 | 问一句答一句 | Think-Execute 循环 |
| 工具调用 | 不支持 | 支持 |
| 执行控制 | 模型自动执行 | 手动接管 |
| 状态管理 | 无 | 有状态机 |
| 参数数量 | 6 个 | 7 个（新增 availableTools） |

## 设计意图

```
V1: 用户问 → 模型答（一次性）
V2: 用户问 → Think(决策) → Execute(执行工具) → 循环直到完成
```

V2 在 V1 基础上**扩展**，不推翻原有结构：

- V1 负责：**基础聊天能力**（ChatClient + ChatMemory）
- V2 负责：**工具调用能力**（Agent Loop + ToolCallingManager）

V2 继承 V1 的构造函数，复用 V1 的基础组件，只新增 V2 特有的逻辑。体现了良好的**开闭原则**：扩展而非修改。
