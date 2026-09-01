# JChatMind

基于[代码随想录]的 Java Agent 系统进行学习和二次开发。

在原项目（Spring AI Agent 对话系统）基础上，补充和改进了以下模块：

### 新增
- **鉴权体系** — Spring Security + JWT + AOP 权限注解（@NeedLogin/@NeedAdmin），Redis 三层限流 + RabbitMQ 异步邮件验证码
- **用户记忆系统** — LLM 驱动自动记忆持久化，候选记忆状态管理，cosine 相似度召回
- **Agent Harness** — 人工审批、熔断器、审计日志等安全控制
- **MCP 服务端** — 将知识库检索、邮件、数据库工具暴露为 MCP 工具，支持 Notion 等外部客户端调用

### 改进
- **RAG 链路** — 多路召回、查询改写评测基线、Ollama Embed 适配、pgvector 余弦距离、可控的 TEI cross-encoder 重排序
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

1. 启动基础设施（PostgreSQL + pgvector + Redis + RabbitMQ + Ollama + TEI reranker）：

```bash
docker compose up -d
```

2. 按 [`sql/migrations/manifest.json`](sql/migrations/manifest.json) 的唯一顺序执行迁移，并在执行前核对每份 SQL 的 SHA-256。Manifest 明确要求先提供不包含在本仓库内的、经批准的原始基线 schema；缺少基线或发现未知/部分状态时必须 fail-closed，不能把增量 SQL 当作 clean install，也不能用 `IF NOT EXISTS` 掩盖漂移。

后端的 `SchemaMigrationExecutor` + `JdbcMigrationStore` 提供显式的 ledger/事务执行能力，但默认不随应用启动自动运行；生产执行仍须由发布流程传入批准基线文件、SHA-256 和人工前置批准项。

发布入口、catalog 对账契约和脱敏报告规则见 [`sql/migrations/README.md`](sql/migrations/README.md)。生产迁移必须通过其中的 `MigrationReleaseApplication` 显式执行，不能把迁移绑定到普通应用启动。

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

### 可选 BGE 重排序

`docker compose up -d` 会额外启动本地 CPU TEI 服务 `jchatmind-reranker`，使用
`BAAI/bge-reranker-v2-m3`，宿主入口为 `http://127.0.0.1:8081/rerank`。模型缓存保存在
Docker volume `jchatmind_tei_data`，首次启动需要下载模型。

后端默认不使用该服务，`rag.rerank.enabled` 默认值为 `false`，因此启动基础设施不会改变已有检索排序。
完成冻结基准的 A/B 对比后，才通过运行环境设置以下非敏感配置开启：

```text
RAG_RERANK_ENABLED=true
RAG_RERANK_BASE_URL=http://127.0.0.1:8081
RAG_RERANK_TIMEOUT_MS=3000
```

启用后，RRF 与上下文过滤完成的前 50 个候选会发送到 TEI；服务超时、不可用或返回非法响应时，后端保持现有本地 rerank 作为回退。

## 许可证

本项目基于 [JChatMind](https://github.com/youngyangyang04/JChatMind)（Copyright (c) 2025 程序员Carl）进行二次开发，沿用原项目 [MIT License](LICENSE)。
