# Implementation Plan: Agent 主循环提示词与评测提示词一致化

**Branch**: main（spec 目录与分支解耦，实施时按需开分支）
**Spec**: [spec.md](./spec.md) | **Checklist**: [checklists/requirements.md](./checklists/requirements.md)
**Date**: 2026-11-17

## Technical Context

| 维度 | 取值 |
|---|---|
| 运行环境 | Windows 11 + Git Bash；Java 21 / Spring Boot / Spring AI |
| 改动模块 | `backend_v2/src/main/java/com/kama/jchatmind/agent/JChatMind.java`（think prompt + 版本常量）、`agent/tools/KnowledgeTools.java`、`mcp/McpKnowledgeTool.java`（各追加一行说明） |
| 约束来源 | `backend_v2/src/test/java/com/kama/jchatmind/rag/G2CurrentFixtureAnswerGenerationEvaluator.java:23-32`（citation-minimal-v3，只读不改） |
| 评测入口 | L1：`mvnw.cmd test -Dtest=RagFastRegressionEvaluatorTest`（零依赖）；judge：现有系统属性门控 answer-quality evaluator（限 20 条，API key 走现有配置） |
| 基线口径 | `mvnw.cmd test` 全量（宪法原则 VII：1104 项 0 失败） |
| 版本锚点 | `agent-think-prompt-v1`，对齐 `intent-classifier-prompt-v2` 惯例 |

## Constitution Check

| 宪法原则 | 检查结果 |
|---|---|
| I 单机演示防御校准 | PASS — 不新增任何防御机制；无后处理校验层 |
| II 鉴权三层门卫 | PASS — 不动任何端点 |
| III 错误契约恒定 | PASS — 不动 ApiResponse/BizException |
| IV 事务边界纪律 | PASS — 无事务改动 |
| V 最小精准修改 | PASS — 改动限 3 个文件的既有方法内追加；既有规则文本逐字不动；出界清单见 spec 非目标节 |
| VI 技术栈与依赖锁定 | PASS — 零新依赖 |
| VII 基线先行验证 | PASS — S0 先跑基线，S4 用现有入口复验并归档 |

## 设计决策摘要（详见 research.md）

1. **约束段追加段落融入**（D1）— 插入【最终回答排版要求】后，既有文本逐字不动
2. **约束内容中文提炼自 citation-minimal-v3**（D2）— 四条核心约束，"supplied context" 转译为工具调用语义
3. **PROMPT_VERSION 常量 + 一行日志**（D3）— 评测报告不动
4. **工具说明行加在 formatResults 拼接层**（D4）— 单块结构零变化，MCP 路径同步
5. **基线/对比只用现有评测入口**（D5）— 结果 log 归档进 specs/002

## Phases

- **Phase 0**: research.md（本目录）✅
- **Phase 1**: contracts/prompt-contract.md、quickstart.md（本目录）；无数据模型改动，不建 data-model.md
- **实施**: 按 tasks.md（S0→S1→S2→S3→S4，见 speckit-tasks）

## 关键文件（实施目标）

| 文件 | 改动 |
|---|---|
| `agent/JChatMind.java` | 新增 `PROMPT_VERSION` 常量 + 构建时一行日志；`buildThinkPrompt` 静态块追加【引用与拒答】段 |
| `agent/tools/KnowledgeTools.java` | `formatResults` 拼接结果后追加一行引用格式总说明 |
| `mcp/McpKnowledgeTool.java` | 对应拼接处同步追加同款说明行 |

## 复用清单（不重造）

- 评测入口：`RagFastRegressionEvaluatorTest`、answer-quality evaluator（门控）
- 版本号惯例：`ChatClientIntentModelClient.PROMPT_VERSION`、`ZhipuVisionClient.EXTRACTION_PROMPT_VERSION`
- 约束文案来源：`citation-minimal-v3` SYSTEM_PROMPT（逐条映射，见 prompt-contract.md）
- 归档惯例：specs/001 的 *.log 落盘方式
