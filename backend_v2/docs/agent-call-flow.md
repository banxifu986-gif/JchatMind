# JChatMind Agent 调用流程详解

## 整体流程概览

```
用户输入 → Think(决策) → [Execute(执行工具)] → Think(再决策) → ... → 结束
```

一次 Agent 调用可能包含多个 Think-Execute 循环，直到模型认为任务完成或达到最大循环次数。

---

## 第一步：chat() — 入口方法

```java
public String chat(String userInput) {
    // 1. 状态检查（Agent 不可重入）
    if (agentState != AgentState.IDLE) {
        throw new IllegalStateException("Agent 状态不是 IDLE");
    }

    try {
        agentState = AgentState.THINKING;

        // 2. 用户消息加入记忆
        UserMessage userMessage = new UserMessage(userInput);
        chatMemory.add(sessionId, userMessage);

        // 3. 执行 Think-Execute 循环（最多20次）
        for (int i = 0; i < MAX_STEPS && agentState != AgentState.FINISHED; i++) {
            step();
        }

        // 4. 从记忆中取出 AI 最终回复
        List<Message> history = chatMemory.get(sessionId);
        String aiResponse = "";
        for (int i = history.size() - 1; i >= 0; i--) {
            Message msg = history.get(i);
            if (msg instanceof AssistantMessage) {
                aiResponse = ((AssistantMessage) msg).getText();
                break;
            }
        }

        return aiResponse;
    } finally {
        agentState = AgentState.IDLE;  // 重置状态
    }
}
```

**核心职责**：入口控制 + 状态管理 + 循环执行 + 结果提取

---

## 第二步：step() — Think + Execute 的最小循环单元

```java
protected void step() {
    if (think()) {
        // 有工具调用 → 执行工具
        execute();
    } else {
        // 没有工具调用 → 结束
        agentState = AgentState.FINISHED;
    }
}
```

**核心含义**：一次 step = 一次决策 +（可选的）一次执行

---

## 第三步：think() — 让模型决定要不要调用工具

```java
protected boolean think() {
    // 1. 构建 Think 阶段的系统提示词
    String thinkPrompt = """
        现在你是一个智能的「决策模块」。
        请根据当前对话上下文，决定下一步的动作。
        如果需要调用工具来完成任务，请调用相应的工具。
        """;

    // 2. 用完整对话历史调用 LLM，并暴露可用工具
    this.lastChatResponse = chatClient
        .prompt(prompt)
        .system(thinkPrompt)
        .toolCallbacks(availableTools.toArray(new ToolCallback[0]))
        .call()
        .chatResponse();

    // 3. 获取模型输出
    AssistantMessage output = this.lastChatResponse.getResult().getOutput();
    List<AssistantMessage.ToolCall> toolCalls = output.getToolCalls();

    // 4. 关键判断：有 tool_calls 吗？
    if (toolCalls.isEmpty()) {
        // 没有工具调用 → 这是最终回复 → 写入记忆
        chatMemory.add(sessionId, output);
        return false;  // → step() 不会调用 execute()
    }

    return true;  // 有工具调用 → step() 会调用 execute()
}
```

**核心职责**：调用 LLM 决策 + 判断是否需要执行工具

---

## 第四步：execute() — 执行工具并回填结果

```java
protected void execute() {
    // 1. 执行工具调用（Spring AI 的 ToolCallingManager）
    ToolExecutionResult toolExecutionResult =
        toolCallingManager.executeToolCalls(prompt, this.lastChatResponse);

    // 2. 更新记忆：用完整历史替换旧记忆
    // 包含：旧记忆 + AssistantMessage(tool_calls) + ToolResponseMessage
    chatMemory.clear(sessionId);
    chatMemory.add(sessionId, toolExecutionResult.conversationHistory());

    // 3. 检查终止工具
    if (toolResponseMessage.getResponses()
            .stream()
            .anyMatch(resp -> resp.name().equals("terminate"))) {
        this.agentState = AgentState.FINISHED;
    }
}
```

**核心职责**：执行工具 + 更新记忆 + 检查终止条件

---

## 流程图解

```
用户: "上海今天天气如何？"
  │
  ▼
┌─────────────────────────────────────┐
│ Think()                             │
│   - 调用 LLM + 暴露工具              │
│   - LLM 返回: 需要调用 weather 工具   │
│   - 发现有 tool_calls，暂不写入记忆   │
│   - 返回 true                        │
└─────────────────────────────────────┘
  │ think() = true
  ▼
┌─────────────────────────────────────┐
│ Execute()                           │
│   - 调用 weatherTool.getWeather()    │
│   - 获取结果："今天上海晴，25度"       │
│   - 生成 ToolResponseMessage        │
│   - 更新 chatMemory                  │
└─────────────────────────────────────┘
  │
  ▼ 回到 step()，再次 Think()
┌─────────────────────────────────────┐
│ Think()                             │
│   - 有 tool 结果，模型决定：直接回答   │
│   - LLM 返回："今天上海晴，25度"      │
│   - 没有 tool_calls，写入记忆         │
│   - 返回 false                       │
└─────────────────────────────────────┘
  │ think() = false
  ▼
  结束，返回最终答案给用户
```

---

## 关键代码解释

### 1. 为什么关闭自动工具执行？

```java
this.chatOptions = DefaultToolCallingChatOptions.builder()
        .internalToolExecutionEnabled(false)  // 关闭自动执行
        .build();
```

**原因**：Agent Loop 需要控制权。如果 Spring AI 自动执行了，Agent 就不知道"执行到哪里了"，无法：
- 记录执行轨迹
- 插入中断/回滚
- 控制循环次数

### 2. 为什么有 tool_calls 时不立刻写入记忆？

```java
if (toolCalls.isEmpty()) {
    chatMemory.add(sessionId, output);  // 最终答案 → 写入
}
// 有 tool_calls → 暂不写入，等 execute() 成功后再统一写入
```

**原因**：防御性思维。如果工具执行失败了，记忆里就不应该有"说要调用工具"的记录。

### 3. MAX_STEPS = 20 的作用？

防止 Agent 无限循环的兜底机制。模型可能一直决定要调用工具，需要有个上限。

---

## 状态机流转

```
         chat() 被调用
              │
              ▼
    ┌─────────────────┐
    │     IDLE        │ ← 初始状态
    └────────┬────────┘
             │ userInput 到来
             ▼
    ┌─────────────────┐
    │   THINKING      │ ← Think 阶段
    └────────┬────────┘
             │ think() 返回
             ▼
    ┌─────────────────┐     ┌─────────────────┐
    │   有 tool_calls  │ ──► │    EXECUTING    │ ← Execute 阶段
    └────────┬────────┘     └────────┬────────┘
             │                       │
             │ 无 tool_calls          │ 执行完毕
             ▼                       ▼
    ┌─────────────────────────────────────┐
    │             FINISHED               │
    └─────────────────────────────────────┘
```
