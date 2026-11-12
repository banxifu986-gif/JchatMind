# backend_v2 模块说明

- 先遵循仓库根目录 `AGENTS.md`
- 本文件只补充 `backend_v2` 的后端模块上下文，不重复通用沟通、边界和安全规则

## 模块定位

- `backend_v2` 是 Spring Boot 后端，负责 Agent 编排、工具调用、知识库检索、消息持久化和 SSE 推送
- 前端相关约束以仓库根目录规则为准，本文件不再重复 UI 技术栈

## 后端技术栈

- Java 17
- Spring Boot 3.5.8
- Spring AI 1.1.0
- MyBatis
- PostgreSQL + pgvector
- Lombok
- Spring Mail
- SSE

## 后端核心架构

- Agent 运行主线是 Think-Execute 循环，围绕状态流转和工具执行展开
- 模型接入使用 `ChatClientRegistry` 注册表模式，支持多模型切换
- 工具系统区分固定工具和可选工具，并手动接管 Spring AI 工具执行流程
- RAG 链路包含 Markdown 解析、分块、Embedding 入库和 pgvector 检索
- SSE 负责把执行过程和结果实时推送给前端

## 代码关注点

- 重点目录通常包括 `controller`、`service`、`model`、`converter`、`agent`、`config`
- 涉及模型、工具、知识库、SSE 的改动时，优先先看已有链路，不要只在单点打补丁
- 需要改 Spring Bean 装配、模型注册或工具注册时，先确认影响范围再动手

## 当前已知事项

- 2026-11-12 测试基线全绿：1104 项 0 失败/errors，120 项门控跳过；全上下文测试缺环境变量（`DB_PASSWORD` 等）时 `ApplicationContext` 启动失败，属环境问题
- 鉴权由 controller 层 `@NeedLogin` 切面强制（JWT + 封禁检查）；`SseController` 是唯一例外——手工 401 门卫（前端与测试契约锁定），封禁用户仍可建立只读 SSE 连接
- 文档上传的事务口径：`uploadDocument` 不使用方法级事务（磁盘 IO 在事务外，`TransactionTemplate` 只包元数据更新与任务创建两步）；`deleteDocument` 整体包事务、文件删除挂 `afterCommit`
