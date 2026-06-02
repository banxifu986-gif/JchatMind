# JchatMind 下一阶段系统规划：从组件成熟到系统集成

**日期：** 2026-05-25
**状态：** 规划中
**范围：** 宏观系统视角，非单点技术方案

---

## 1. RAG 收口确认

### 1.1 已达成的能力

| 能力 | 状态 | 证据 |
|------|------|------|
| 7 通道召回（向量 + 6 路标题/内容通道） | 完成 | `RagServiceImpl`，1295 行 |
| RRF 全通道融合（k=60） | 完成 | 统一融合，无通道间评分不匹配 |
| 多维重排序（8 个信号维度） | 完成 | 词法、标题、内容、路径、结构、BM25 信号 |
| Query Rewrite（规则优先，LLM rewrite 默认关闭） | 完成 | 意图检测、自动上下文选择、话题切换检测 |
| Embedding LRU 缓存（进程内） | 完成 | P95 延迟改善 |
| 结构化 chunk 元数据 | 完成 | `RagChunkSupport.java` |
| 评测框架（5 个测试类，fixture + 真实 KB） | 完成 | Recall@1/3/5/10，MRR@3/10，A/B 对比 |

**当前稳定基线：**
- fixture：Recall@1/3/5/10 = 1.0000，MRR@3/10 = 1.0000
- 真实 KB title_recall Recall@1 = 0.9000
- 真实 KB rerank_quality Recall@1 = 0.9231
- 真实 KB follow_up_contextual Recall@1 = 0.7500

### 1.2 已明确停止的方向

以下方向经实际验证已回退，无新证据前不得重新启动：

1. L2 距离改 cosine distance（真实 KB 小样本回退）
2. RRF 共识强度注入 rerank（fixture 与真实 KB 均明显回退）
3. Query 模板细磨（收益不再来自模板层）
4. 默认开启 LLM rewrite（证据不足）

### 1.3 默认回归入口（各阶段保持绿色）

```bash
# fixture 回归
mvn -q -Dtest=QueryRewriteServiceImplTest test
mvn -q -Dtest=RagRecallEvaluationTest test

# 真实 KB 小样本回归
mvn -q -Dtest=RagRecallEvaluationTest "-Drag.eval.mode=real" "-Drag.eval.real-kb-id=..." "-Drag.eval.real-max-documents=1" "-Drag.eval.real-max-cases=40" test
```

---

## 2. 阶段一：前后端联调（立即执行）

**理由：** 收口文档明确指出下一步是联调而非 RAG 单点深挖。各组件在隔离状态下通过了单元测试，但尚未作为一个完整产品端到端验证。

### 2.1 联调目标

验证完整的用户聊天体验在以下维度工作：

| 维度 | 验证内容 | 当前已知状态 |
|------|---------|------------|
| 会话生命周期 | 创建会话、发送消息、接收响应、持久化历史 | 各 Controller 独立存在；端到端未测试 |
| SSE 流式传输 | 实时 agent 状态与内容推送 | `SseController` + `SseService` 存在；前端 `AgentChatView` 通过 `EventSource` 订阅 |
| 工具执行 | Agent 正确调用 KnowledgeTools、EmailTools、DataBaseTools | 工具有单元测试；agent 编排未测试 |
| 人工审批 | Harness 在高风险工具暂停，SSE 推送审批事件，用户审批/拒绝 | `HarnessRunner` 存在；前端无审批 UI |
| 检索上下文持久化 | 多轮 follow-up 正确使用会话上下文 | `ChatSessionFacadeService.getRetrievalContext/updateRetrievalContext` 存在 |
| 知识库 CRUD | KB 创建、文档上传、文档列表/删除端到端工作 | Controller + 前端视图存在；上传仅限 `.md` |
| 用户记忆流程 | 记忆提取、候选列表、确认、删除、语义召回 | `UserMemoryFacadeService` 存在；`UserMemoryView` 存在 |
| Agent CRUD | Agent 创建、工具/KB 选择、模型选择、会话创建 | 前后端均存在完整 CRUD |

### 2.2 联调任务

**T1：核心聊天回路冒烟测试**
- 启动后端（有效配置）
- 启动前端
- 创建带知识库的 Agent
- 发送消息并验证：(a) 消息持久化，(b) SSE 事件接收，(c) Agent 响应展示，(d) 工具调用可见
- 验证会话列表正确显示

**T2：聊天场景中验证 RAG 集成**
- 上传 Markdown 文档到 KB
- 发送应触发 KnowledgeTool 的查询
- 验证检索到的 chunk 出现在响应中
- 测试多轮 follow-up：追问并验证上下文延续
- 测试 topic switch：切换话题，验证旧上下文不污染结果
- 测量端到端延迟（用户消息到首 token / 完整响应）

**T3：SSE 协议完整性验证**
- 检查 `AgentChatView.tsx` SSE 事件处理
- 验证所有 `SseMessageType` 变体（AI_GENERATED_CONTENT、AI_PLANNING、AI_THINKING、AI_EXECUTING、AI_DONE）被后端发出、前端处理
- 检查异常情况：SSE 断开、重连、agent 执行错误

**T4：工具链完整性审计**
- 验证 `TerminateTool` 正确终止 agent
- 验证 `EmailTools` 异步发送端到端工作
- 验证 `DataBaseTools` SELECT 查询正确执行
- 检查 `FileSystemTools`（已注释 `@Component`）和 `DirectAnswerTool`（已注释 `@Component`）是否有明确的决策记录

**T5：MCP 服务端可访问性验证**
- 启动后端（MCP server 启用）
- 使用 curl 或最小 MCP 客户端调用 `tools/list`
- 验证三个注册工具（mcpKnowledgeQuery、sendEmail、databaseQuery）
- 测试 API key 认证（缺少/无效时 401）
- 测试限流/错误处理

### 2.3 阶段一退出标准

| 标准 | 阈值 |
|------|------|
| 核心聊天回路通过 | 用户消息到达 agent，agent 响应展示 |
| RAG 检索在聊天上下文中触发 | KnowledgeTool 被调用，结果显示在响应中 |
| SSE 流式传输稳定 | 所有 SSE 事件类型被接收，无连接丢失 |
| 多轮 follow-up 上下文工作 | 追问在相同文档上下文中检索 |
| MCP tools/list 返回正确工具 | 3 个工具列出，schema 正确 |
| 所有现有 RAG 回归测试保持绿色 | 基线不变 |
| 无新增后端测试失败 | 与联调前基线一致 |

### 2.4 阶段一预期发现的问题

基于代码分析，以下问题较可能在联调中暴露：

1. **前端缺少审批 UI**：`HarnessController` 有 approve/reject/pending 端点，但 `AgentChatView.tsx` 不渲染审批组件。任何需要审批的工具都会使 agent 挂起。
2. **SSE 重连缺失**：前端创建 `EventSource` 但无重连逻辑。连接断开后用户看不到后续更新。
3. **用户身份硬编码**：`useUser()` 从 context 提供 `userId`，但无登录流程。`userId` 可能是静态值或 undefined。
4. **文档上传仅限 Markdown**：`DocumentFacadeServiceImpl` 仅处理 `.md`；前端 accept 属性可能也只接受 `.md`。
5. **ApplicationContext 启动失败勘误（2026-05-25）**：当前已确认的一类启动失败不是环境变量缺失，而是 `MCP Server` 与 `Query Rewrite LLM wiring` 叠加形成的循环依赖。联调前需要补最小启动上下文测试，保护 bean 图稳定性。

---

## 3. 系统层面差距分析

### 3.1 关键差距（阻塞核心产品体验）

| 差距 | 影响 | 证据 |
|------|------|------|
| **无端到端测试基础设施** | 无法验证用户 UI 操作产生正确系统行为 | 前端无测试套件；后端仅单元测试 |
| **前端审批 UI 缺失** | 高风险工具调用时 agent 无限挂起 | `HarnessController` 存在；`AgentChatView` 无审批渲染 |
| **SSE 可靠性未验证** | 用户可能看到不完整或空白的 agent 响应 | 无重连逻辑，无错误恢复 |
| **文档摄入仅限 Markdown** | 用户无法上传 PDF、Word、HTML、纯文本 | `DocumentFacadeServiceImpl` 仅处理 `.md` |

### 3.2 重要差距（降低体验或限制使用场景）

| 差距 | 影响 | 证据 |
|------|------|------|
| **无认证/授权** | 任何用户可访问任何人的聊天会话 | `userId` 作为查询参数传递，非会话 token |
| **MCP 客户端未接入** | Agent 无法调用外部 MCP 工具（Notion、网页搜索等） | `docs/plans/mcp.md` 记录了计划；`McpServerConfig` 存在但无客户端连接代码 |
| **用户记忆 UX 基础** | 记忆确认/删除可用但完整生命周期未验证 | `UserMemoryView.tsx` 存在但展示结构未知 |
| **错误处理不一致** | 部分错误返回结构化 `ApiResponse`，部分抛异常 | 无全局错误处理模式；前端无 ErrorBoundary |
| **FileSystemTools 已禁用** | Agent 无法读写本地文件 | `@Component` 被注释，标注"禁用文件系统相关工具" |

### 3.3 中等差距（影响完善度或扩展性）

| 差距 | 影响 |
|------|------|
| **无可观测性（工具指标除外）** | 无法诊断生产问题（延迟分解、RAG 缓存命中率、token 用量） |
| **无限流** | API 端点无滥用或高频调用保护 |
| **无会话超时/清理** | 孤立聊天会话无限累积 |
| **DirectAnswerTool 已禁用** | Agent 对简单对话也总是使用工具 |

---

## 4. 分阶段路线图

```
阶段一：前后端联调          （当前 - 1~2 周）
阶段二：关键差距修复        （2~4 周）
阶段三：质量加固            （4~6 周）
阶段四：功能扩展            （6 周+）
阶段五：生产就绪            （待定）
```

### 4.2 阶段二：关键差距修复

**按用户影响排序的优先级：**

| 优先级 | 差距 | 理由 |
|--------|------|------|
| P0 | 前端审批 UI | 无此 UI 则 agent 在高风险工具上挂起 |
| P0 | SSE 可靠性 | 流式不稳定则聊天体验崩溃 |
| P1 | 端到端测试基础设施 | 无测试则后续所有变更都有回归风险 |
| P1 | Markdown 以外的文档摄入 | 扩展知识库效用是核心价值主张 |
| P2 | 错误处理一致性 | 防止用户困惑和调试困难 |

**P0a：前端审批 UI**
- 在 `AgentChatView` 中添加审批卡片组件
- 处理 `TOOL_APPROVAL_REQUIRED` SSE 事件类型
- 将审批/拒绝按钮连接到 `HarnessController` 端点
- 展示工具名、参数和上下文
- 优雅处理审批超时

**P0b：SSE 可靠性**
- 添加 `EventSource` 指数退避重连
- 添加心跳机制
- 在 UI 中添加加载/错误状态
- 验证会话关闭时后端 SSE emitter 清理

**P1a：端到端测试基础设施**
- 选项 A（轻量）：Playwright/Cypress 覆盖关键用户旅程
- 选项 B（重量）：Spring Boot 集成测试
- 最少：3 个关键旅程自动化（创建 agent + 聊天、上传文档 + 查询、多轮 follow-up）

**P1b：文档摄入扩展**
- 添加 PDF 解析（Apache PDFBox 或类似）
- 添加纯文本解析
- 添加 HTML 解析（JSoup 或类似）
- 将解析器接入 `DocumentFacadeServiceImpl`
- 扩展前端 Upload 组件的 accept 属性

### 4.3 阶段三：质量加固

| 优先级 | 差距 | 理由 |
|--------|------|------|
| P0 | 认证/授权 | 安全问题；阻塞多用户部署 |
| P1 | MCP 客户端接入 | 解锁 Notion/网页搜索等外部能力 |
| P1 | 用户记忆 UX 完善 | 完整生命周期含提取触发和证据展示 |
| P2 | 可观测性仪表板 | 诊断生产问题必需 |
| P2 | 限流 | 生产安全 |

**关键活动：**
- Spring Security + JWT 或 API key 认证
- 实现 `JChatMindFactory` 中的 `buildExternalToolCallbacks()` 接入外部 MCP 工具
- 记忆生命周期完整 UX：提取触发、证据展示、搜索过滤
- 结构化日志（trace ID）+ 延迟埋点（rewrite / recall / rerank / LLM 分阶段耗时）
- RAG 专用指标：缓存命中率、向量召回数、BM25 召回数、RRF 融合统计

### 4.4 阶段四：功能扩展

候选方向（按用户价值排序）：
1. FileSystemTools 重新启用（安全审计 + 路径沙箱）
2. DirectAnswerTool 重新启用（提升对话效率）
3. 多格式检索结果 UI：展示 chunk 源文档、路径、相关性分数
4. Agent 配置模板：预构建常见任务 agent
5. 会话导出：聊天历史导出为 Markdown/PDF
6. 批量文档摄入

### 4.5 阶段五：生产就绪

- 数据库迁移策略
- 备份恢复流程
- 水平扩展评估（SSE 会话黏性）
- 安全审计（依赖扫描、OWASP 检查）
- 负载性能测试

---

## 5. 联调期间的可观测性

### 5.1 阶段一人工测量

| 指标 | 测量方式 | 预期范围 |
|------|---------|---------|
| 端到端延迟（消息到首 token） | 秒表或浏览器 DevTools | 简单查询 < 3s，RAG 查询 < 10s |
| 端到端延迟（消息到完整响应） | 秒表 | < 30s |
| SSE 事件序列完整性 | 浏览器 console log | PLANNING → THINKING → EXECUTING → GENERATED_CONTENT → DONE |
| RAG 召回数 | 后端日志 | KB 查询 > 0，非 KB 查询 = 0 |
| 错误率 | 手动统计 | < 5% |
| 工具调用成功率 | 后端日志或前端观察 | > 95% |

### 5.2 阶段三自动化指标

| 指标 | 实现方式 |
|------|---------|
| 请求延迟（p50/p95/p99） | Micrometer `@Timed` |
| RAG 流水线延迟分解 | 自定义 Micrometer 计时器 |
| Embedding 缓存命中率 | Counter 指标 |
| 工具执行计数（按类型） | `ToolMetricsService`（已存在） |
| SSE 连接数 | Gauge 指标 |
| 错误计数（按类型） | `@ControllerAdvice` Counter |

---

## 6. 联调后决策框架

### 6.1 问题分类决策树

```
联调发现问题
    |
    +-- 是接口/数据流问题？
    |       是 -> 先修联调层，不动 RAG/工具逻辑
    |       否 -> 继续
    |
    +-- 与 RAG 检索质量相关？
    |       是 -> 应用 RAG 收口决策规则（6.2）
    |       否 -> 继续
    |
    +-- 是缺失功能？
    |       是 -> 检查是否在阶段二/三待办中
    |              在 -> 按用户影响排序
    |              不在 -> 加入阶段四候选列表
    |       否 -> 继续
    |
    +-- 是性能/扩展性问题？
            是 -> 先 profile 再优化；先加埋点
            否 -> 归类为 bug，直接修复
```

### 6.2 RAG 专项决策规则（来自收口文档）

1. **接口不稳定 / 数据不通 / 展示不对** → 先修联调，不动 RAG
2. **候选明显相关但首位不稳** → 考虑 rerank 层
3. **压根没召回到** → 考虑 recall / rewrite
4. **少量 follow-up case 不理想** → 不做补丁，先积累现象

### 6.3 优先级定义

| 级别 | 定义 | 响应 |
|------|------|------|
| P0 - 阻塞 | 核心用户旅程不可用 | 立即修复 |
| P1 - 关键 | 主要功能损坏（审批、邮件、MCP） | 当前阶段修复 |
| P2 - 重要 | 体验下降但有替代方案 | 安排下一阶段 |
| P3 - 锦上添花 | 外观或极端情况 | 待办列表 |

### 6.4 各阶段回归门禁

每个阶段关闭前运行：
1. `mvn -q -Dtest=QueryRewriteServiceImplTest test`（必须通过）
2. `mvn -q -Dtest=RagRecallEvaluationTest test`（fixture Recall@5 必须 = 1.0）
3. `npm run lint && npm run build`（必须通过）
4. 该阶段新增的端到端测试（必须通过）

---

## 7. 风险登记

| 风险 | 概率 | 影响 | 缓解措施 |
|------|------|------|---------|
| 后端测试因 ApplicationContext 长期无法修复 | 高 | 中 | 联调测试补偿；测试配置修复作为独立任务 |
| 前端审批 UI 需要显著改动 SSE 协议 | 中 | 高 | 先审计 SSE 协议；按现有事件格式设计审批 UI |
| Embedding 服务（Ollama）在测试期间不可用 | 中 | 高 | 准备回退方案：跳过 RAG 测试，仅测试聊天流程 |
| 非 Markdown 解析引入 chunking 质量回归 | 低 | 中 | 每添加一个新解析器后运行 RAG 回归 |
| MCP 客户端实现比计划更复杂 | 中 | 低 | 如阶段二超期，阶段三可推迟 |
| 多用户认证引入破坏性 API 变更 | 高 | 中 | 规划 API 版本化或向后兼容的 `userId` 提取 |

---

## 8. 治理

### 8.1 阶段门禁

每个阶段需明确签收后才能进入下一阶段：
- 阶段一 → 二：所有冒烟测试通过，问题列表已分类
- 阶段二 → 三：关键差距已解决，e2e 测试绿色
- 阶段三 → 四：质量指标可接受，认证已工作
- 阶段四 → 五：功能集稳定，无 P0/P1 bug

### 8.2 基线保护

RAG 回归套件是系统金丝雀。任何导致 RAG 回归的变更必须：
1. 若为 fixture 测试回归 → 立即回退
2. 若仅为真实 KB 小样本回归 → 诊断并文档化
3. 绝不未经完整回归验证即发布

### 8.3 文档标准

每个阶段产出：
- 阶段收口文档（与 `rag-closure-2026-05-24.md` 格式一致）
- 更新后的基线指标
- 被拒绝方案的决策日志

---

## 关键文件速查

| 文件 | 作用 |
|------|------|
| `ui/src/components/views/AgentChatView.tsx` | 联调核心：SSE 事件处理、审批 UI 缺口、消息流 |
| `backend_v2/.../agent/harness/HarnessRunner.java` | 审批编排；前端必须与此集成 |
| `backend_v2/.../service/impl/RagServiceImpl.java` | RAG 流水线（1295 行）；检索流集成问题追踪起点 |
| `backend_v2/.../service/impl/DocumentFacadeServiceImpl.java` | 文档摄入瓶颈；非 Markdown 格式扩展起点 |
| `backend_v2/.../rag/RagRecallEvaluationTest.java` | RAG 回归基线；所有阶段须保持绿色 |
| `backend_v2/.../service/impl/QueryRewriteServiceImpl.java` | Query Rewrite 实现（698 行） |
| `backend_v2/.../agent/tools/KnowledgeTools.java` | Agent 侧 RAG 工具入口；会话上下文持久化 |
| `docs/records/rag-closure-2026-05-24.md` | RAG 收口文档；决策规则的权威来源 |
