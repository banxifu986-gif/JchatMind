# Research: 主循环提示词一致化设计决策

## D1. 约束段以追加段落融入，不改写既有规则文本

**Decision**: 在 `buildThinkPrompt` 的静态文本块中新增一个 `【引用与拒答】` 段落，插入在【最终回答排版要求】之后、【额外信息】之前；既有工具选择规则与排版要求逐字不动。

**Rationale**: 宪法原则 V（最小精准修改）。think prompt 是拼接式结构（静态块 + 条件追加段），新增段落是最小侵入方式；重排既有文本会放大 diff 且无收益。

**Alternatives**: 重写整个静态块统一语气（否决：diff 大、不可溯源）；约束做成条件段仅在有检索结果时插入（否决：拒答约束在无检索结果时同样需要生效）。

## D2. 约束段内容从 citation-minimal-v3 提炼为中文，适配工具调用语义

**Decision**: 评测版 SYSTEM_PROMPT（`G2CurrentFixtureAnswerGenerationEvaluator.java:23-32`）的四条核心约束——仅据提供的检索内容作答、证据不足时明确说明并拒答、事实性陈述须带 `引用: <chunkId>` 标记（逐字使用工具返回的标识、每 chunk 至多引一次、用最少数量）、绝不编造或改动引用 ID——提炼为中文约束段，并把"supplied context"转译为"本轮 KnowledgeTool 返回的内容"。

**Rationale**: 生产走工具调用路径，检索内容以工具返回块（`KnowledgeTools.formatResultBlock`，含 `引用: <chunkId>` 行）形式出现在对话中，语义对应需要显式点明，否则模型不知道"引用标记从哪来"。

**Alternatives**: 直接照搬英文原文（否决：主循环提示词全中文，混入英文段落指令遵循降级）；约束只写"不要编造引用"一句（否决：丢失拒答与最少引用约束，评测对齐目标落空）。

## D3. PROMPT_VERSION 常量 + 一行日志，评测报告不动

**Decision**: `JChatMind` 增加常量 `PROMPT_VERSION = "agent-think-prompt-v1"`，在 think prompt 构建处（或 Agent 运行入口）打一行 debug/info 日志输出版本号。评测报告结构本轮不改。

**Rationale**: Clarifications 收敛结论；对齐 `ChatClientIntentModelClient.PROMPT_VERSION`（intent-classifier-prompt-v2）既有惯例，零风险锚点。

**Alternatives**: 写入评测报告字段（推迟到 wiki/17 §5 评测侧改造项）。

## D4. 工具返回块追加说明行的位置与文案

**Decision**: 不改 `formatResultBlock` 单块结构，在 `formatResults` 拼接结果后追加一行总说明（两处：`KnowledgeTools.formatResults:481-488`、`McpKnowledgeTool` 对应拼接处），文案示意：`回答中引用知识库内容时，请逐字使用上述"引用:"后的标识，不得编造或改动。` 单块字段与 JSON 结构零变化。

**Rationale**: 说明行是面向整体的规则，逐块重复会膨胀 token 且稀释信号；追加在拼接层对现有解析方（模型消费文本，无程序解析该文本）零影响。

**Alternatives**: 每个结果块内追加（否决：N 倍 token）；不追加、只靠 think prompt 约束（否决：MCP 路径外部客户端不经主循环 think prompt，工具侧自含说明更稳）。

## D5. 基线与对比的执行口径

**Decision**: S0 基线 = L1 `RagFastRegressionEvaluatorTest`（零依赖）+ judge 20 条（现有系统属性门控，环境按 wiki/14 记录走火山端点）；S4 同口径重跑，归档进 specs/002 目录（对齐 001 的 *.log 归档惯例）。judge 不可用时退化为仅 L1 对比并如实记录。

**Rationale**: 宪法原则 VII 只用现有校验入口；001 spec 已建立 log 归档先例。

**Alternatives**: 临时手写评测脚本（否决：自创校验方式，违宪）。

## D6. 已知风险与观测项

- judge 评测路径不是 Agent 主循环（wiki/17 §5 错位），faithfulness 提升可能不显著——对比结论如实记录，不强行解读
- 约束段增加约 150-200 中文字符/轮，token 开销可忽略
- glm-4.6 与 deepseek-chat 双模型下指令遵循差异：本次不区分模型评估，作为观测项记录
