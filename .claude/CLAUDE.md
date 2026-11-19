# JChatMind 项目规则

## 规则层级
- 本文件只补充 JChatMind 项目差异，与全局 `CLAUDE.md` 共同生效
- 与全局规则或通用 `SKILL` 冲突时，以本文件为准
- `backend_v2/.claude/CLAUDE.md` 仅补充后端模块上下文，不重复通用规则

## 信息来源
- 开始前先读 `README.md`，获取项目技术栈、目录结构、启动方式和仓库概况
- 本文件只保留 `README.md` 未覆盖的项目差异，重复信息尽量不写

## 当前仓库基线认知
- 后端测试入口存在：`backend_v2` 可执行 `mvnw.cmd test`
- 2026-11-12 后端 `mvnw.cmd test` 基线通过：1105 项，0 failures/errors，120 项按条件跳过（门控 L2 运行时测试与评测类）；跳过项不代表真实基础设施或模型验收通过。迁移 L2（`-Dmigration.lifecycle.l2=true`）已单独跑通：25 条迁移 clean install + catalog 对账通过
- 依赖完整应用上下文的测试需要真实环境变量（如 `DB_PASSWORD`）：缺配置的机器会在 `ApplicationContext` 启动阶段失败，属环境问题，不是代码基线问题
- Codex Windows 沙箱内可能在 Mockito/Byte Buddy attach 阶段挂起；应先诊断执行环境，不能据此修改业务逻辑或关闭测试。基线分类与证据见 `docs/wiki/14-评测体系与门禁.md` 的测试基线节
- 前端统一校验入口为 `npm run check`（lint、`npm test`、build）；`npm test` 执行 Node 源码契约与 TypeScript 类型契约，`npm run test:e2e` 显式执行需要前端服务和相应 fixture 的 Playwright 测试
- 后续改动前应先复用这些现有入口确认基线，不要自创校验方式

## 代码风格

### 后端风格
- 使用标准 Spring Boot 分层，常见目录为 `controller`、`service`、`model`、`converter`、`agent`
- Controller 保持轻量，主要负责路由、参数接收和 `ApiResponse` 包装
- 倾向使用构造器注入，现有代码大量使用 `@AllArgsConstructor`
- 命名直接，类名和接口名明确体现职责，不做过度抽象
- 保持 Java 代码现有排版风格：大括号换行、字段和方法之间留空行、链式调用按缩进展开
- 除非逻辑确实不明显，不要新增注释；修改现有代码时保持原有注释密度
- 不要未经允许新增兼容性分支、兜底逻辑或双写逻辑

### 关键架构约定（2026-11-12 审查收口确立）
- 鉴权三层：`SecurityConfig` 对 `/api/**` 等路径放行 + controller 方法挂 `@NeedLogin`（切面校验 JWT 与封禁）+ service 层数据归属校验；公开端点仅限登录/注册/验证码、webhook ingress（HMAC 签名）、MCP（API Key 过滤器）、health 与 `SseController`（手工 401 门卫，契约锁定）
- 错误契约：HTTP 恒 200，语义走 `ApiResponse.code`；`BizException` 必须带码（401/403），内部异常细节不进响应体、只进日志
- 事务边界：事务内不做磁盘 IO；多步破坏性删除必须包 `@Transactional`；文件删除挂 `afterCommit`
- 防御等级按单机演示校准：不新增 write-only 审计、零调用的协调层、为多实例准备的锁与队列
- `migration/`（手搓 schema 迁移框架）经 2026-11-12 审查评估决定保留：独立包、不在应用启动路径、自带测试；不换 Flyway，不新增该依赖
- Agent 循环骨架（2026-11-19 specs/003 P2 落地）：`agent/loop/AgentLoop` 持状态机/步数预算（含 finalBudgetTurn 最终消费轮）/next-turn hook/步间取消检查/生命周期事件出口，零 Spring 依赖；`JChatMind` 只持 agent 定义、prompt 组装、SSE 桥（线上契约逐字节不变）与审批编排；用户停止走 `POST /api/chat-sessions/{id}/stop`（三层鉴权、幂等），在飞工具中断 + 步间停步
- Harness hook 管线（2026-11-19 specs/003 P1 落地）：守卫分两级——`HarnessRunner`（单重载）做重复/白名单拒绝 + 审批闸门（loop 层，不进工具调用栈）；`HarnessToolCallbackProxy` 内单调用栈成对 hook（before 可拦截 / after 可改写 / onError / onBlocked，熔断审计为 per-call 实现，审计 order=9000 链尾记录改写后结果）。工具上下文经 `ToolContext` 显式传递（`HarnessToolContexts` 单点组装），缺失显式失败 `[HARNESS_CONTEXT_MISSING]`，不得回退 ThreadLocal/静默降级
- 工具并行与截断（2026-11-19 specs/003 P3/P1 落地）：多 call 轮走 `AgentToolExecutor.executeParallel`（并发上限 = 池容量 4，无序发起、按声明顺序回填、失败隔离、aborted 前置）；批级超时/用户取消统一 `TOOL_OUTCOME_UNKNOWN` 收口；finishReason=length 的响应全部 toolCall 判废 `[TOOL_CALLS_TRUNCATED]`

## 文档导航

`docs/` 现行文档只有 `docs/wiki/` 一个来源，历史方案与参考资料全部归档：

- **`docs/README.md`** — 文档总索引，先读这个
- **`docs/wiki/00-INDEX.md`** — 模块地图与按任务选路；01 为全局总览（能力边界、接口总表），02–16 为模块深潜页
- **`docs/archive/2026-08-15/`** — 历史方案与记录：plans、records、reference、root
- **`docs/archive/2026-11-10/`** — 近期归档：plans、reference、spec

原 `docs/项目架构与关键链路.md` 已并入 wiki 并经源码核验后删除（能力边界与接口总表见 wiki/01，SQL 调优基线见 wiki/13 §3.1），其 git 历史仍可查。历史结论只从归档区查阅；若仍然有效，先回填对应 wiki 页，再引用它。目录变更以 `docs/README.md` 为准。

### 前端风格
- 使用 TypeScript + React 函数组件
- import 路径显式，现有代码普遍保留 `.ts`、`.tsx` 扩展名
- 倾向使用具名类型定义，接口和请求响应类型集中在 `api` 与 `types` 附近
- hooks 负责数据获取与刷新，组件负责视图组织，尽量保持职责分离
- 现有代码使用双引号、分号、两空格缩进，修改时保持一致
- 沿用现有 Ant Design 组件写法和已有交互模式，不要擅自改 UI 技术路线
- 不要未经允许引入新的状态管理方案、请求库或组件库
