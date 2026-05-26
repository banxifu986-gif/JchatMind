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
- 当前后端测试基线不是绿色，`mvnw.cmd test` 失败，现状是 Spring Boot `ApplicationContext` 启动失败
- 前端当前没有 `test` 脚本，现有校验入口主要是 `npm run lint` 和 `npm run build`
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

## 文档导航

`docs/` 目录结构与查阅指引：

- **`docs/README.md`** — 文档总索引，先读这个
- **`docs/reference/`** — 稳定参考资料：简历表述版本、Agent 面试八股、RAG 收口规范
- **`docs/plans/active/`** — 规划中的方案：MCP 双向集成、系统路线图、RAG 优化路线图、用户记忆系统
- **`docs/plans/done/`** — 已完成的方案：Agent Harness、Query Rewrite、多路召回、工具调用改进
- **`docs/records/`** — 重大 bug 修复与阶段性收口记录，按 rag/user-memory/startup/sse 分目录
- **`docs/测试清单.md`** — 后端测试覆盖清单

涉及方案设计或历史决策时，优先查阅对应 `docs/plans/` 和 `docs/records/` 目录。

### 前端风格
- 使用 TypeScript + React 函数组件
- import 路径显式，现有代码普遍保留 `.ts`、`.tsx` 扩展名
- 倾向使用具名类型定义，接口和请求响应类型集中在 `api` 与 `types` 附近
- hooks 负责数据获取与刷新，组件负责视图组织，尽量保持职责分离
- 现有代码使用双引号、分号、两空格缩进，修改时保持一致
- 沿用现有 Ant Design 组件写法和已有交互模式，不要擅自改 UI 技术路线
- 不要未经允许引入新的状态管理方案、请求库或组件库
