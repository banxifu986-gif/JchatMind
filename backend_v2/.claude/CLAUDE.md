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

- 测试依赖完整应用上下文，缺少模型相关配置时容易在启动阶段失败
- 当前已知一类失败原因是模型配置缺失导致 `ApplicationContext` 无法启动
