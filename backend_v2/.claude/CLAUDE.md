# JChatMind 项目规则

## 项目简介

JChatMind 是一个 Java AI Agent 系统，基于 Spring AI 框架构建，实现了 Think-Execute 循环、工具调用、RAG 知识库检索和多模型切换等核心能力，并通过 SSE 将执行过程实时推送给前端。

## 项目结构

```
jchatmind/   # 后端 Spring Boot 项目
ui/          # 前端 React 项目
```

## 技术栈

### 后端（jchatmind/）

- **Java 17（JDK 17）** + **Spring Boot 3.5.8**
- **Spring AI 1.1.0** — AI 模型集成、工具调用、RAG
- **MyBatis** — 数据库 ORM
- **PostgreSQL 14 + pgvector** — 结构化数据 + 向量存储（通过 Docker 部署）
- **Lombok** — 减少样板代码
- **Spring Mail** — 邮件服务
- **SSE（Server-Sent Events）** — 实时推送执行状态
- 支持模型：**DeepSeek**、**智谱 AI（ZhipuAI）**、**Ollama**（本地模型）

## 基础设施

- **Docker** — 部署 pgvector，快速启动 PostgreSQL 14

### 前端（ui/）

- **React 19** + **TypeScript**
- **Node.js 24.14.0**
- **Vite（rolldown-vite）** — 构建工具
- **Ant Design 6 + @ant-design/x** — UI 组件库
- **Tailwind CSS 4** — 样式
- **React Router 7** — 路由

## 核心架构

- **Agent Loop**：Think-Execute 循环 + 状态机（THINKING / EXECUTING / DONE / ERROR）
- **工具系统**：固定工具 + 可选工具，自动注册，手动接管 Spring AI 工具执行流程
- **RAG 链路**：Markdown 解析分块 → Embedding 入库 → pgvector 相似度检索
- **多模型注册表**：`ChatClientRegistry` 模式，支持模型动态切换
- **SSE 推送**：执行状态实时可视化
