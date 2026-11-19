# Implementation Plan: Agent Loop 与 Harness 管线重构

**Branch**: main（spec 目录与分支解耦，实施时按需开分支）
**Spec**: [spec.md](./spec.md)
**Date**: 2026-11-19

## Technical Context

| 维度 | 取值 |
|---|---|
| 运行环境 | Windows 11 + Git Bash；Java 21 / Spring Boot / Spring AI 1.1.0 |
| 设计参照 | pi-main `packages/agent/src/agent-loop.ts`（只读）：`runLoop` 骨架、`beforeToolCall/afterToolCall` 成对 hook、`failToolCallsFromTruncatedMessage`、`executeToolCallsParallel` |
| 改动模块 | `agent/harness/**`（Proxy/Chain/Interceptor/Runner）、`agent/JChatMind.java`、`agent/JChatMindFactory.java`、`agent/AgentToolExecutor.java`、`agent/loop/`（新）、`controller/`（停止端点）、`service/SseService`（桥接）、`workflow/WorkflowPlanExecutor.java`（适配）、`skill/HarnessedSkillKnowledgeToolExecutor.java`（适配）、`ui/`（停止按钮） |
| 基线口径 | `mvnw.cmd test` 全量（1106 项 0 失败，宪法原则 VII）；阶段 2 另跑 `npm run check` |
| 测试策略 | TDD：每场景测试先行且可追溯到 spec 验收标准，红→绿；禁止按实现倒写测试 |

## 技术验证结论（2026-11-19，全部通过，证据已回填 spec 假设节）

1. **finishReason 通道**：`ChatGenerationMetadata.getFinishReason()` 存在；deepseek/zhipuai 均有 `LENGTH`；`streamChatResponse` 已保留 chunk metadata → 截断判定零新增采集
2. **ToolContext 承载**：`ToolCallingChatOptions.setToolContext` 存在，manager 从 prompt options 传递至 `ToolCallback.call(toolInput, toolContext)`；`JChatMind.chatOptions` 已是 `DefaultToolCallingChatOptions` → 每次 run 注入上下文即可
3. **工具中断安全**：活跃工具 5 项（databaseQuery/EmailTools/KnowledgeTools/TerminateTool/McpKnowledgeTool），无文件锁/原生长操作；JDBC 中断后服务端查询可能继续至完成，由现有超时语义托底（边界记录在案）

## Constitution Check

| 宪法原则 | 检查结果 |
|---|---|
| I 单机演示防御校准 | PASS — InMemory store 同步的唯一触发场景为 spec 场景 6（单会话内一轮并行）；Steering/多实例机制明确不做 |
| II 鉴权三层门卫 | PASS — 停止端点三层齐备；公开端点锁定清单不变 |
| III 错误契约恒定 | PASS — stop 业务错误走 `ApiResponse.code`；SSE 契约逐字节不变 |
| IV 事务边界纪律 | PASS — 无事务改动 |
| V 最小精准修改 | PASS — 改动限 spec 列出类；审批流语义保持；无关问题只备注 |
| VI 技术栈与依赖锁定 | PASS — 零新依赖（Spring AI 2.0 / AgentScope 已否决，见 spec 备选节） |
| VII 基线先行验证 | PASS — 每阶段前后 `mvnw.cmd test` 全量；P2 加 `npm run check` |

## 设计决策摘要

1. **D1 hook 管线收口（spec 方案 C）** — 审计/熔断/白名单迁入 Proxy 单调用栈成对路径；审批留 loop 层；ThreadLocal→ToolContext；重载收敛为 1
2. **D2 截断安全** — `lastChatResponse` 的 finishReason=="length"（大小写以运行时实测为准，测试由 stub 控制）时全部 toolCall 判废
3. **D3 AgentLoop 抽取（spec 方案 A）** — 骨架持状态机/预算/事件出口/取消检查；`JChatMind` 留定义/prompt/SSE 桥/审批编排；内存压缩挂 next-turn hook
4. **D4 取消** — `POST /api/chat-sessions/{sessionId}/stop`（路由命名以 wiki/01 登记惯例为准，P2 定稿）+ 步间检查 + `AgentToolExecutor` 扩展用户取消触发源（超时/取消先到先断）
5. **D5 并行** — per-call 并行编排（对齐 pi：无序发起、按 toolCall 顺序回填、失败不丢整轮）；上限固定 4；两个 InMemory store 最小同步

## Phases

- **Phase 0**: 技术验证 ✅（结论回填 spec）
- **Phase 1**: contracts/hook-pipeline-contract.md（本目录）
- **实施**: 按 tasks.md（P1→P2→P3→收口），每阶段 TDD 先行、独立全量回归

## 关键文件（实施目标）

| 阶段 | 文件 | 改动 |
|---|---|---|
| P1 | `agent/harness/proxy/HarnessToolCallbackProxy.java` | 成对 hook 编排（before 拦截/after 改写/onError）；ToolContext 显式取上下文 |
| P1 | `agent/harness/interceptor/HarnessInterceptor.java` + Chain | 接口语义升级（after 返回改写结果） |
| P1 | `agent/harness/proxy/HarnessExecutionContextHolder.java` | 删除（ThreadLocal 移除），上下文缺失改显式失败 |
| P1 | `agent/harness/HarnessRunner.java` | 重载收敛为 1；审批路径不变 |
| P1 | `agent/JChatMind.java`、`workflow/WorkflowPlanExecutor.java` | 守卫调用点收缩 + 重载适配；截断判废接入 |
| P2 | `agent/loop/AgentLoop.java`（新） | 循环骨架 + 状态机 + 事件出口 + 取消检查 |
| P2 | `agent/JChatMind.java`、`JChatMindFactory.java` | 瘦身：prompt/定义/SSE 桥/审批编排 |
| P2 | `controller/ChatSessionController.java`（或对齐惯例）+ service | 停止端点，三层鉴权 |
| P2 | `ui/src/`（useChatStream/相关视图） | 停止按钮 |
| P3 | `agent/AgentToolExecutor.java` 或新编排类 | per-call 并行；顺序回填 |
| P3 | `agent/harness/audit/InMemoryAuditStore.java`、`circuit/InMemoryCircuitBreakerRegistry.java` | 最小同步 |

## 复用清单（不重造）

- `AgentToolExecutor` 线程池与 `Cancellation` 机制（扩展触发源而非重写）
- `SseMessage` 类型体系与 `SseServiceImpl` 推送通道（桥接映射，不动契约）
- `HarnessRunner.awaitApprovals` 审批流（行为保持）
- 现有 `HarnessRunnerTest` 等 harness 单测骨架（随语义升级改写，结构复用）
