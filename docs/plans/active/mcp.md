# MCP 双向集成开发方案

## 概述

为 JchatMind 引入 MCP（Model Context Protocol）双向集成能力：

- **MCP Server（向外暴露）**：供外部业务项目通过 MCP 协议调用 JchatMind 的工具能力（知识库检索、邮件、数据库）
- **MCP Client（向内消费）**：让 JchatMind Agent 能调用外部 MCP Server 的工具（如 Notion 搜索）

改造方式为零侵入——不修改任何现有业务类，仅新增依赖、配置和适配层。

## 分支

`feat/mcp-server`（基于 `main`）

## 依赖

在 `backend_v2/pom.xml` 新增两个依赖，版本由已有的 `spring-ai-bom:1.1.0` 管理：

```xml
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-server-webmvc</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-starter-mcp-client</artifactId>
</dependency>
```

## 架构图

```
┌─────────────────────────────────────────────────────┐
│                   JchatMind                          │
│                                                      │
│  ┌──────────┐  ┌──────────────┐  ┌───────────────┐  │
│  │Agent 执行│  │MCP Server    │  │MCP Client     │  │
│  │(Think→   │  │(向外暴露)     │  │(向内消费)      │  │
│  │ Execute) │  │              │  │               │  │
│  └────┬─────┘  └──────┬───────┘  └───────┬───────┘  │
│       │               │                  │           │
│       │   本地工具 +   │                  │           │
│       │   MCP远程工具  │                  │           │
│       ▼               ▼                  ▼           │
│  ToolCallback[]   POST /mcp/message   stdio/HTTP     │
│                   GET  /mcp/sse       npx notion-mcp │
└─────────────────────────────────────────────────────┘
         ▲                    │              │
         │                    ▼              ▼
   外部业务项目          其他MCP Client    Notion MCP
   (Spring Boot等)      (调用JchatMind)   Server
```

## 一、MCP Server（向外暴露）

### 1.1 新增文件

#### `mcp/McpKnowledgeTool.java`

无状态的 MCP 工具适配器，绕过 `KnowledgeTools` 的 `fork()` 状态绑定问题。

- `@Component`，不实现项目自定义 `Tool` 接口（避免被 Agent 工具列表自动收录）
- `@Tool(name = "mcpKnowledgeQuery")` 对外暴露
- 直接调用 `RagService.retrieve(kbIds, query, null, 3)`（context 传 null，MCP 无会话上下文）
- 用 `KnowledgeBaseMapper.selectByIdBatch(kbIds)` 解析 KB 名称
- 结果格式化：知识库名 / 来源 / 路径 / 内容

参数：
| 参数 | 类型 | 必传 | 说明 |
|---|---|---|---|
| query | String | 是 | 查询文本 |
| kbIds | List\<String\> | 是 | 知识库 ID 列表 |

#### `mcp/McpServerConfig.java`

配置类，两项职责：

**a) `@Primary ToolCallbackProvider`**

用 `MethodToolCallbackProvider` 显式注册白名单工具，替换 Spring AI 自动扫描产生的默认 Provider。排除无 fork 就无法使用的 `KnowledgeTools` 和 Agent 专用的 `TerminateTool`。

暴露的 MCP 工具：
| 工具名 | Bean | 用途 |
|---|---|---|
| mcpKnowledgeQuery | McpKnowledgeTool | 知识库语义检索 |
| sendEmail | EmailTools（现有） | 发送邮件 |
| databaseQuery | DataBaseTools（现有） | 数据库查询 |

**b) API Key 鉴权过滤器**

- `FilterRegistrationBean`，拦截 `/mcp/*`
- 验证请求头 `X-API-Key`，与 `${mcp.api-key}` 比对
- 配置为空时放行所有请求（开发环境免鉴权）
- 不匹配返回 401

### 1.2 MCP 端点

Spring AI MCP Server Boot Starter 自动注册：

| 端点 | 方法 | 用途 |
|---|---|---|
| `/mcp/sse` | GET | MCP 客户端 SSE 连接 |
| `/mcp/message` | POST | JSON-RPC 消息（工具发现/调用） |

### 1.2.1 启动期依赖约束

- `MCP Server` 侧 `ToolCallbackProvider` 只用于对外暴露 `/mcp/*` 工具，不应被 `JChatMindFactory` 当作 agent 外部工具 provider 收集。
- `McpKnowledgeTool -> RagService -> QueryRewriteServiceImpl -> ChatClientRegistry` 与 Spring AI Tool Calling 自动配置叠加时，可能形成启动期循环依赖。
- `QueryRewriteServiceImpl` 对模型侧 `ChatClient` 的解析必须保持请求期惰性，不能在构造阶段提前解析。
- 结论：`MCP Server provider` 和 `agent 外部工具 provider` 必须显式分边界，不能混用“注入全部 `ToolCallbackProvider`”的做法。

### 1.3 调用方指南

其他业务项目接入示例（Spring Boot + WebClient）：

```java
// 列出可用工具
String tools = webClient.post()
    .uri("http://jchatmind:8080/mcp/message")
    .header("X-API-Key", "shared-secret")
    .bodyValue("""
        {"jsonrpc":"2.0","id":"1","method":"tools/list"}
        """)
    .retrieve()
    .bodyToMono(String.class)
    .block();

// 调用知识库检索
String result = webClient.post()
    .uri("http://jchatmind:8080/mcp/message")
    .header("X-API-Key", "shared-secret")
    .bodyValue("""
        {"jsonrpc":"2.0","id":"2","method":"tools/call","params":{"name":"mcpKnowledgeQuery","arguments":{"query":"查询文本","kbIds":["kb-uuid-1"]}}}
        """)
    .retrieve()
    .bodyToMono(String.class)
    .block();
```

客户端无需引入 Spring AI 依赖，只需发 HTTP JSON-RPC 请求即可。

---

## 二、MCP Client（向内消费）

### 2.1 配置

`application.yaml`，支持 stdio 或 HTTP 传输：

```yaml
spring:
  ai:
    mcp:
      client:
        connections:
          notion:
            type: stdio                    # 进程桥接模式
            command: npx
            args:
              - "-y"
              - "@notionhq/notion-mcp-server"
            env:
              NOTION_API_KEY: ${NOTION_API_KEY:}
```

### 2.2 Agent 接入

改造 `JChatMindFactory`（最小改动）：

- 新增 `List<ToolCallbackProvider>` 依赖注入，收集所有非本地工具的 MCP 远程工具回调
- 新增 `buildExternalToolCallbacks()` 方法：遍历所有 `ToolCallbackProvider`，按工具名去重后追加到 Agent 的工具列表
- `create()` 方法中追加一行：`toolCallbacks.addAll(buildExternalToolCallbacks(toolCallbacks))`

### 2.3 工具合并去重逻辑

```
本地工具 (KnowledgeTools, EmailTools, DataBaseTools, TerminateTool)
    +
MCP 远程工具 (Notion search_notion, get_notion_page, create_notion_page, ...)
    ↓
去重：按 ToolDefinition.name 去重，本地优先
    ↓
最终 Agent 可用工具列表
```

`@Primary ToolCallbackProvider` 提供的本地工具不会与 `buildToolCallbacks()` 重复，因为 `buildExternalToolCallbacks()` 通过名称去重。

### 2.4 扩展更多 MCP 服务

只需在 `application.yaml` 中新增连接配置：

```yaml
spring:
  ai:
    mcp:
      client:
        connections:
          notion:        # Notion 工作区
            type: stdio
            command: npx
            args: ["-y", "@notionhq/notion-mcp-server"]
            env:
              NOTION_API_KEY: ${NOTION_API_KEY:}
          web-search:    # 联网搜索
            type: http
            url: http://search-mcp:8080/mcp/sse
```

Agent 启动时自动发现所有连接处 MCP Server 的工具，无需额外代码。

---

## 三、配置项汇总

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `spring.ai.mcp.server.name` | JchatMind MCP Server | MCP Server 名称 |
| `spring.ai.mcp.server.version` | 1.0.0 | 版本号 |
| `spring.ai.mcp.server.type` | SYNC | 传输模式 |
| `mcp.api-key` | 空（不鉴权） | MCP Server API Key |
| `spring.ai.mcp.client.connections.<name>.type` | — | stdio 或 http |
| `NOTION_API_KEY` | 空 | Notion 集成 API Key |

## 四、验证步骤

### MCP Server 验证

```bash
# 列出工具
curl -X POST http://localhost:8080/mcp/message \
  -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":"1","method":"tools/list"}'

# 调用检索
curl -X POST http://localhost:8080/mcp/message \
  -H "Content-Type: application/json" \
  -H "X-API-Key: test-key" \
  -d '{"jsonrpc":"2.0","id":"2","method":"tools/call","params":{"name":"mcpKnowledgeQuery","arguments":{"query":"测试查询","kbIds":["actual-kb-id"]}}}'

# 鉴权验证（设置 MCP_API_KEY 后无 key 应返回 401）
```

### MCP Client 验证

1. 在 `.env` 中设置 `NOTION_API_KEY`
2. 启动应用，检查日志确认 Notion MCP 连接建立成功
3. 通过 Agent 对话测试，确认 Notion 工具出现在工具调用中

## 五、不改动的文件

- `KnowledgeTools.java` — 不修改，Agent 内部继续用 fork 模式
- `JChatMindFactory.java`（除新增字段和方法外，核心流程不变）
- 所有 Controller / Service / Mapper — 不修改
- 业务逻辑零影响

## 六、后续扩展

- Agent 配置支持 `allowedMcpTools` 字段，按 Agent 粒度控制可用的外部 MCP 工具
- 接入更多 MCP Server（网页抓取、代码执行、文件管理等）
- MCP Server 端增加工具级别的调用频率限制
