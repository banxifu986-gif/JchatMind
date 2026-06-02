# Query Rewrite改造与评测方案

## 1. 背景

- 当前 Query Rewrite 已经从 `RagServiceImpl` 中抽离，但职责仍偏轻，主要是 query 清洗、标题型判定和路径型 auto context selection。
- 结合现有 RAG 链路，真正影响召回质量的不是泛化同义改写，而是三件事：
  - 多轮追问时能否把低信息 query 补成可检索的 standalone query。
  - 已有上下文是否应该 `HARD` 约束，还是只作为 `SOFT` 信号参与排序。
  - 用户已经显式切换到新标题、新路径时，系统能否及时逃逸旧 context。
- 本轮默认走规则优先，不在生产链路新增 LLM 改写调用。

## 2. 问题定义

- 现状一：
  - 有 context 就容易被当成强约束使用。
  - 这对 follow-up 有帮助，但对 topic switch 有误伤风险。
- 现状二：
  - 低信息追问如“这个怎么回答”“这个流程是什么”在无状态检索下天然信息不足。
  - 这类 query 的正确解法不是泛化改写，而是基于 session 命中的 `sourceName + parentContentPath` 做规则化补全。
- 现状三：
  - 标题召回链路需要保守。
  - 如果把 rewritten query 直接喂给标题 exact/contains/BM25，容易把原本稳定的 `title_exact` 打散。

## 3. 现状链路

- `QueryRewriteServiceImpl`
  - 负责 query sanitize、意图识别、标题型判定、路径型 auto context selection。
- `RagServiceImpl`
  - 负责 query rewrite、向量召回、标题召回、候选合并、context filter、rerank。
- `KnowledgeTools`
  - 负责从 session 读取 `retrievalContext`，并在命中后回写新的 `retrievalContext`。
- 本轮边界：
  - 不改 `KnowledgeTools` 的 session 存储结构。
  - 不改数据库 schema。
  - 不新增依赖。
  - 不做兼容性双写。

## 4. 方案设计

### 4.1 设计目标

- 目标不是泛化同义改写。
- 本轮把 Query Rewrite 升级成“检索意图决策 + 上下文软硬约束 + follow-up 补全”。
- 验收原则是保守不回归：
  - 先守住现有 `title_exact` / `content_rewrite` 主指标。
  - 再新增量化证明多轮 follow-up 的收益。

### 4.2 Query Rewrite 规则升级

- `QueryRewriteResult` 扩充为明确的检索计划对象：
  - 保留 `query/context/titleQuery`
  - 新增 `intent`
  - 新增 `contextApplyMode`
  - 新增 `retrievalQueries`
- `intent` 分四类：
  - `FOLLOW_UP`
  - `NAVIGATION`
  - `FACTOID`
  - `ANALYTICAL`
- `contextApplyMode` 分三类：
  - `NONE`
  - `SOFT`
  - `HARD`

### 4.3 意图识别规则

- `NAVIGATION`
  - query 明显带路径信号，如 `>`、`/`、`\`、`.md`、`.markdown`
- `FOLLOW_UP`
  - query 信息量低，且当前 session 已有 context
  - 典型如“这个怎么回答”“这里怎么做”“继续说”
- `ANALYTICAL`
  - query 带明显分析类标记，如“原理”“设计”“区别”“思路”“流程”
- `FACTOID`
  - 其他默认归为普通事实型检索

### 4.4 Context 策略

- `HARD`
  - 只给高置信 follow-up
  - 路径型 query 且没有 topic switch 信号时也允许 `HARD`
- `SOFT`
  - context 存在，但 query 明显出现新标题、新路径、新来源线索
  - analytical query 默认也只做 `SOFT`
- `NONE`
  - 没有 context 时

规则重点：

- 不再“有 context 就直接硬绑定”。
- 当 query 明显出现新路径或新标题时，禁止沿用旧 context 做 `HARD` 约束。
- 路径型 query 仍允许 auto context selection，但纯普通问句不自动猜路径。

### 4.5 Follow-up 补全

- 只对低信息 follow-up 生成 standalone query。
- 规则化主查询拼接：
  - `sourceName + parentContentPath + 原始问句`
- 原始问句保留为副查询。
- 这样可以兼顾：
  - 借用上下文补信息
  - 保留原问句的原始检索语义

### 4.6 RAG 检索执行策略

- `RagServiceImpl` 按 `QueryRewriteResult` 执行检索：
  - 对 `retrievalQueries` 逐个做向量召回
  - 多 query 候选按 `chunkId` 去重
  - 合并时保留更优候选
- 标题召回链路保持保守：
  - `title exact / contains / keyword / trigram / BM25`
  - 只基于原始 query 跑一次
  - 不直接吃 rewritten query
- context 使用规则：
  - `HARD` context：前置过滤，再 rerank
  - `SOFT` context：不做前置过滤，只在 rerank 中加分

## 5. 接口变更

### 5.1 `QueryRewriteResult`

- 新增字段：
  - `Intent intent`
  - `ContextApplyMode contextApplyMode`
  - `List<String> retrievalQueries`

### 5.2 `QueryRewriteServiceImpl`

- 新增能力：
  - 规则化意图识别
  - context `NONE/SOFT/HARD` 判定
  - follow-up standalone query 构造
  - topic switch guard

### 5.3 `RagServiceImpl`

- 从“单 query 检索”改为“检索计划执行”
- 新增能力：
  - multi-query vector recall
  - 原 query 标题召回
  - `SOFT/HARD` context 区分消费
  - 多 query 候选去重合并

## 6. 评测方案

### 6.1 单测

- `QueryRewriteServiceImplTest`
  - 意图识别
  - `SOFT/HARD` 判定
  - follow-up standalone query 生成
  - topic switch 脱离旧 context
  - 路径型 query 自动补全
- `KnowledgeToolsTest`
  - 保持现有 session context 读写覆盖
  - 不改断言方向

### 6.2 离线评测

- 测试入口：
  - `backend_v2/src/test/java/com/kama/jchatmind/rag/RagRecallEvaluationTest.java`
- 保留现有分组：
  - `title_exact`
  - `content_rewrite`
  - `title_path`
  - 其他已有诊断组
- 新增分组：
  - `follow_up_contextual_rewrite`
  - `topic_switch_guard`
- 输出继续保留：
  - `Recall@1/3/5/10`
  - `MRR@3/10`
  - hit 分布
  - `missCaseIds`
  - 与旧 report 的 diff

### 6.3 线上 E2E

- `RagOnlineE2eEvaluationTest`
  - 保持无状态入口
  - 用于兜底检查单轮行为未回归
- `RagSessionOnlineE2eEvaluationTest`
  - 拆成两组：
    - `follow_up_low_info`
    - `topic_switch_after_context`
  - 同时输出 stateless/contextual 对比，量化 session context 的收益

## 7. 验收阈值

### 7.1 不回归红线

- `real/title_exact Recall@5` 相对基线下降不得超过 `0.02`
- `real/content_rewrite Recall@5` 相对基线下降不得超过 `0.02`
- 上述两组 `MRR@3` 相对基线下降不得超过 `0.03`

### 7.2 增量目标

- `follow_up_contextual_rewrite Recall@1 >= 0.85`
- `follow_up_contextual_rewrite MRR@3 >= 0.90`
- `topic_switch_guard Recall@3 >= 0.90`
- `RagSessionOnlineE2eEvaluationTest` 中：
  - contextual `Hit@Top3 >= 0.90`
  - contextual `Hit@1` 相比 stateless 至少提升 `0.30`

## 8. LLM 二阶段引入条件

- 本轮不引入 LLM 改写。
- 只有以下条件同时满足时，才进入第二阶段：
  - 规则版已上线并完成本轮基线复跑
  - 主指标未回归，但多轮 follow-up 指标仍明显不足
  - 剩余 miss 主要集中在代词、省略、抽象分析类问法
  - 规则模板已难继续提升
- 第二阶段约束：
  - 入口放在独立的 `QueryRewriteService` 分支能力中
  - 只对高置信 `FOLLOW_UP` 或 `ANALYTICAL` query 按开关启用
  - 不能默认全量开启

## 9. 实施步骤

1. 跑现有单测与评测，固化本轮前基线 report。
2. 实现 `QueryRewriteServiceImpl` / `RagServiceImpl` 改造。
3. 先跑单测，再跑离线评测，再跑线上 E2E，再跑 session E2E。
4. 将结果写入 `docs/RAG召回评测基线记录.md`。
5. 依据验收阈值给出采纳结论。

## 10. 当前实施说明

- 本轮默认采用规则优先。
- 生产链路只改：
  - `QueryRewriteServiceImpl`
  - `RagServiceImpl`
  - 对应测试
  - 评测文档
- `retrievalContext` 持久化结构保持不变。

## 11. 本轮实测结论

- 真实 KB 复跑已完成，知识库为 `34d6eabb-9823-434a-9966-bc9eaa103739`。
- 主链路结论：
  - `real/title_exact` 与 `real/content_rewrite` 均守住不回归红线
  - `real/title_exact Recall@5` 维持 `1.0000`
  - `real/content_rewrite Recall@5` 从 `0.9657` 提升到 `0.9755`
  - 说明本轮规则化改造没有打坏既有单轮 RAG 主链路，且正文式问法还有小幅增益
- 增量结论：
  - `follow_up_contextual_rewrite` 已跨过 `Recall@1` 目标线，结果为 `0.8599`
  - `follow_up_contextual_rewrite MRR@3=0.8736`，仍低于 `0.90`
  - `topic_switch_guard Recall@3=0.9945`，已达到目标线
  - Session E2E 的 contextual overall `Hit@Top3=1.0000`，已达到目标线
  - Session E2E 的 contextual `Hit@1` 仅比 stateless 提升 `+0.25`，仍低于 `+0.30`
- 本轮采纳建议：
  - 结论定为 `部分采纳（较上版前进一大步）`
  - 规则版可以保留，主链路、`topic switch guard`、session Top3 已具备采纳依据
  - 本轮已采纳的关键点包括：Markdown 跳级标题路径修复、编号问句标题进入 `titleQuery`、`topic switch` 路径分支脱锚
  - 下一轮优先继续压 `follow-up` 的 Top1/MRR 与 session `Hit@1` 增益，不建议现在直接上 LLM rewrite

## 12. 2026-05-23 最小 MVP 本地验证记录

### 12.1 本次改动

- `RagRecallEvaluationTest`
  - 将 `user_like_question` 模板改成更像真实用户问法，不再使用明显“面试里如果问到...”的模板腔
- `QueryRewriteServiceImpl`
  - 新增可开关 LLM rewrite 分支
  - 默认关闭：`rag.query-rewrite.llm.enabled=false`
  - 仅对高置信 `FOLLOW_UP` 和部分带 context 的 `ANALYTICAL` query 生效
  - LLM 失败时自动回退规则版 rewrite
- `QueryRewriteServiceImplTest`
  - 增补 LLM rewrite 命中、失败回退、导航场景禁用的单测

### 12.2 本地执行命令

```bash
cd backend_v2
mvn -q -DskipTests compile
mvn -q -Dtest=QueryRewriteServiceImplTest test
mvn -q -Dtest=RagSessionOnlineE2eEvaluationTest test
mvn -q -Dtest=RagRecallEvaluationTest test
```

### 12.3 执行结果

- `mvn -q -DskipTests compile`
  - 结果：`通过`
- `mvn -q -Dtest=QueryRewriteServiceImplTest test`
  - 结果：`通过`
  - 备注：日志中存在一条 `LLM query rewrite failed, fallback to rule-based rewrite` 的 `warn`
  - 说明：这是刻意保留的单测场景，用于验证 LLM 改写异常时会自动回退，不是失败
- `mvn -q -Dtest=RagSessionOnlineE2eEvaluationTest test`
  - 结果：`通过`
  - 说明：本次主要验证 Spring 上下文与 session-aware 检索入口未被改造打断
- `mvn -q -Dtest=RagRecallEvaluationTest test`
  - 结果：`通过`
  - 执行口径：默认 `fixture`

### 12.4 Fixture 指标快照

- `fixture overall`
  - total：`6`
  - evaluated：`6`
  - excluded：`0`
  - Recall@1/3/5/10：`1.0000 / 1.0000 / 1.0000 / 1.0000`
  - MRR@3/10：`1.0000 / 1.0000`
- `fixture/user_like_question`
  - total：`3`
  - evaluated：`3`
  - excluded：`0`
  - Recall@1/3/5/10：`1.0000 / 1.0000 / 1.0000 / 1.0000`
  - MRR@3/10：`1.0000 / 1.0000`
- `fixture/follow_up_contextual_rewrite`
  - Recall@1/3/5/10：`1.0000 / 1.0000 / 1.0000 / 1.0000`
- `fixture/topic_switch_guard`
  - Recall@1/3/5/10：`1.0000 / 1.0000 / 1.0000 / 1.0000`

### 12.5 结论

- 本次最小 MVP 改动没有打断编译、离线评测入口和 session E2E 入口
- `user_like_question` 模板改写后，fixture 口径未出现回归
- LLM rewrite 分支当前只具备“受控接入能力”，默认关闭，不改变默认主链路行为
- 下一步若要评估真实收益，应继续在真实 KB 上复跑：
  - `real/user_like_question`
  - `real/follow_up_contextual_rewrite`
  - `RagSessionOnlineE2eEvaluationTest`
