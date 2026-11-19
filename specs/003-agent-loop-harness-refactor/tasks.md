# Tasks: Agent Loop 与 Harness 管线重构

**Spec**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Contract**: [contracts/hook-pipeline-contract.md](./contracts/hook-pipeline-contract.md)

源码根：`backend_v2/src/main/java/com/kama/jchatmind/`（下称相对路径）。测试命令在 `backend_v2/` 下执行。

**TDD 纪律（全程适用）**：标 `[TDD]` 的任务 = 先写测试且必须先红（因功能缺失失败），测试断言内容必须可追溯到 spec 场景验收标准，MUST NOT 根据实现答案倒写；实现任务以"对应测试转绿"为完成定义。

**基线纪律**：每阶段起点跑 `./mvnw.cmd test` 全量并归档 `specs/003-agent-loop-harness-refactor/pN-baseline.log`，阶段终点复验归档 `pN-after.log`（宪法原则 VII）。

## Phase 0: 基线（先行）

- [x] T000 跑全量基线归档 `p1-baseline.log`：1106 项 0 失败口径确认；记录 harness 相关存量测试清单（`HarnessRunnerTest`、`CircuitBreakerInterceptorTest`、`HumanApprovalInterceptorWebhookOutboxTest` 等），作为 P1 改写预期面

## Phase 1: hook 管线收口 + 截断安全（R1.x，场景 1/2/3）

- [x] T101 [TDD] 场景 1 守卫配对测试（先红）：stub 工具 + 构造 Proxy 调用——① before 返回 BLOCK 时工具实现零执行且 tool result 含拒绝原因；② after 改写后模型收到改写结果；③ 每条 tool call 恰好一条审计记录（含被拦截）。断言逐条对应 spec 场景 1 验收
- [x] T102 [TDD] 场景 2 截断判废测试（先红）：stub 响应 finishReason 注入 LENGTH，含 2 个 toolCall——全部判废为错误 result（文案提示重新发起）、工具实现零执行、审计逐 call 记录。对应 spec 场景 2
- [x] T103 [TDD] 场景 3 上下文显式传递测试（先红）：ToolContext 注入上下文后 Proxy 可读齐字段；ToolContext 缺失时显式失败（可观测错误），不存在 FALLBACK 静默路径。对应 spec 场景 3
- [x] T104 `HarnessInterceptor`/Chain 接口语义升级 + `HarnessToolCallbackProxy` 成对编排（使 T101-T103 转绿）
- [x] T105 审计/熔断/白名单守卫迁入 Proxy 单调用栈路径；`JChatMind.execute()` 与 `WorkflowPlanExecutor` 守卫调用点收缩（审批路径不动，R1.3）
- [x] T106 `ToolContext` 传递接线：`JChatMind` run 级注入 toolContext；删除 `HarnessExecutionContextHolder`；Factory/Workflow/HarnessedSkill 三处 Proxy 构造方适配
- [x] T107 `HarnessRunner` 重载收敛为 1（调用方同步适配）
- [x] T108 截断判废实现接入 `JChatMind` 合成响应路径（使 T102 转绿）
- [x] T109 存量 harness 单测随语义升级改写（复用其结构，改写理由记录在测试注释）；`./mvnw.cmd test` 全量回归归档 `p1-after.log`
- [x] T110 [P1 review 处置（2026-11-19）] R1 签名/ToolContext 组装收敛为 `HarnessToolContexts` 单点工厂（main 3 处 + 测试 3 处）；R2 审计 order 调至链尾 9000 并在契约 §1 声明"审计记录改写后最终结果"；R3 截断判废分支补 completedToolSteps 归类；R4 删除 HarnessRunnerTest 空壳用例；R6 Proxy 队列取值改 instanceof 模式；R5 已登记 P3 前置清单（见 Phase 3 节）。复验归档 `p1-after-review.log`（1114/0/120；AsyncConfigTest 首轮偶发经 3 次单测复跑确认与环境竞争相关，与本次改动无代码交集）

## Phase 2: 循环抽取 + 取消（R2.x，场景 4/7）

**P2 顺手项（P1 复核 FYI 登记，2026-11-19）**：
- [x] FYI-2（P2 开头已补）：`HarnessToolCallbackProxyPairedHookTest.afterHookShouldBeAbleToRewriteToolResult` 补断言 `records.get(0).getToolResult()` 含 `"|REWRITTEN"`，锁定 order=9000 的"审计记录改写后最终结果"语义，防止 order 被无意改回
- FYI-1（知悉即可，不处理）：order=9000 使审计 before 打点在熔断检查之后，duration 微小低估，无实质影响

- [x] T200 路由定稿：停止端点命名对齐 wiki/01 登记惯例并回填 spec R2.3；wiki/01 接口总表补登（三层鉴权标注）
- [x] T201 [TDD] 场景 7 AgentLoop 骨架测试（先红，零 Spring 依赖）：stub 轮次决策/工具执行器——正常结束、20 步预算耗尽（BUDGET_EXHAUSTED）、中途取消停步、next-turn hook（内存压缩）被调用四条路径
- [x] T202 [TDD] 场景 4 取消测试（先红）：stub LLM 每轮要求继续调工具 + 在飞工具注册中断探测——stop 后工具实现收到中断、步数停止增长、SSE 收到明确结束事件、重复 stop 幂等；非本人会话 stop 返回 403 语义（`ApiResponse.code`）
- [x] T203 新建 `agent/loop/AgentLoop`（状态机/预算/类型化事件出口/取消检查/工具编排委托；使 T201 转绿）
- [x] T204 `JChatMind` 瘦身：prompt 组装/SSE 事件桥（内部事件→`SseMessage` 线上契约逐字节不变）/审批编排迁入桥；内存压缩挂 next-turn hook（使 T201 部分转绿）
- [x] T205 `AgentToolExecutor` 取消触发源扩展：超时/用户取消先到先断（使 T202 转绿）
- [x] T206 停止端点 + service（`@NeedLogin` + owner 校验 + 幂等）；`AgentExecutionTaskQueue`/SSE 链路接线
- [x] T207 [P] 前端停止按钮（`ui/src`，沿用现有交互模式）；`npm run check` 通过
- [x] T208 阶段回归：`./mvnw.cmd test` 全量 + `npm run check`，归档 `p2-after.log`；wiki/01 登记复核
- [x] T209 [P2 review 处置（2026-11-19）] R1（Minor）修复：AGENT_FAILED 日志丢失异常堆栈——AgentLoopEvent 增加 error 字段，AgentLoop 失败分支传异常，JChatMind.onLoopEvent log.error 恢复与原 run() catch 一致的含堆栈形态；R2（Minor 不改）：停止按钮在 AI_ERROR 后仍显示——useChatStream 既有 displayAgentStatus 语义，点击幂等无害，受 R2.5"沿用现有交互模式"约束；R3（Nit 不改）：AgentToolExecutor.cancelState 参数 null 容错仅为测试便利。复验归档 `p2-after-review.log`（1124/0/0/120，AsyncConfigTest 全量与单独复跑均绿，偶发未复现）

## Phase 3: 并行工具（R3.x，场景 5/6）

**P3 前置清单（review R1/R5 登记，2026-11-19）**：
- InMemoryAuditStore.getRecords 无锁读（records.get）与 synchronized 写并发不安全 → 改为快照读或全锁
- signatureToQueues（ArrayDeque.pollFirst 非原子）→ 借 R1 已收敛的 HarnessToolContexts 单点改造为并发结构（ConcurrentLinkedQueue 等）
- 两个 InMemory store 最小同步后补并发压测（T302 托底）

- [x] T301 [TDD] 场景 5 并行编排测试（先红）：两个 stub 工具各 sleep 300ms——耗时 < 500ms、结果顺序 == toolCall 声明顺序、并行中 stop 全部中断且结果列表完整。对应 spec 场景 5
- [x] T302 [TDD] 场景 6 并发压测测试（先红）：多线程驱动 8 工具 × 50 轮——审计记录数 == 实际执行数、熔断计数正确。对应 spec 场景 6
- [x] T303 per-call 并行编排实现（无序发起/顺序回填/失败隔离/aborted 前置检查；单 call 退化顺序等价；使 T301 转绿）
- [x] T304 `InMemoryAuditStore`/`InMemoryCircuitBreakerRegistry` 最小同步（零新依赖；使 T302 转绿）
- [x] T305 阶段回归：`./mvnw.cmd test` 全量归档 `p3-after.log`
- [x] T310 [P3 review 处置（2026-11-19）] R1（Minor 修复）`executeParallel` 的 `cancelInFlight` 补置批级 `cancellation.cancelled=true`——与串行路径 flag+interrupt 双保险对齐，`checkActive`（契约 §6 aborted 前置）在吞中断的工具上可靠成立；R2（Nit 修复）`AgentToolExecutorParallelTest` 重复 static import 清理。R3（登记不改）`WorkflowPlanExecutor` 超时步骤状态记录存在毫秒级竞态（TIMED_OUT 与任务异常完成 FAILED 竞争，全量偶发 1 例、单独复跑 3 次全绿；P1 期既有，与 P3 零代码交集，状态标签精度问题不影响"后续步骤停止"语义）——留待独立事项 TDD 处置。R4（汇报勘误）压测失败计数实际断言 200（50×8/2），交付汇报"250"为笔误。终验归档 `p3-after-review.log`（1128/0/0/120；AsyncConfigTest 饱和偶发经 3 次单独复跑全绿判定环境问题，与 P2 审查同口径）

## 收口

- [x] T400 `.claude/CLAUDE.md`「临时：线上限时迭代测试规则」节删除（Session 2026-11-19 确认已过时）
- [x] T401 文档同步：`.claude/CLAUDE.md` 关键架构约定节按落地结果更新（hook 管线/循环结构/取消语义）；docs/wiki 相关页回填；宪法一致性自查（原则 I–VII 逐条）
- [x] T402 全量终验：`mvnw.cmd test`（1128/0/0/120，final-test.log）+ `npm run check`（16/0）双绿归档
- [x] T402 成功标准勾验（2026-11-19）：①成对 hook/after 改写/逐 call 审计（HarnessToolCallbackProxyPairedHookTest）✓ ②截断判废零误执行（TruncatedToolCallDiscardTest）✓ ③ThreadLocal 与静默降级消除（HarnessToolCallbackProxyToolContextTest）✓ ④用户停止：中断/停步/SSE 收尾/幂等/403（JChatMindStopTest + ChatSessionStopExecutionTest）✓ ⑤并行有序+守卫一致（AgentToolExecutorParallelTest 时钟断言 + 压测）✓ ⑥AgentLoop 零 Spring 单测（AgentLoopTest）✓ ⑦全量基线与线上契约零回退（三阶段独立交付，终验双绿）✓

## 依赖与并行

```
T000 → [P1] T101-T103(TDD,可并行) → T104-T108 → T109
     → [P2] T200 → T201/T202(TDD,可并行) → T203-T207 → T208
     → [P3] T301/T302(TDD,可并行) → T303/T304 → T305 → T400-T402
```

- 三阶段串行（每阶段独立交付、独立全量回归）
- 同阶段内 `[TDD]` 测试任务可并行编写；T203 与 T205/T206 可在各自测试红后并行实现
- 任一阶段失败回滚边界 = 该阶段起点基线（未提交工作区按阶段切分 commit 由用户决定时机）
