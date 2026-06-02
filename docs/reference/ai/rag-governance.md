# RAG 优化收口与推进规范

## 1. 目的

这份文档用于收口当前项目中与 RAG 相关的优化工作，避免后续继续沿着单个功能点反复深入，却无法回答下面三个更关键的问题：

- 当前收益到底来自哪一层：query rewrite、context 策略、候选召回，还是 rerank？
- 当前问题到底属于召回问题、排序问题，还是评测口径问题？
- 当前是否已经到了值得默认引入 LLM 的阶段？

本规范的目标不是继续堆优化点，而是把 RAG 优化从“经验驱动”推进到“可归因、可复现、可验收”。

## 2. 当前判断

### 2.1 已具备的能力

- 主链路已经完整：`query rewrite -> recall -> context filter -> rerank`
- 离线评测已经具备基础指标：
  - `Recall@1/3/5/10`
  - `MRR@3/10`
  - `coverage`
  - `excludedReasons`
  - `missCaseIds`
- 多轮场景已有 session-aware 评测入口：
  - `RagSessionOnlineE2eEvaluationTest`

### 2.2 当前主要风险

- 优化点越来越多，但收益归因不够清楚。
- 部分指标已经受评测口径变化影响，不能只看单个分数做乐观判断。
- `follow_up_contextual_rewrite` 当前更像排序问题，不宜继续把主要精力压在 Query 模板细化上。
- LLM rewrite 已经具备受控接入能力，但默认开启的证据仍不足。

## 3. 收口原则

### 3.1 不再优先做的事

- 不继续围绕单个 Query 模板做细碎打磨。
- 不继续靠增加 marker、扩充自然语言表述来替代评测收口。
- 不在没有对照试验的情况下继续新增 RAG 优化分支。

### 3.2 必须优先做的事

- 为核心优化层建立 A/B 对照评测。
- 将“总体变好”拆成“哪一层贡献了收益”。
- 将离线检索指标与多轮在线行为指标统一到同一套收口口径。

## 4. 统一评测口径

### 4.1 主口径

主口径优先看以下分组：

- `title_exact`
- `content_rewrite`

这两组继续作为主链路是否回归的红线指标。

### 4.2 诊断口径

以下分组用于定位问题来源，不直接替代主口径：

- `user_like_question`
- `follow_up_contextual_rewrite`
- `topic_switch_guard`
- `title_path`
- `contextual_title_query`

### 4.3 维度口径

离线评测需要继续按维度聚合：

- `title_recall`
- `query_rewrite`
- `rerank_quality`
- `follow_up_contextual`

维度聚合的作用不是展示更多指标，而是回答“问题更偏哪一层”。

## 5. A/B 对照规范

### 5.1 必做对照

后续所有重要优化，至少要能回答以下两个问题：

1. 关闭 query expansion 后，指标下降多少？
2. 关闭 rerank 后，指标下降多少？

### 5.2 推荐变体

- `full_chain`
  - 默认完整链路
- `no_query_expansion`
  - 只保留原始 query，不消费额外 `retrievalQueries`
- `no_rerank`
  - 保留候选召回与合并，关闭 rerank

### 5.3 解释规则

- `Recall@10` 基本不变，但 `Recall@1/MRR@3` 下滑：
  - 说明问题更偏排序层
- `Recall@5/10` 明显下滑：
  - 说明问题更偏召回层或 query 信息补全层
- `follow_up_contextual_rewrite` 下降明显，但 `title_exact` 不变：
  - 说明收益主要来自上下文补全，而不是标题召回

## 6. 验收标准

### 6.1 主链路不回归

- `real/title_exact Recall@5` 相对当前基线下降不得超过 `0.02`
- `real/content_rewrite Recall@5` 相对当前基线下降不得超过 `0.02`
- 上述两组 `MRR@3` 相对当前基线下降不得超过 `0.03`

### 6.2 多轮能力目标

- `follow_up_contextual_rewrite Recall@1 >= 0.85`
- `follow_up_contextual_rewrite MRR@3 >= 0.90`
- `RagSessionOnlineE2eEvaluationTest` 中 contextual `Hit@Top3 >= 0.90`
- `RagSessionOnlineE2eEvaluationTest` 中 contextual `Hit@1` 相对 stateless 至少提升 `0.30`

### 6.3 归因要求

每一轮优化在结论里都必须明确回答：

- 主收益来自 query expansion、context 策略，还是 rerank
- 当前瓶颈更偏 recall 还是 rerank
- 是否值得继续投入规则优化
- 是否已经具备引入 LLM 默认开启的证据

## 7. LLM 引入时机

### 7.1 当前结论

当前不建议把 LLM rewrite 作为默认主链路。

原因：

- 规则版链路仍有继续压缩排序问题的空间
- 当前缺少稳定的 A/B 结果证明 LLM 对真实 follow-up Top1 有持续净收益
- 默认引入 LLM 会提高延迟、复杂度和回退面

### 7.2 允许继续保留的形态

LLM 当前适合作为：

- 默认关闭
- 可配置开启
- 失败自动回退
- 仅作用于高置信 `FOLLOW_UP` 或部分 `ANALYTICAL` query

### 7.3 允许升级为默认能力的前提

只有在以下条件同时满足时，才考虑默认开启：

- 规则版链路已经通过 A/B 证明接近平台期
- `follow_up_contextual_rewrite` 的剩余问题主要集中在代词、省略、抽象追问
- LLM A/B 对比在真实知识库下稳定提升 `Recall@1` 或 `MRR@3`
- 线上延迟、失败回退率、误改写率可接受

## 8. 推进顺序

### 第一阶段：评测收口

- 固化主口径与诊断口径
- 增加 `full_chain / no_query_expansion / no_rerank` 对照
- 输出可归因结论

### 第二阶段：继续压主要瓶颈

- 如果 A/B 结果表明问题偏排序：
  - 优先继续压 rerank
- 如果 A/B 结果表明问题偏 query 信息不足：
  - 优先继续压 follow-up 补全和 context 策略

### 第三阶段：再决定是否放大 LLM

- 只有在规则链路收益明显见顶时，才继续扩大 LLM rewrite 作用范围

## 9. 本轮最小推进结果

本轮先推进以下最小能力：

- 新增 RAG 优化收口规范文档
- 离线评测增加最小 A/B 对照能力：
  - `full_chain`
  - `no_query_expansion`
  - `no_rerank`
- 默认不改变生产主链路行为，仅服务评测归因

这一步的价值不在于继续提高某个单点分数，而在于回答后续优化该往哪里投。

## 10. 2026-05-24 补充判断

基于真实 KB 小样本 A/B 归因复跑：

- 执行口径：
  - `real-max-documents=1`
  - `real-max-cases=40`
  - `enable-ab-comparison=true`
  - `ab-sample-size=20`
- 结果：
  - `dominantLayer = rerank_dominant`
  - `queryExpansionMrrImpact = +0.0000`
  - `rerankMrrImpact = +0.2000`

这意味着：

- 当前项目在收口阶段的首要矛盾仍是排序层，不是 query expansion 层
- 继续围绕 Query 模板、follow-up 表达式或 LLM rewrite 开关做细碎优化，优先级应继续后置
- 若下一轮还要动主链路，应优先回答“怎样让已有候选更稳定排到 Top1”，而不是“怎样再生成更多 query”

因此，本规范补充一条推进约束：

- 在新的 A/B 结果推翻之前，默认将 `rerank` 视为下一轮唯一优先优化层
- `query rewrite` 与 `LLM rewrite` 只做受控保留，不作为下一轮主攻面
