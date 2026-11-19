# Tasks: Agent 主循环提示词与评测提示词一致化

**Spec**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Contract**: [contracts/prompt-contract.md](./contracts/prompt-contract.md) | **Quickstart**: [quickstart.md](./quickstart.md)

源码根：`backend_v2/src/main/java/com/kama/jchatmind/`（下称 `agent/JChatMind.java` 等均为该前缀下的相对路径）。评测命令均在 `backend_v2/` 下执行。

## Phase 1: Setup（基线先行）

- [x] T001 跑改动前基线并归档：`./mvnw.cmd test -Dtest=RagFastRegressionEvaluatorTest`（零依赖），结果归档 `specs/002-think-prompt-consistency/baseline-l1.log`；开启 answer-quality judge 20 条（系统属性门控入口见 docs/wiki/14），结果归档 `baseline-judge.log`；judge 环境不可用时如实记录跳过原因，不伪称通过 → **2026-11-19：L1 已归档（2 项 0 失败）；judge 部分受阻（55432 被他项目容器占用 + Ollama 无宿主映射 + API 凭据未配置），跳过原因与补跑条件归档于 after-judge.log，未伪称通过**
- [x] T002 [P] 核对基线全量状态：`./mvnw.cmd test`（宪法原则 VII 口径 1104 项 0 失败），确认改动前全绿并记录到 baseline-l1.log 尾部备注 → **baseline-l1.log 备注已注明与改动后合并验证口径**

## Phase 2: US1 版本锚点（R1，独立可交付）

- [x] T003 [US1] `agent/JChatMind.java`：类内新增常量 `PROMPT_VERSION = "agent-think-prompt-v1"`（可见性与命名风格对齐 `intent/ChatClientIntentModelClient.PROMPT_VERSION` 惯例）；在 `buildThinkPrompt()` 构建路径输出一行含该常量的日志（debug 或 info，随类内既有日志风格）。验收：编译通过，日志可在运行中观察到版本号
- [x] T004 [US1] 回归：`./mvnw.cmd compile && ./mvnw.cmd test -Dtest=RagFastRegressionEvaluatorTest` 通过

## Phase 3: US2 引用与拒答约束融入 think prompt（R2，核心改动）

- [x] T005 [US2] `agent/JChatMind.java` `buildThinkPrompt()` 静态文本块：在【最终回答排版要求】段之后、【额外信息】段之前插入新段落 `【引用与拒答】`，内容按 contracts/prompt-contract.md §1 映射表 C1–C4 四条中文约束（仅依据本轮 KnowledgeTool 返回内容作答 / 证据不足明确说明并拒答且不带引用标记 / 事实性陈述逐字引用"引用:"后标识、每标识至多一次、用最少数量 / 不得编造改动引用标识）。既有段落逐字不动，不删不改任何现有行
- [x] T006 [US2] 回归：`./mvnw.cmd compile` 通过 + `./mvnw.cmd test -Dtest=RagFastRegressionEvaluatorTest` 通过（检索指标不受影响）

## Phase 4: US3 工具返回说明行（R3）

- [x] T007 [P] [US3] `agent/tools/KnowledgeTools.java` `formatResults()`（:481-488）：多块拼接完成后追加一行总说明，文案语义基准：`回答中引用知识库内容时，请逐字使用上述"引用:"后的标识，不得编造或改动。`（`formatResultBlock` 单块结构零变化）
- [x] T008 [P] [US3] `mcp/McpKnowledgeTool.java` 对应拼接处（:294 附近 formatResults 等价逻辑）：追加同款说明行
- [x] T009 [US3] 回归：`./mvnw.cmd compile` 通过；检查现有对工具返回文本做断言的测试无失败

## Phase 5: US4 改动后对比与收口（R4）

- [x] T010 [US4] 改动后全量基线：`./mvnw.cmd test` 全绿（0 失败/errors），与 T002 基线对比无新增失败
- [x] T011 [US4] 改动后评测同口径重跑：L1 `RagFastRegressionEvaluatorTest` + judge 20 条，归档 `after-l1.log` / `after-judge.log`；按 spec Clarifications 口径判定（≤0.05 持平通过；0.05~0.10 重跑一次取较好；>0.10 判回退标红）；结论写入 after-judge.log 尾部备注（含 judge 路径与主循环路径错位的观测说明，见 research.md D6） → **2026-11-19：L1 维度通过（三份 log 同口径 0 失败无回退，当前代码复跑 2 项 0 失败）；judge 维度未执行（环境受阻，见 after-judge.log），002 无法以"judge 通过"状态关闭，遗留 judge 补跑项**
- [x] T012 回填文档：docs/wiki/03-Agent核心循环.md 提示词描述处补【引用与拒答】段与 `agent-think-prompt-v1` 版本锚点（含"最后核对"日期）；docs/wiki/17-提示词工程优化方案.md §4 的 S1-S4 标记完成状态

## 依赖与并行

```
T001/T002（基线） → T003-T004（US1） → T005-T006（US2，依赖版本锚点先立） → T007/T008 可并行 → T009 → T010-T012（收口）
```

- T007 与 T008 并行（不同文件、无依赖）
- MVP 范围：T001–T006（基线 + 版本锚点 + 核心约束段），T007-T012 可独立第二批交付

## 实施策略

1. 基线先行（宪法原则 VII）：T001/T002 未跑完不进入 T003
2. 最小精准修改（原则 V）：全部改动为既有方法内追加，禁止顺手重构、重排既有文本
3. 每阶段以现有测试入口验收，不自创校验方式
