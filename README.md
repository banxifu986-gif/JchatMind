# JChatMind

这是我的个人学习项目，用来练习基于 Spring AI 的 Java Agent 系统设计与实现。

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

1. 准备 PostgreSQL 和 pgvector 扩展
2. 执行 `sql/jchatmind_assert/jchatmind.sql`
3. 在 `backend_v2` 中补全运行配置
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
