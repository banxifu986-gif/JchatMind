# 启动循环依赖故障记录 - 2026-05-25

## 1. 现象

- 时间：`2026-05-25`
- 场景：本地启动 `backend_v2`
- 结果：Spring Boot `ApplicationContext` 启动失败
- 关键报错：bean 依赖形成循环引用，默认禁止自动循环依赖

循环链路：

```text
JChatMindFactory
-> chatClientRegistry
-> deepseek-chat
-> deepSeekChatModel
-> toolCallingManager
-> toolCallbackResolver
-> mcpToolCallbackProvider
-> mcpKnowledgeTool
-> ragServiceImpl
-> queryRewriteServiceImpl
-> chatClientRegistry
```

## 2. 根因

这次不是“环境变量缺失”导致的启动失败，而是新增的 `MCP Server` 接线与 `Query Rewrite LLM` 接线叠加后，形成了真实的启动期闭环。

根因拆解：

1. `McpKnowledgeTool` 直接依赖 `RagService`
2. `RagServiceImpl` 依赖 `QueryRewriteServiceImpl`
3. `QueryRewriteServiceImpl` 虽然通过 `ObjectProvider<ChatClientRegistry>` 注入，但构造阶段就立即取值，仍然是启动期强依赖
4. `ChatClientRegistry` 的创建依赖 `deepseek-chat`
5. `deepseek-chat` 在 Spring AI Tool Calling 自动配置过程中，又会回头解析 `mcpToolCallbackProvider`

所以 `ObjectProvider` 在这里并没有真正打断依赖，只是把强依赖包装了一层。

## 3. 修复决策

本次按 `TDD` 主线处理，修复策略分两层：

1. 最小拆环
   - `QueryRewriteServiceImpl` 不再在构造阶段解析 `ChatClientRegistry`
   - 只有在请求阶段、且确实命中 LLM rewrite 条件时，才惰性解析目标 `ChatClient`

2. 边界收紧
   - `MCP Server` 侧 `ToolCallbackProvider` 只服务 `/mcp/*`
   - `JChatMindFactory` 不再直接注入“全部 provider 列表”
   - 改为注入单一聚合 bean `externalToolCallbackProvider`
   - 聚合 bean 内部显式排除 `mcpToolCallbackProvider`，避免 server 侧 provider 被混入 agent 外部工具集合

明确不采用：

- `spring.main.allow-circular-references=true`
- 通过配置兜底绕过真实依赖问题

## 4. 测试与验证口径

本次新增的验证重点：

- `QueryRewriteServiceImpl` 懒解析测试
  - 构造阶段不触发 `ChatClientRegistry` 解析
  - `llmRewriteEnabled=false` 时不触发 `ChatClientRegistry` 解析
- 最小启动上下文测试
  - 验证 `MCP Server Provider + QueryRewrite LLM wiring` 共存时，不再形成当前这条循环依赖
- `JChatMindFactory` provider 边界测试
  - 验证 `mcpToolCallbackProvider` 不进入 agent 外部工具 provider 集合

说明：

- 当前仓库存在独立的编译/模型类基线问题，和这次循环依赖修复不是同一个故障面
- 本次验证重点只针对“启动期 bean cycle 是否被拆掉”

## 5. 关联文档

- `docs/plans/mcp.md`
  - 需要补充：`MCP Server provider` 与 `agent 外部工具 provider` 的职责边界
- `docs/plans/next-phase-system-roadmap-2026-05-25.md`
  - 需要勘误：当前 `ApplicationContext` 启动失败并非单纯环境变量缺失
