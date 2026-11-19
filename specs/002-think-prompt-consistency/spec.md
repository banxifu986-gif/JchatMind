# Feature: Agent 主循环提示词与评测提示词一致化（引用与拒答约束下沉生产）

**Feature Branch**: [独立 spec 目录，与 git 分支解耦]
**Created**: 2026-11-17
**Status**: Draft
**Input**: docs/wiki/17-提示词工程优化方案.md §3 已收敛决策与 §4 实施路径（S1-S4），经 grill-me 评审收敛

## 概述

生产 Agent 主循环 think prompt（`JChatMind.buildThinkPrompt`）目前没有任何引用格式与拒答行为的约束，而带严格约束的提示词（`citation-minimal-v3`）只存在于评测 harness 的答案生成 evaluator 中。这导致两个后果：线上回答的引用与拒答行为弱于评测假设，评测指标反映的是"理想提示词下的能力上限"而非线上行为；且主循环提示词无版本追踪，评测门禁依赖的"提示词不变"前提无法验证。

本特性把评测版引用/拒答约束提炼后融入生产主循环提示词（改生产侧，评测 harness 不动），给主循环提示词打版本号锚点，并以现有评测骨架（L1 快速回归 + LLM judge 20 条）做改动前后对比。

**明确的非目标**（本轮不做）：few-shot 示例、结构化输出/JSON mode、token 化上下文截断、视觉 provider 提示词合并、temperature 参数接通、后处理引用校验——均见 wiki/17 §3 第 7 条，留待基线分数定位后逐项立项。

## Clarifications

### Session 2026-11-17

- Q: 改动后 judge 分数相对基线的回退判定规则？ → A: 下降 ≤0.05 视为持平；首次对比下降在 0.05~0.10 区间时重跑一次 judge，取两次较好结果判定；下降 >0.10 判定为回退，标红人工评估
- Q: PROMPT_VERSION 本轮溯源落地程度？ → A: 版本常量 + 提示词构建时一行日志输出；评测报告结构本轮不动，报告携带 PROMPT_VERSION 留到评测侧改造项（wiki/17 §5 第 3 条）

## 用户场景与测试

### 场景 1：用户提问知识库可回答的问题（主流程）

用户在会话中问一个知识库内有答案的问题，Agent 检索到相关 chunk 后回答。回答中的引用来自本轮 KnowledgeTool 真实返回的 chunk 标识，格式与工具返回一致；用户可见的回答不出现编造的引用标识。

**验收**：judge 20 条 faithfulness/relevancy 不低于改动前基线；回答中出现的引用标识均在对应检索结果集合内（方向性观测，不做后处理强校验）。

### 场景 2：用户提问知识库无法回答的问题

用户问知识库外的问题，检索结果不相关时，Agent 明确说明无法从知识库回答，而不是硬编。

**验收**：judge 评测中拒答类 case 行为与改动前不回退；L1 快速回归的拒答代理信号不回退。

### 场景 3：维护者做提示词回归对比

维护者修改主循环提示词后，能通过版本号在评测报告/日志中区分改动前后，并跑 L1 快速回归 + judge 20 条做方向性对比。

**验收**：`RagFastRegressionEvaluatorTest` 零依赖通过；judge 20 条结果可落盘归档并与基线对比。

### 场景 4：存量行为不回退

Agent 工具调用决策、MCP 侧知识工具、意图路由、记忆链路行为不因本次改动变化。

**验收**：`mvnw.cmd test` 基线通过（1104 项 0 失败口径，宪法原则 VII）；工具返回块仅追加说明行，现有解析方无回归。

### 边界场景

- 模型不遵守引用格式约束：本轮不做后处理校验，靠 judge 分数观测；分数不达标再立项后处理方案（wiki/17 §3 第 5 条）
- 约束段与主循环既有规则段冲突（如排版要求）：约束段以追加段落形式融入，不改写既有规则文本，冲突靠 judge 观测
- judge 样本 20 条的噪声：只做方向性对比，不下显著性结论；回退判定按 Clarifications 口径执行（≤0.05 持平，0.05~0.10 重跑取较好，>0.10 判回退）

## 需求

### 功能需求

**R1 主循环提示词版本锚点**
- R1.1 主循环 think prompt 增加版本号常量，命名对齐现有惯例（`intent-classifier-prompt-v2` 同款模式），初值 `agent-think-prompt-v1`
- R1.2 版本号可被日志溯源：提示词构建时输出一行含版本号的日志；评测报告结构本轮不动（Clarifications Session 2026-11-17）

**R2 引用与拒答约束融入生产提示词**
- R2.1 从评测版 `citation-minimal-v3` 提炼引用约束，适配工具调用路径语义：只允许引用本轮 KnowledgeTool 真实返回的 chunk 标识（工具返回格式 `引用: <chunkId>`），不得编造引用
- R2.2 融入拒答约束：检索结果与问题不相关时明确告知无法从知识库回答
- R2.3 以追加段落形式融入 `buildThinkPrompt`，不改写、不删除既有规则文本（最小精准修改，宪法原则 V）
- R2.4 评测 harness（`G2CurrentFixtureAnswerGenerationEvaluator` 等）提示词保持不变

**R3 工具返回说明行**
- R3.1 KnowledgeTool 与 McpKnowledgeTool 的返回块尾部追加一行引用格式说明，与既有 `引用: <chunkId>` 输出自洽
- R3.2 返回块既有字段与 JSON 结构不变，仅追加说明文本

**R4 基线对比与归档**
- R4.1 改动前先跑一次基线：L1 快速回归 + judge 20 条（judge 需 API key，走现有系统属性门控）
- R4.2 改动后重跑同口径评测，结果落盘归档并给出方向性对比结论
- R4.3 L1 快速回归零依赖通过；`mvnw.cmd test` 整体基线不回退

### 约束

- 宪法原则 V（最小精准修改）：改动仅限主循环提示词、版本常量、两个工具返回块追加说明行；不顺手重构
- 宪法原则 I（单机演示防御校准）：不引入任何新防御机制；本轮不做后处理校验层
- 宪法原则 VII（基线先行验证）：只用现有校验入口，不自创校验方式
- 不新增任何依赖
- 涉及文件预计 ≤3 个：`JChatMind.java`、`KnowledgeTools.java`、`McpKnowledgeTool.java`（评测运行与报告归档不产生源码改动）

## 成功标准

1. 线上回答出现编造引用的比例下降（judge 20 条 faithfulness 方向性提升或持平，判定口径见 Clarifications：下降 ≤0.05 持平；0.05~0.10 重跑一次取较好结果；>0.10 判回退）
2. 检索不相关时，回答出现明确拒答而非硬编（方向性观测）
3. L1 快速回归全部通过，检索指标（Recall@5 等）不回退
4. `mvnw.cmd test` 基线通过，无存量测试失败
5. 主循环提示词可凭版本号区分改动前后
6. judge 20 条改动前后对比报告落盘归档

## 关键实体

- **think prompt**：Agent 每轮决策提示词，`JChatMind.buildThinkPrompt` 产出，含工具选择规则、排版要求、知识库列表、运行进度、对话摘要
- **PROMPT_VERSION**：提示词版本常量，模式对齐 `intent-classifier-prompt-v2` / `image-extraction-prompt-v1`
- **citation-minimal-v3**：评测 harness 答案生成提示词版本（`G2CurrentFixtureAnswerGenerationEvaluator`），本特性的约束来源，本身不改
- **KnowledgeTool 返回块**：工具返回给模型的检索结果 JSON，含 chunk 内容与 `引用: <chunkId>` 标识

## 假设

- judge 评测运行环境可用（API key / 火山端点，wiki/14 记录 DeepSeek 欠费走火山端点）；若 judge 无法运行，S4 退化为仅 L1 快速回归对比，并在归档中如实记录
- 约束段融入后模型（deepseek-chat / glm-4.6）能理解追加段与既有规则的关系，无需重排既有文本

## 依赖

- docs/wiki/17-提示词工程优化方案.md（决策与验收口径）
- docs/wiki/14-评测体系与门禁.md（评测入口、judge 门控、归档惯例）
- 现有评测骨架：`RagFastRegressionEvaluatorTest`、answer-quality evaluator（系统属性门控）

## 阶段拆分（步骤 → 验收项）

| 步骤 | 内容 | 验收项 |
| --- | --- | --- |
| S0 | 跑改动前基线（L1 + judge 20 条）并归档 | 基线报告落盘 |
| S1 | `JChatMind` 增加 `PROMPT_VERSION` 常量 | 编译 + 既有测试通过 |
| S2 | think prompt 追加引用与拒答约束段 | 编译 + L1 快速回归通过 |
| S3 | KnowledgeTool/McpKnowledgeTool 返回块尾追加说明行 | 编译 + 既有测试通过，无解析方回归 |
| S4 | 改动后重跑同口径评测，归档对比结论 | judge faithfulness/relevancy 按 Clarifications 口径不回退（≤0.05 持平；0.05~0.10 重跑取较好；>0.10 判回退）；`mvnw.cmd test` 不回退 |
