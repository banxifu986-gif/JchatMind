# ToolCall 出现时，为什么不立刻写入 Memory

## 核心原因：工具可能失败

当你把 `tool_calls` 的 AssistantMessage 写入 memory 后，如果后续工具执行出错了，memory 里的内容就会和实际情况**不一致**。

## 具体场景

假设对话是这样的：

```
用户：上海今天多少度？
模型：[有 tool_calls] {name: "weather", args: {city: "上海"}}
```

**错误做法**：立刻写入 memory

```
Memory: [用户消息, 模型回复(带tool_calls)]
```

然后工具执行时发现 API 挂了，返回错误。但 memory 里已经有那个"带 tool_calls 的回复"了，这就产生了**脏数据**。

## 正确做法：等执行成功再写入

```
1. Think 阶段：发现有 tool_calls，先不写入
2. Execute 阶段：执行工具，成功
3. Execute 完成后：统一写入 memory
```

这样 memory 里永远都是**确定的事实**，不会有"说了要调工具但不知道调没调成功"的状态。

## 代码逻辑

```java
if (toolCalls.isEmpty()) {
    // 没有工具调用 = 最终答案 → 写入
    chatMemory.add(sessionId, output);
}
// 有工具调用 = 不写入，等 execute 完成后统一处理
```

## 总结

这是**工程上的防御性思维**：不写入不确定的内容，等结果确定了再落笔。
