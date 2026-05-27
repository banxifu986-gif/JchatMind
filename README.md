# JChatMind

基于[代码随想录]的 Java Agent 系统进行学习和二次开发。

在原项目（Spring AI Agent 对话系统）基础上，补充和改进了以下模块：

### 新增
- **鉴权体系** — Spring Security + JWT + AOP 权限注解（@NeedLogin/@NeedAdmin），Redis 三层限流 + RabbitMQ 异步邮件验证码
- **用户记忆系统** — LLM 驱动自动记忆持久化，候选记忆状态管理，cosine 相似度召回
- **Agent Harness** — 人工审批、熔断器、审计日志等安全控制
- **MCP 服务端** — 将知识库检索、邮件、数据库工具暴露为 MCP 工具，支持 Notion 等外部客户端调用

### 改进
- **RAG 链路** — 多路召回、查询改写评测基线、Ollama Embed 适配、pgvector 余弦距离
- **工具稳定性** — 压缩阶段防截断工具调用对、工具指标接入、SSE 超时处理
- **基础设施** — docker-compose 一键启停（PostgreSQL + Redis + RabbitMQ）

## 项目结构

```text
javamind_agents
├── backend_v2      # Spring Boot 后端
├── ui              # React + Vite 前端
├── sql             # 数据库初始化脚本与示例数据
├── docs            # 仓库级补充文档
└── examples        # 示例页面
```

## 技术栈

- 后端：Java 17、Spring Boot 3.5.8、Spring AI 1.1.0、MyBatis、PostgreSQL、pgvector
- 前端：React 19、TypeScript、Vite、Ant Design 6、Tailwind CSS 4

## 启动

1. 启动基础设施（PostgreSQL + pgvector + Redis + RabbitMQ）：

```bash
docker compose up -d
```

2. 执行建表 SQL：

```bash
docker exec -i jchatmind-postgres psql -U postgres -d jchatmind < sql/auth/2026-05-26-create-user-table.sql
docker exec -i jchatmind-postgres psql -U postgres -d jchatmind < sql/auth/2026-05-26-create-email-failure-table.sql
```

3. 在 `backend_v2` 中补全运行配置（`.env` 文件）
4. 启动后端
5. 启动前端

后端：

```bash
cd backend_v2
./mvnw spring-boot:run
```

Windows:

```bash
cd backend_v2
mvnw.cmd spring-boot:run
```

前端：

```bash
cd ui
npm install
npm run dev
```

## 许可证

本项目基于 [JChatMind](https://github.com/youngyangyang04/JChatMind)（Copyright (c) 2025 程序员Carl）进行二次开发，沿用原项目 [MIT License](LICENSE)。
