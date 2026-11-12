# JChatMind 宪法 (Constitution)

**版本**: 1.0.0
**批准日期**: 2026-11-12
**最后修订**: 2026-11-12

## Preamble

本宪法确立 JChatMind（backend_v2 Spring Boot 后端 + ui/ React + TypeScript 前端的
Java Agent 对话系统）所有开发工作的非协商治理基线，约束全部 speckit 特性流程
（/speckit-specify、/speckit-plan、/speckit-tasks、/speckit-implement、/speckit-analyze）
以及一切人工或代理完成的代码改动。

冲突解决顺序：本宪法 > 项目 `.claude/CLAUDE.md` > 全局 `~/.claude/CLAUDE.md` >
代理默认习惯。CLAUDE.md 提供操作细则，本宪法提供其上不与之冲突的治理约束。

条款以 MUST / MUST NOT 承载强制性；SHOULD 条款偏离时必须说明理由。

## 核心原则

### 原则 I：单机演示防御校准

本项目按单机演示场景校准防御等级。MUST NOT 引入仅为多实例/分布式部署服务的机制，
包括但不限于：数据库 advisory lock、outbox、独立熔断协调层、write-only 审计、
零调用的协调层、为多实例准备的锁与队列。

防御只加在会被真实触发的路径上。2026-11 全库审查结论：按多实例 SaaS 校准的防御
掩盖了真正会咬人的薄弱点（鉴权缺口、事务内 IO）。新增防御机制前 MUST 指出触发它的
真实场景；说不出场景就不加。

### 原则 II：鉴权三层门卫

受保护端点 MUST 三层齐备，缺一层即缺陷：

1. `SecurityConfig` 对 `/api/**` 等路径放行，不承担鉴权语义；
2. controller 方法挂 `@NeedLogin`，切面校验 JWT 与用户封禁状态；
3. service 层数据归属校验（owner 校验）。

公开端点仅限锁定清单：登录/注册/验证码、webhook ingress（HMAC 签名）、
MCP（API Key 过滤器）、health、`SseController`（手工 401 门卫，契约锁定）。
新增公开端点 MUST 同步更新清单并说明豁免理由。

### 原则 III：错误契约恒定

HTTP 状态码恒为 200，业务语义 MUST 通过 `ApiResponse.code` 传达；前端按 `code`
分支处理，不解析 HTTP 状态码。`BizException` MUST 携带业务码（含 401/403 语义）。
内部异常细节 MUST NOT 进入响应体，只进日志。

### 原则 IV：事务边界纪律

事务内 MUST NOT 做磁盘 IO。多步破坏性删除 MUST 包在 `@Transactional` 中；
文件等外部资源删除 MUST 挂 `afterCommit`。跨事务边界的多步操作用
`TransactionTemplate` 显式拆分，不做隐式传播。

### 原则 V：最小精准修改

每一行改动 MUST 与当前需求直接相关。MUST NOT 顺手重构、优化无关代码或调整无关
格式；发现项目原有废弃冗余代码只备注提醒，不擅自删除；自身修改产生的失效导入、
变量、函数必须清理。未获允许 MUST NOT 新增兼容性分支、兜底逻辑或双写逻辑。

MUST 遵循现有代码风格：后端标准 Spring 分层（controller/service/model/converter/agent）、
构造器注入（现有代码多用 `@AllArgsConstructor`）、Controller 保持轻量只做路由与
`ApiResponse` 包装；前端 TypeScript + React 函数组件、双引号、分号、两空格缩进、
沿用 Ant Design 既有交互模式。

### 原则 VI：技术栈与依赖锁定

新增依赖、组件库、状态管理方案或请求库 MUST 先经明确确认。前端不得擅自更换
Ant Design 技术路线。

`migration/`（手搓 schema 迁移框架）为已决策保留项（2026-11-12）：不换 Flyway、
不新增 Flyway 依赖、不在应用启动路径运行；`sql/migrations/` 发布契约按其 README
的 fail-closed 规则执行。

### 原则 VII：基线先行验证

改动前后 MUST 使用现有校验入口，MUST NOT 自创校验方式：

- 后端：`backend_v2` 下 `mvnw.cmd test`（当前基线 1104 项 0 失败/errors，
  120 项门控跳过；跳过项不代表真实基础设施或模型验收通过）；
- 前端：`npm run check`（lint + test + build）；`npm run test:e2e` 需前端服务
  与相应 fixture，显式运行。

依赖完整应用上下文的测试需要真实环境变量（如 `DB_PASSWORD`）；缺配置机器在
ApplicationContext 启动阶段失败属环境问题，不是代码基线问题。校验失败时 MUST
如实区分代码缺陷与环境问题，不得伪称通过。

## 文档与知识治理

- `docs/README.md` 为文档总索引；现行文档仅保留 `docs/README.md` 与
  `docs/项目架构与关键链路.md` 两份，历史方案与参考资料一律进 `docs/archive/<日期>/`。
- 历史结论只从归档区查阅；若仍有效，MUST 先回填现行架构文档再引用。
- 关键架构约定变更 MUST 同步 `.claude/CLAUDE.md` 的「关键架构约定」节与架构文档。
- 特性级契约（schema、接口约束）沉淀在 `specs/<feature>/contracts/`，不写入本宪法。

## 宪法边界

以下内容 MUST NOT 进入本宪法：

- 临时性规则（如限时迭代测试规则），其在 `.claude/CLAUDE.md` 中标注有效期，到期删除；
- 特性级实现契约与任务清单（归 `specs/<feature>/`）；
- 可从代码、git 历史或 README 直接推导的信息。

宪法只记录跨特性、长期有效的非协商约束。

## 治理 (Governance)

- **修订程序**：修改本宪法 MUST 更新版本号并在文件顶部附 Sync Impact Report
  （临时审阅素材，提交前移除）。新增原则或章节 → MINOR；删除或重定义既有原则
  （不向后兼容）→ MAJOR；措辞澄清、笔误修正、非语义润色 → PATCH。
- **合规检查**：`/speckit-plan` 与 `/speckit-implement` MUST 先读本宪法；
  `/speckit-analyze` MUST 将 spec/plan/tasks 与本宪法的一致性纳入检查范围；
  所有代码改动在 review 时逐条对照核心原则 I–VII。
- **运行时开发指引**：日常开发以项目与全局 `.claude/CLAUDE.md` 为细则来源；
  本宪法约束 speckit 流程及其上的治理事项。

**版本策略**: MAJOR.MINOR.PATCH，语义如上。
