# Skill 与工作流

> 页面定位：内置 Skill（同步受控检索 + 默认关闭的异步 task 链路）与显式 Planner→Verifier→Executor 工作流。动 Skill、工作流前必读。
>
> 最后核对：2026-11-14（按源码核验）。

## 1. 定位

两者都是"在 ReAct 与 Harness 之外显式编排"的尝试，但边界不同：

- **内置 Skill**：服务端固定模板的受控只读知识检索，同步链路已启用；异步 task 链路（HTTP submit/status + Rabbit + DB dispatch lease + worker）已实现但由环境开关默认关闭。
- **显式工作流**：Planner 输出结构化计划 → Verifier 校验证据/矛盾/权限 → Executor 受控顺序执行，**仅通过 `POST /api/workflows/execute` 显式触发**，Planner 未接入普通聊天主路径。

## 2. 内置 Skill

### 2.1 同步链路（已启用）

```text
POST /api/agents/{agentId}/skills/{skillId}/execute
→ BuiltinSkillExecutionService.execute → prepare（归属/授权校验链）：
   会话-Agent 归属 → Agent owner → allowedTools 含 KnowledgeTool → 绑定 KB 经 owner 过滤
→ BuiltinSkillRegistry.prepare：未知 skill/null input/含 tools 键/未知字段/空 question/空授权范围/kbIds ⊄ authorizedScope 拒绝
→ BuiltinSkillExecutor.execute：按 SkillDefinition 缓存的独立线程池（core=max=2，SynchronousQueue+AbortPolicy，daemon）
   → RejectedExecutionException → abstained"并发预算已满"；Future.get(30s) 超时 → cancel(true) + abstained
→ HarnessedSkillKnowledgeToolExecutor：合成 agentId "builtin-skill:technical-decision-comparison"、step=1、
   单 ToolCall("KnowledgeTool") → harnessRunner.beforeExecution + awaitApprovals → 非 ALLOW → blocked
   → ALLOW → knowledgeTools.fork(userId, sessionId, kbDTOs) → HarnessToolCallbackProxy.call
→ BuiltinSkillRegistry.validateOutput：abstained 必带 reason；否则必带 conclusion + 非空 evidence
   （每项含 chunkId+kbId 且 kbId ∈ invocation.knowledgeBaseIds()）
```

**唯一登记**：`technical-decision-comparison@v1`（SYNC、timeout 30s、并发 2、`requiresApproval=false`）。

### 2.2 异步 task 链路（`builtin-skill.task.dispatch.enabled` 默认 false）

```text
POST .../skills/{skillId}/tasks（header Idempotency-Key ≤128 字符）
→ BuiltinSkillTaskService.submit（@Transactional）：幂等键 trim、≤128
   requestSha256 = SHA-256({requesterUserId, agentId, sessionId, skillId, skillVersion, input{question, kbIds sorted}})
   insertIfAbsent==1 新建；否则取既有任务比对 sha，不同 → "幂等键已用于不同任务请求"
   任务初始 PENDING、maxAttempts=5；input_snapshot 仅冻结 {question, kbIds sorted}
→ @Scheduled(fixedDelay=250ms) 扫 selectDispatchableTaskIds(64) → acquireDispatchLease(60s)==1 才 publish
→ RabbitMQ builtin-skill-task.queue（2 消费者 / prefetch 1）
→ BuiltinSkillTaskWorker.onMessage：claimExecutionTask(300s) CAS 领取
   → 执行期重新跑 executionService.prepare（用冻结 input 二次校验归属与授权）
   → skill_version 与注册表不一致 → 永久失败
   → 输出校验失败 → failPermanently("SkillOutputValidationFailure")
   → 其他异常 attempt<5 → retryExecutionTask(延迟 30s)；≥5 → DEAD_LETTER
→ GET /api/skill-tasks/{taskId}（仅 requester 可查）
```

任务状态 CHECK：`PENDING/QUEUED/RUNNING/RETRYING/WAITING_APPROVAL/SUCCEEDED/FAILED/CANCELLED/DEAD_LETTER`；`max_attempts BETWEEN 1 AND 10`；`request_sha256 ~ ^[0-9a-f]{64}$`；幂等 UNIQUE `(requester_user_id, idempotency_key)`；partial 索引 dispatch `(status,next_attempt_at,created_at) WHERE status IN (PENDING,RETRYING)` 与 running lease。恢复：`@Scheduled(fixedDelay=30_000)` `recoverExpiredExecutionTasks(30s)` 回收过期执行 lease。

## 3. 显式工作流

### 3.1 链路

```text
POST /api/workflows/execute
→ WorkflowExecutionService.execute(ownerId, command) L74：
   command 四字段必填、request ≤16_384；session-Agent 归属 + Agent owner
   → webhookProperties.requireSource(sourceId) 且 source.ownerId==ownerId
   → agent allowedTools 必须含 KnowledgeTool；agentKbIds ∩ source.allowedKbIds 为空拒绝
   → 检索 RETRIEVAL_LIMIT=10 走 RagRetrievalResultCache（route 标识 "workflow"）
   → evidence 映射 WorkflowEvidence(chunkId, chunkId, content) —— factKey 即 chunkId（L253-267）
→ WorkflowPlanner.plan（严格 schema：plan 仅 {maxSteps, timeoutSeconds, steps}；step 仅 {id, toolName, factKey, claim, evidence}；
   evidence 仅 {chunkId, factKey, statement}；hasOnlyFields 拒绝多余字段；evidence 必须与检索结果记录级相等）
→ 拒绝 → fallback：WorkflowSingleAgentFallbackAnswerService.answer（证据 ≤16_000 字符，只读回答不调工具）→ mode=SINGLE_AGENT_FALLBACK
→ 通过 → WorkflowPlanExecutor.executeInternal：执行前再次 verify（防篡改）
   → FutureTask 提交 workflowTaskExecutor（core=2/max=4/queue=20）→ execution.get(plan.timeoutSeconds())
   → 超时 cancel(true)，失败则取已完成结果 → timedOut
   → 步骤顺序执行：beforeExecution（webhook 变体）→ 非 ALLOW → BLOCKED + recordSyntheticOutcome
      → WorkflowToolCallback 经 HarnessToolCallbackProxy.call → 执行后检查线程中断
   → 非 SUCCEEDED 即 halted
→ WorkflowExecutionAuditRecorder.record → 响应 traceId + mode
```

### 3.2 契约

- `WorkflowPlanVerifier`：`MAX_AGENT_STEPS=20`、`MAX_TOOL_TIMEOUT_SECONDS=30`；maxSteps∈[1,20]、实际步数≤maxSteps、timeout∈[1,30]、step id 唯一、toolName∈allowedTools、factKey/claim 非空、同 factKey 矛盾声明拒绝、每步 evidence 非空且 factKey 匹配。
- Planner 输入/输出各 16,384 字符上限。
- 审计只存哈希：`request_sha256/plan_sha256/step outputSha256` + step 元数据（仅 `{stepId, toolName, status, outputSha256}`，原始 I/O 不落库）；outcome 枚举 `SUCCEEDED/REJECTED/FAILED/TIMED_OUT/FALLBACK_SUCCEEDED/FALLBACK_FAILED`，failure_code=`WORKFLOW_<outcome>`；`expires_at=created_at+90 天`。
- 保留清理：`WorkflowExecutionAuditRetentionJob` 每小时（cron `0 0 * * * *`），批 500 条、单次最多 20 批。
- 拒绝/异常/超时都停止后续步骤（Future cancel/interruption）；跨进程恢复与多 Agent 协作未实现。

## 4. 受控知识库删除（deletion 包，同属"显式编排"）

`DELETE /api/knowledge-bases/{knowledgeBaseId}` → `KnowledgeBaseDeletionTaskServiceImpl`（advisory lock + 幂等重放）→ 同事务写 task + audit → 以 `id + owner_id` 删 KB（外键级联清理绑定/文档/chunk/摄入任务）→ RabbitMQ `knowledge-base-deletion.queue`（1 消费者 / 1 prefetch / retry TTL 30s）→ `KnowledgeBaseDeletionTaskConsumer` → `deleteKnowledgeBaseDirectory`（仅删 `document.storage.base-path/<kbId>` 规范化目录，越出基目录拒绝）→ 失败重试至 DEAD_LETTER。任务表不引用 KB（无 FK），文件清理状态不随级联丢失。查询 `GET /api/knowledge-base-deletion-tasks/{taskId}` 仅 owner。

## 5. 验证入口

| 模块 | 测试 | 门控 |
| --- | --- | --- |
| Skill 同步 | `BuiltinSkillRegistryTest`、`BuiltinSkillExecutionServiceTest`、`BuiltinSkillExecutorTest`、`HarnessedSkillKnowledgeToolExecutorTest`、`BuiltinSkillHttpExecutionIntegrationTest` | 默认跑 |
| Skill 异步 | `BuiltinSkillTaskServiceTest`、`BuiltinSkillTaskDispatchServiceTest`、`BuiltinSkillTaskWorkerTest` 等 | 默认跑；L2：`-Dg3.skill.http.postgres.l2=true`、`-Dg3.skill.task.persistence.l2=true`、`-Dg3.skill.task.rabbit.l2=true` |
| 工作流 | `WorkflowPlannerTest`、`WorkflowPlanVerifierTest`、`WorkflowPlanExecutorTest`、`WorkflowExecutionServiceTest`、`WorkflowControllerTest`、`WorkflowExecutionAuditRecorderTest` | 默认跑；`WorkflowExecutionAuditRuntimeL2Test` 需 `-Dg4.workflow.audit.l2=true` |
| KB 删除 | `deletion/` 5 个测试 | 默认跑 |

## 6. 关联文档

- Harness 复用（审批/代理）：[06-工具Harness](06-工具Harness.md)
- 检索与缓存：[08-RAG检索与排序](08-RAG检索与排序.md) §6
- Webhook 审批出站事件：[02-认证鉴权与异步邮件](02-认证鉴权与异步邮件.md) §5
- ReAct 循环的复杂任务边界：[03-Agent核心循环](03-Agent核心循环.md) §8
- 归档 Spec：[../archive/2026-11-10/spec/trusted-knowledge-agent-spec.md](../archive/2026-11-10/spec/trusted-knowledge-agent-spec.md)
