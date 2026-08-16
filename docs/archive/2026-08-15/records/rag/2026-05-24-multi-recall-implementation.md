# RAG 多路召回实施记录 - 2026-05-24

## 1. 本轮目标

- 按既定收口方案实现第一阶段 RAG 召回架构
- 不继续深挖 Query Rewrite
- 优先完成：
  - 内容侧 BM25
  - 全通道 RRF 融合
  - 自适应 rerank 的内容信号补强

## 2. 本轮实现

已完成：

- `backend_v2/src/main/java/com/kama/jchatmind/mapper/ChunkBgeM3Mapper.java`
  - 新增 `selectContentLexicalCandidatesByKbIds`
- `backend_v2/src/main/resources/mapper/ChunkBgeM3Mapper.xml`
  - 新增内容候选 SQL
- `backend_v2/src/main/java/com/kama/jchatmind/model/dto/RagRetrievalResult.java`
  - 新增 `rrfScore`
  - 新增 `vectorRank/vectorDistance`
  - 新增 `titleBm25Rank/titleBm25Score`
  - 新增 `contentBm25Rank/contentBm25Score`
- `backend_v2/src/main/java/com/kama/jchatmind/service/impl/RagServiceImpl.java`
  - 新增内容 BM25 通道
  - 标题 BM25 / 内容 BM25 收口为统一计算逻辑
  - 多通道检索统一切到 `rrfFuse`
  - rerank 增加：
    - `titleBm25Signal`
    - `contentBm25Signal`
    - `vectorSignal`

## 3. 本轮未做

- 未切到“数据库原生 BM25 排序”
  - 当前仍是“数据库拉候选 + Java 侧 BM25 打分”
  - 这是为了不新增 PG 中文扩展，也不改业务表 schema
- 未实现 `P3a/P3b`
  - cosine 距离归一化重构
  - embedding 缓存

## 4. 测试结果

执行结果：

- `mvn -q -DskipTests compile`
  - 通过
- `mvn -q -Dtest=QueryRewriteServiceImplTest test`
  - 通过
  - 有预期内 warn：`LLM query rewrite failed, fallback to rule-based rewrite`
- `mvn -q -Dtest=RagRecallEvaluationTest test`
  - 通过
  - fixture `Recall@1/3/5/10 = 1.0000`
  - fixture `MRR@3/10 = 1.0000`
- `mvn -q -Dtest=RagRecallEvaluationTest "-Drag.eval.enable-ab-comparison=true" test`
  - 通过
  - fixture A/B 无回归
  - `dominantLayer = mixed_or_close`
  - `queryExpansionMrrImpact = +0.0000`
  - `rerankMrrImpact = +0.0000`

## 5. 真实 KB 评测结论

命令：

```bash
mvn -q -Dtest=RagRecallEvaluationTest "-Drag.eval.mode=real" "-Drag.eval.real-kb-id=34d6eabb-9823-434a-9966-bc9eaa103739" "-Drag.eval.enable-ab-comparison=true" "-Drag.eval.ab-sample-size=20" "-Ddocument.storage.base-path=D:\coding\Java\project\JchatMind\backend_v2\data\documents" test
```

结果：

- 180 秒超时未完成
- 900 秒超时仍未完成
- 日志显示真实文档解析已开始并完成章节提取：
  - `336` sections
  - `28` sections

判断：

- 当前问题不是功能报错，而是“真实 KB 全量评测耗时过长”
- 这说明下一步更该收口评测入口，而不是继续在单个 follow-up 规则上微调

## 6. 批判性结论

- 第一阶段功能 gap 已基本补齐，主问题不再是“少一个召回点”
- 继续在 follow-up 局部 bonus 上深挖，收益会越来越差
- 后续更值得优先推进的是：
  - 真实 KB 评测耗时治理
  - `P3a/P3b`
  - 是否需要真正数据库原生 BM25，而不是继续堆 Java 侧词法补丁

## 7. 继续收口：评测提速与低风险性能补强

本轮继续做了两个宏观收口项：

- 真实 KB 评测增加限流配置
  - `rag.eval.real-max-documents`
  - `rag.eval.real-max-cases`
  - `rag.eval.real-document-order`
- `RagServiceImpl` 增加进程内 embedding LRU 缓存
  - 配置项：`rag.embedding.cache.max-entries`

目的不是继续微调某个 recall case，而是：

- 让真实 KB 能有一个“分钟级以内”的回归入口
- 降低评测/导入/多次重复检索时的 embedding 重算成本

快速验证结果：

```bash
mvn -q -Dtest=RagRecallEvaluationTest "-Drag.eval.mode=real" "-Drag.eval.real-kb-id=34d6eabb-9823-434a-9966-bc9eaa103739" "-Drag.eval.real-max-documents=1" "-Drag.eval.real-max-cases=40" "-Drag.eval.enable-ab-comparison=false" "-Ddocument.storage.base-path=D:\coding\Java\project\JchatMind\backend_v2\data\documents" test
```

结果：

- 在约 `40s` 内完成
- 说明真实 KB 回归入口已经从“不可控超时”变成“可采样、可快速执行”

默认配置已补充到：

- `backend_v2/src/test/resources/application-rag-eval.yaml`
  - `rag.embedding.cache.max-entries`
  - `rag.eval.real-max-documents`
  - `rag.eval.real-max-cases`
  - `rag.eval.real-document-order`

当前项目推进建议：

- 默认回归：
  - `QueryRewriteServiceImplTest`
  - fixture `RagRecallEvaluationTest`
  - real KB 小样本评测
- 只有在阶段性验收时，再跑真实 KB 更大样本或全量

## 8. P3a 最小版收口

本轮对 `P3a minimal` 做了最小试探，但最终决定不采纳，已回退：

- 试探内容：
  - 向量检索 SQL 临时从 `<->` 切到 pgvector cosine distance ` <=> `
  - rerank 向量信号临时改成 `cosineSimilarity = 1 - cosineDistance`
- 回退原因：
  - fixture 与真实 KB 小样本都能跑通，说明链路正确
  - 但真实 KB 小样本出现多维回退，尤其是 `follow_up_contextual`、`topic_switch_guard`、`title_recall`、`rerank_quality`
  - 这类“距离口径统一”的理论收益，目前不足以覆盖整体回归风险
  - 现阶段项目主矛盾已经不是向量口径不一致，而是回归入口收口、真实 KB 可持续评测、主链路稳定性

收口结论：

- `P3a` 本轮维持未采纳状态
- 主链路保留当前已验证版本：
  - 内容侧 BM25
  - 全通道 RRF 融合
  - rerank 内容信号补强
  - 真实 KB 小样本回归入口
  - embedding 进程内 LRU 缓存
- 后续若重新评估 `P3a`，前提应是：
  - 先有更稳定的真实 KB 诊断样本
  - 再单独验证距离度量，不与主链路收口混在一轮

## 9. 本轮验证

执行命令：

```bash
mvn -q -DskipTests compile
mvn -q -Dtest=RagRecallEvaluationTest test
mvn -q -Dtest=RagRecallEvaluationTest "-Drag.eval.mode=real" "-Drag.eval.real-kb-id=34d6eabb-9823-434a-9966-bc9eaa103739" "-Drag.eval.real-max-documents=1" "-Drag.eval.real-max-cases=40" "-Drag.eval.enable-ab-comparison=false" "-Ddocument.storage.base-path=D:\coding\Java\project\JchatMind\backend_v2\data\documents" test
```

验证结果：

- 编译通过
- fixture 回归通过
  - `fixture recall@1/3/5/10 = 1.0`
  - `fixture mrr@3/10 = 1.0`
- 真实 KB 小样本回归通过
  - `real/title_recall recall@1 = 0.9000`
  - `real/rerank_quality recall@1 = 0.9231`
  - `real/follow_up_contextual recall@1 = 0.7500`
  - `real/follow_up_contextual recall@3 = 0.8750`
  - `real/follow_up_contextual mrr@3 = 0.8125`

说明：

- 这轮目标是确认“回退 `P3a minimal` 后主链路仍可稳定回归”，不是重做大样本对比
- 当前默认回归入口仍以 fixture + 真实 KB 小样本为主，不恢复真实 KB 全量 A/B

## 10. 未采纳试探：RRF 共识信号直接注入 rerank

本轮还做过一个最小试探：

- 尝试把 `RRF` 融合后的共识强度直接注入 `rerank`
- 目标是让排序层更多利用“多路召回共同支持”的候选，而不是继续加 Query 模板或局部 bonus

结果：

- fixture 直接出现严重回归
  - `Recall@1` 从 `1.0000` 掉到 `0.0000`
  - `MRR@3` 从 `1.0000` 掉到 `0.3333`
- 真实 KB 小样本也出现严重回归
  - `real/title_exact Recall@1` 从 `1.0000` 掉到 `0.0000`
  - `real/content_rewrite Recall@5` 从 `1.0000` 掉到 `0.0000`
  - A/B 中甚至出现 `no_rerank` 反而显著优于主链路

判断：

- 当前 `RRF` 分数只能作为召回融合信号，不能未经校准直接进入排序层
- 这说明项目现阶段不适合再继续做 rerank 信号扩写试探
- 再往下做，大概率会回到“局部想法很多，但主链路不稳定”的状态

收口结论：

- 该试探已回退，不进入主链路
- 当前 RAG 功能优化阶段到此收口：
  - 保留内容侧 BM25
  - 保留全通道 RRF 融合
  - 保留当前已验证的 rerank 信号
  - 保留真实 KB 小样本回归入口
  - 暂停新的 rerank/距离度量试探
