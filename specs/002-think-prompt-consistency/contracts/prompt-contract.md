# Prompt Contract: agent-think-prompt-v1

本特性唯一的接口契约是提示词文本契约。生产提示词新增约束段须与评测版 `citation-minimal-v3` 语义逐条对应，下表为映射基准，实施与验收时逐条核对。

## 1. 约束映射表（citation-minimal-v3 → 生产约束段）

| # | citation-minimal-v3 原文（英文） | 生产约束段对应（中文，语义等价） |
|---|---|---|
| C1 | Answer only from the supplied retrieved context | 回答知识库类问题时，仅依据本轮 KnowledgeTool 返回的内容作答 |
| C2 | state that the evidence is insufficient and abstain without a citation marker | 检索结果与问题不相关或不足以支持回答时，明确说明无法从知识库回答，不要输出引用标记 |
| C3 | Every factual claim must have a direct supporting citation using the exact `引用: <runtime chunk UUID>` marker present in its supporting chunk; cite each supporting chunk at most once; use the minimum number of citation markers | 事实性陈述须引用支撑它的检索结果：逐字使用工具返回中"引用:"后的标识，每个标识至多使用一次，只引用必要的结果 |
| C4 | Never invent or alter a citation ID | 不得编造、修改或拼接任何未出现在工具返回中的引用标识 |

## 2. 落点契约

### 2.1 think prompt 追加段（JChatMind.buildThinkPrompt）

- 位置：静态文本块内，【最终回答排版要求】段之后、【额外信息】段之前
- 段落标题：`【引用与拒答】`
- 内容：C1–C4 的中文表述（可用一条列表呈现，语义不得缺失任何一条）
- 既有段落（工具选择规则、排版要求、额外信息及全部条件追加段）逐字不变

### 2.2 版本锚点（JChatMind）

- 常量：`PROMPT_VERSION = "agent-think-prompt-v1"`（public 或 package 可见，风格随类内既有常量）
- 日志：think prompt 构建路径输出一行含版本号的日志，debug 或 info 级别均可

### 2.3 工具返回说明行（KnowledgeTools / McpKnowledgeTool）

- 位置：`formatResults` 多块拼接完成后追加一行（MCP 侧对应拼接处同款）
- 文案（语义基准，措辞可微调）：`回答中引用知识库内容时，请逐字使用上述"引用:"后的标识，不得编造或改动。`
- 单个结果块（`formatResultBlock`）字段、顺序、JSON 结构零变化

## 3. 不变量

- `G2CurrentFixtureAnswerGenerationEvaluator.SYSTEM_PROMPT` 及其 SHA-256 不变（评测侧冻结）
- 工具返回块既有字段与格式不变（现有消费方——模型与任何对该文本做断言的测试——无回归）
- think prompt 既有段落文本逐字不变
