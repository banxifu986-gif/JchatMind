# MCP 服务端

> 页面定位：外部 MCP 客户端（如 Notion）接入——API Key 主体映射、工具暴露、授权与审计。动 MCP 接入前必读。
>
> 最后核对：2026-11-14（按源码核验）。

## 1. 定位

Spring AI MCP Server（`mcp-server-webmvc`，type SYNC、protocol STREAMABLE）在 `/mcp`、`/mcp/*` 暴露知识库检索、邮件、数据库三类工具。`X-API-Key` 仅用于计算 SHA-256 指纹查找主体凭据及单一有效 grant——**不再作为共享配置的授权来源**。真实主体仅映射单一内部用户（生产已建立主体 `1 → user_id=10`）；无 tenant 模型，V1 不伪造 tenant 字段。

## 2. 关键文件索引

包根 `backend_v2/src/main/java/com/kama/jchatmind/mcp/`：

| 文件 | 职责 | 行数 |
| --- | --- | --- |
| [McpServerConfig.java](../../backend_v2/src/main/java/com/kama/jchatmind/mcp/McpServerConfig.java) | 工具注册 + `McpApiKeyFilter`（最高优先级） | 82 |
| [McpKnowledgeTool.java](../../backend_v2/src/main/java/com/kama/jchatmind/mcp/McpKnowledgeTool.java) | `mcpKnowledgeQuery` 工具 | 363 |
| [McpPrincipalAccessService.java](../../backend_v2/src/main/java/com/kama/jchatmind/mcp/McpPrincipalAccessService.java) | 指纹解析与审计写入 | 85 |
| [McpCallerIdentity.java](../../backend_v2/src/main/java/com/kama/jchatmind/mcp/McpCallerIdentity.java) | record principalId + userId | 4 |

schema：[sql/mcp/2026-08-18-create-mcp-principal-access.sql](../../sql/mcp/2026-08-18-create-mcp-principal-access.sql)。

## 3. 认证链路

1. `McpApiKeyFilter`（`Ordered.HIGHEST_PRECEDENCE`，注册于 `/mcp`、`/mcp/*`）：读 `X-API-Key` → `resolveCaller`（SHA-256 hex 指纹 → `selectActiveCallerByCredentialFingerprint`，凭据表只存指纹/状态，**无明文**）。
2. 成功：设置请求属性 `jchatmind.mcp.callerIdentity` / `jchatmind.mcp.correlationId`（每请求 UUID），记 `AUTHENTICATE/ALLOW`；失败：记 `AUTHENTICATE/DENY/"invalid_credential"` 并直接写 401 JSON。
3. `SecurityConfig` 仅放行到该过滤器——避免 Security 先返回 403 跳过 MCP 的 401 与审计。

grant 约束：部分唯一索引 `uq_mcp_principal_single_active_user_grant ON (principal_id) WHERE revoked_at IS NULL`——每主体至多一个有效内部用户 grant。

## 4. 检索工具链路（`McpKnowledgeTool.search` L71，`@Tool(name="mcpKnowledgeQuery")`）

```text
resolveCaller 为 null → 直接返回拒绝文本（此路径不写 KNOWLEDGE_QUERY 审计）
→ requireAccessibleKnowledgeBaseIds(kbIds, userId)：owner 校验（KB 范围只能收窄不能扩权）
→ 空范围 → 审计 ALLOW/"empty_scope"，返回 ""
→ ragRouter.decide(query, scope, ...) → ABSTAIN/CLARIFY/DIRECT 记审计（ABSTAIN 记 DENY）返回 route.reason()
→ route.searchScope() ∩ accessible（intersectRouteScope L205）
→ 结果缓存（RagRetrievalResultCache，key 含 indexRevisions/route/指纹）
→ MULTIMODAL_RAG：表格 → PDF 页 → 图片资产 → 普通检索 顺序合并（chunkId 去重、limit(topK)）
→ 空结果 → 审计 ABSTAIN/"no_evidence"；成功 → ALLOW/"retrieved"
→ BizException（越权）→ DENY/"knowledge_base_access_denied"
```

输出格式（`formatResultBlock` L298）：知识库/来源/路径/引用(chunkId)/内容五行，附加 ` | 页码: <pageNumber>`、` | 行号: <startLine>-<endLine>`、` | 资产: <type>:<id>`。

审计（`mcp_access_audit`）：`decision CHECK IN (ALLOW,DENY)`、`target_kb_ids JSONB DEFAULT '[]'`、`correlation_id NOT NULL`、`request_metadata` 恒 `{}`；**不记录查询正文、不记录原始凭据**。表为只追加（append-only）。

## 5. 不变量与契约

1. 调用方不得自报用户或扩大 KB 范围；模型/客户端传入 `kbIds` 只能收窄。
2. 未认证主体不产生 `KNOWLEDGE_QUERY` 审计（提前返回）；认证失败产生 `AUTHENTICATE/DENY`。
3. 未来引入共享、角色或 tenant 前，必须先迁移独立的授权与审计模型——不得把 grant 表误当 ACL。
4. 审计查询、保留策略、审批工作流不在 V1 范围。
5. MCP 客户端装配默认关闭：`MCP_CLIENT_ENABLED=false`（stdio Notion 连接，见 [13](13-数据模型与迁移.md) §5）；服务端始终装配。

## 6. 验证入口

默认跑：`McpServerConfigTest`、`McpKnowledgeToolTest`、`McpKnowledgeToolAuthorizationContractTest`、`McpPrincipalAccessServiceTest`、`McpPrincipalAuditServiceContractTest`、持久化契约 `McpPrincipalAccessPersistenceContractTest`、`McpAccessAuditPersistenceContractTest`、`McpPrincipalMigrationContractTest`。集成：`McpToolLoadingTest`（`-Dmcp.integration.enabled=true`）。

## 7. 关联文档

- 检索内核与授权收敛：[08-RAG检索与排序](08-RAG检索与排序.md) §5
- 鉴权与审计表：[02-认证鉴权与异步邮件](02-认证鉴权与异步邮件.md)、[13-数据模型与迁移](13-数据模型与迁移.md)
- 工具 Harness（本地工具侧）：[06-工具Harness](06-工具Harness.md)
- 归档：[../archive/2026-08-15/plans/active/mcp.md](../archive/2026-08-15/plans/active/mcp.md)
