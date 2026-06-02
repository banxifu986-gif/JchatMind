# RAG 召回评测基线记录

## 1. 记录目的

这份文档用于固化当前项目在真实知识库上的离线 RAG 召回基线，后续所有解析器、chunking、embedding 与检索策略优化，都以这份基线做前后对比。

## 2. 评测入口

- 测试类：`backend_v2/src/test/java/com/kama/jchatmind/rag/RagRecallEvaluationTest.java`
- 评测模式：`real`
- 结果来源：`backend_v2/target/rag-eval/report.json`
- 记录日期：`2026-05-11`

## 3. V1 基线结果

### 3.1 overall

- total：`384`
- evaluated：`340`
- excluded：`44`
- recall@1：`0.5058`
- recall@3：`0.6065`
- recall@5：`0.6244`

### 3.2 title_exact

- total：`192`
- evaluated：`177`
- excluded：`15`
- recall@1：`0.8644`
- recall@3：`0.9492`
- recall@5：`0.9605`

### 3.3 content_rewrite

- total：`192`
- evaluated：`163`
- excluded：`29`
- recall@1：`0.1472`
- recall@3：`0.2638`
- recall@5：`0.2883`

## 4. V1 结论

- 当前实现对标题精确问法的召回较强，`title_exact Recall@5` 已接近 `0.96`。
- 当前实现对内容改写问法的召回明显偏弱，`content_rewrite Recall@5` 仅 `0.2883`。
- 这说明当前系统更接近“标题相似检索”，还不是稳定的“正文语义召回”。
- `excluded` 仍有一定比例，后续需要继续关注 chunk 对齐、空内容与不可评测 case。

## 5. V2 优化结果

### 5.1 本次改动

- 改动点：将 chunk 的 embedding 输入从“仅标题”改为“标题 + 正文”
- 目标：提升真实问答场景下的正文语义召回能力，重点观察 `content_rewrite`

### 5.2 overall

- total：`384`
- evaluated：`339`
- excluded：`45`
- recall@1：`0.6822`
- recall@3：`0.8261`
- recall@5：`0.8680`

### 5.3 title_exact

- total：`192`
- evaluated：`176`
- excluded：`16`
- recall@1：`0.5852`
- recall@3：`0.7443`
- recall@5：`0.7727`

### 5.4 content_rewrite

- total：`192`
- evaluated：`163`
- excluded：`29`
- recall@1：`0.7791`
- recall@3：`0.9080`
- recall@5：`0.9632`

## 6. V2 相对 V1 的变化

### 6.1 overall

- recall@1：`0.5058 -> 0.6822`，提升 `+0.1764`
- recall@3：`0.6065 -> 0.8261`，提升 `+0.2196`
- recall@5：`0.6244 -> 0.8680`，提升 `+0.2436`

### 6.2 title_exact

- recall@1：`0.8644 -> 0.5852`，下降 `-0.2792`
- recall@3：`0.9492 -> 0.7443`，下降 `-0.2049`
- recall@5：`0.9605 -> 0.7727`，下降 `-0.1878`

### 6.3 content_rewrite

- recall@1：`0.1472 -> 0.7791`，提升 `+0.6319`
- recall@3：`0.2638 -> 0.9080`，提升 `+0.6442`
- recall@5：`0.2883 -> 0.9632`，提升 `+0.6749`

## 7. V2 结果解读

- 这次改动不是“所有维度都一起变好”，而是检索偏好发生了明显切换。
- V1 更偏标题相似检索，因此 `title_exact` 很强，但 `content_rewrite` 很弱。
- V2 引入正文后，系统明显转向正文语义召回，因此 `content_rewrite` 大幅提升。
- 同时，标题信号被正文内容稀释，导致 `title_exact` 有明显回落。
- 从聊天型 RAG 的真实使用场景看，V2 更接近自然提问检索，但也暴露出标题精确信号需要补回来的问题。

## 8. 下一步优化方向

- 保留正文语义召回优势，继续提升真实问答场景效果。
- 尝试补回标题精确匹配能力，例如：
  - `title + title + content`
  - 标题路径拼接
  - 标题/BM25 与向量混合召回
- 后续如继续优化，仍需基于同一批知识库与同一套 case 做前后对比。

## 9. 对比规则

- 后续优化必须尽量固定同一批知识库文档。
- 后续优化必须继续使用同一套 `RagRecallEvaluationTest` 评测逻辑。
- 优先比较 `content_rewrite Recall@1/3/5`，因为它更接近真实用户提问。
- 每次改动尽量只动一个核心变量，例如：
  - chunk 切分策略
  - embedding 文本构成
  - 检索排序策略

## 10. 注意事项

- 当前 `overall recall` 由测试代码按分组结果取平均得到，解读时应优先看 `title_exact` 与 `content_rewrite` 分项。
- `backend_v2/target/rag-eval/report.json` 会被后续运行覆盖，因此本文件承担“人工固化基线快照”的作用。

## 11. V2' 评测口径升级复跑结果

### 11.1 本次改动

- 记录日期：`2026-05-12`
- 改动点：仅升级 `RagRecallEvaluationTest` 的评测输出与 gold 对齐逻辑，不改变生产 RAG 检索链路。
- 评测侧检索深度从 top-5 扩展到 top-10，用于新增 `Recall@10` 与 `MRR@10`；`Recall@1/3/5` 仍保留，便于和 V2 做近似对比。
- gold 对齐从单一 chunk id 扩展为多候选 gold chunk，解决重复正文 chunk 导致的不可评测问题。
- 新增指标：`coverage`、`weightedRecall@1/3/5/10`、`MRR@3/10`、hit 分布、`excludedReasons`、`skippedDocumentReasons`、`missCaseIds`。

### 11.2 overall

- total：`384`
- evaluated：`355`
- excluded：`29`
- coverage：`0.9245`
- recall@1：`0.7021`
- recall@3：`0.8394`
- recall@5：`0.8800`
- recall@10：`0.9096`
- weightedRecall@1：`0.6958`
- weightedRecall@3：`0.8338`
- weightedRecall@5：`0.8732`
- weightedRecall@10：`0.9042`
- mrr@3：`0.7587`
- mrr@10：`0.7716`
- hit 分布：`hit@1=247`，`hit@3非hit@1=49`，`hit@5非hit@3=14`，`miss@5=45`
- excludedReasons：`empty_rewrite_query=29`
- skippedDocumentReasons：无

### 11.3 title_exact

- total：`192`
- evaluated：`192`
- excluded：`0`
- coverage：`1.0000`
- recall@1：`0.6250`
- recall@3：`0.7708`
- recall@5：`0.7969`
- recall@10：`0.8438`
- weightedRecall@1：`0.6250`
- weightedRecall@3：`0.7708`
- weightedRecall@5：`0.7969`
- weightedRecall@10：`0.8438`
- mrr@3：`0.6927`
- mrr@10：`0.7046`
- hit 分布：`hit@1=120`，`hit@3非hit@1=28`，`hit@5非hit@3=5`，`miss@5=39`

### 11.4 content_rewrite

- total：`192`
- evaluated：`163`
- excluded：`29`
- coverage：`0.8490`
- recall@1：`0.7791`
- recall@3：`0.9080`
- recall@5：`0.9632`
- recall@10：`0.9755`
- weightedRecall@1：`0.7791`
- weightedRecall@3：`0.9080`
- weightedRecall@5：`0.9632`
- weightedRecall@10：`0.9755`
- mrr@3：`0.8364`
- mrr@10：`0.8506`
- hit 分布：`hit@1=127`，`hit@3非hit@1=21`，`hit@5非hit@3=9`，`miss@5=6`
- excludedReasons：`empty_rewrite_query=29`

## 12. V2' 相对 V2 的变化

### 12.1 overall

- evaluated：`339 -> 355`，增加 `+16`
- excluded：`45 -> 29`，减少 `-16`
- recall@1：`0.6822 -> 0.7021`，变化 `+0.0199`
- recall@3：`0.8261 -> 0.8394`，变化 `+0.0133`
- recall@5：`0.8680 -> 0.8800`，变化 `+0.0120`

### 12.2 title_exact

- evaluated：`176 -> 192`，增加 `+16`
- excluded：`16 -> 0`，减少 `-16`
- recall@1：`0.5852 -> 0.6250`，变化 `+0.0398`
- recall@3：`0.7443 -> 0.7708`，变化 `+0.0265`
- recall@5：`0.7727 -> 0.7969`，变化 `+0.0242`

### 12.3 content_rewrite

- evaluated：`163 -> 163`，不变
- excluded：`29 -> 29`，不变
- recall@1：`0.7791 -> 0.7791`，基本不变
- recall@3：`0.9080 -> 0.9080`，基本不变
- recall@5：`0.9632 -> 0.9632`，基本不变

## 13. V2' 结果解读

- V2' 不是新的检索策略收益，而是“评测尺子升级后的 V2 复跑结果”。
- `content_rewrite` 与 V2 完全一致，说明评测升级没有改变正文语义召回的核心判断。
- `title_exact` 指标小幅上移，主要原因是多候选 gold chunk 解决了之前 16 条不可评测样本，`evaluated` 从 `176` 增至 `192`。
- 当前仍能看出 V2 的主要问题：`content_rewrite Recall@5=0.9632` 很强，但 `title_exact Recall@5=0.7969` 仍明显低于 V1 的 `0.9605`。
- 后续所有 V3+ 优化应以 V2' 作为新对照起点，优先比较 `title_exact`、`content_rewrite`、`MRR@3`、`weightedRecall@3/5` 和 hit 分布。

## 14. V3 标题加权 embedding 复跑结果

### 14.1 本次改动

- 记录日期：`2026-05-12`
- 改动点：生产上传侧与评测侧的 chunk embedding 输入从 `title + content` 调整为 `title + title + content`。
- 目标：在保留 V2 正文语义召回能力的前提下，补回标题精确检索能力。
- 新知识库：`b9904f1f-4d96-497d-80c6-17930b3f65a0`
- 新文档：`812944df-21e4-4498-b744-90e95c30ba73`
- 运行参数：`-Drag.eval.mode=real -Drag.eval.real-kb-id=b9904f1f-4d96-497d-80c6-17930b3f65a0 -Ddocument.storage.base-path=D:\coding\Java\project\JchatMind\backend_v2\data\documents`
- 样本说明：本次 Markdown 解析为 `193` 个章节，V2' 为 `192` 个章节；因此 `total` 从 `384` 变为 `386`。本次对比可作为同源文档重传后的近似收益判断，但不是完全同一批 chunk id 的严格 A/B。

### 14.2 overall

- total：`386`
- evaluated：`357`
- excluded：`29`
- coverage：`0.9249`
- recall@1：`0.7069`
- recall@3：`0.8744`
- recall@5：`0.9000`
- recall@10：`0.9416`
- weightedRecall@1：`0.7059`
- weightedRecall@3：`0.8711`
- weightedRecall@5：`0.8964`
- weightedRecall@10：`0.9384`
- mrr@3：`0.7787`
- mrr@10：`0.7905`
- hit 分布：`hit@1=252`，`hit@3非hit@1=59`，`hit@5非hit@3=9`，`miss@5=37`
- excludedReasons：`empty_rewrite_query=29`
- skippedDocumentReasons：无

### 14.3 title_exact

- total：`193`
- evaluated：`193`
- excluded：`0`
- coverage：`1.0000`
- recall@1：`0.6943`
- recall@3：`0.8342`
- recall@5：`0.8549`
- recall@10：`0.9016`
- weightedRecall@1：`0.6943`
- weightedRecall@3：`0.8342`
- weightedRecall@5：`0.8549`
- weightedRecall@10：`0.9016`
- mrr@3：`0.7547`
- mrr@10：`0.7665`
- hit 分布：`hit@1=134`，`hit@3非hit@1=27`，`hit@5非hit@3=4`，`miss@5=28`

### 14.4 content_rewrite

- total：`193`
- evaluated：`164`
- excluded：`29`
- coverage：`0.8497`
- recall@1：`0.7195`
- recall@3：`0.9146`
- recall@5：`0.9451`
- recall@10：`0.9817`
- weightedRecall@1：`0.7195`
- weightedRecall@3：`0.9146`
- weightedRecall@5：`0.9451`
- weightedRecall@10：`0.9817`
- mrr@3：`0.8069`
- mrr@10：`0.8187`
- hit 分布：`hit@1=118`，`hit@3非hit@1=32`，`hit@5非hit@3=5`，`miss@5=9`
- excludedReasons：`empty_rewrite_query=29`

## 15. V3 相对 V2' 的变化

### 15.1 核心指标变化

| 分组 | Recall@1 | Recall@3 | Recall@5 | Recall@10 | MRR@3 | MRR@10 |
| --- | --- | --- | --- | --- | --- | --- |
| overall | `0.7021 -> 0.7069`，`+0.0048` | `0.8394 -> 0.8744`，`+0.0350` | `0.8800 -> 0.9000`，`+0.0200` | `0.9096 -> 0.9416`，`+0.0320` | `0.7587 -> 0.7787`，`+0.0200` | `0.7716 -> 0.7905`，`+0.0188` |
| title_exact | `0.6250 -> 0.6943`，`+0.0693` | `0.7708 -> 0.8342`，`+0.0634` | `0.7969 -> 0.8549`，`+0.0580` | `0.8438 -> 0.9016`，`+0.0578` | `0.6927 -> 0.7547`，`+0.0620` | `0.7046 -> 0.7665`，`+0.0619` |
| content_rewrite | `0.7791 -> 0.7195`，`-0.0596` | `0.9080 -> 0.9146`，`+0.0067` | `0.9632 -> 0.9451`，`-0.0181` | `0.9755 -> 0.9817`，`+0.0062` | `0.8364 -> 0.8069`，`-0.0295` | `0.8506 -> 0.8187`，`-0.0319` |

### 15.2 hit 分布变化

- overall：`hit@1 247 -> 252`，增加 `+5`；`hit@3 296 -> 311`，增加 `+15`；`hit@5 310 -> 320`，增加 `+10`；`hit@10 321 -> 335`，增加 `+14`；`miss@5 45 -> 37`，减少 `-8`。
- title_exact：`hit@1 120 -> 134`，增加 `+14`；`hit@3 148 -> 161`，增加 `+13`；`hit@5 153 -> 165`，增加 `+12`；`hit@10 162 -> 174`，增加 `+12`；`miss@5 39 -> 28`，减少 `-11`。
- content_rewrite：`hit@1 127 -> 118`，减少 `-9`；`hit@3 148 -> 150`，增加 `+2`；`hit@5 157 -> 155`，减少 `-2`；`hit@10 159 -> 161`，增加 `+2`；`miss@5 6 -> 9`，增加 `+3`。

## 16. V3 结果解读

- V3 达成了主要目标之一：标题精确召回明显回升，`title_exact Recall@5` 提升 `+0.0580`，`MRR@3` 提升 `+0.0620`，`miss@5` 从 `39` 降到 `28`。
- V3 的副作用也明确存在：`content_rewrite Recall@1` 下降 `-0.0596`，`MRR@3` 下降 `-0.0295`，说明标题重复提高了标题信号权重，但会把部分自然改写问题的正确 chunk 往后挤。
- `content_rewrite Recall@10` 从 `0.9755` 提升到 `0.9817`，说明正确 chunk 多数仍在候选池内；当前问题更偏“排序位置下降”，不一定是完全召回不到。
- overall 指标整体上升，主要由 `title_exact` 大幅改善贡献；但因为真实 RAG 问答更依赖 `content_rewrite`，不能只看 overall 判定 V3 完全成功。
- 结论：V3 可记录为“候选策略/部分采纳”。如果业务更重标题式问题，收益明显；如果业务更重自然问答，应继续做下一轮排序优化，目标是在不牺牲 `content_rewrite MRR@3` 的前提下保留标题收益。

## 17. 下一步建议

- 不建议继续简单增加标题重复次数，例如 `title + title + title + content`，因为 V3 已经暴露出正文改写排序下降。
- 优先尝试 top-10 候选 rerank：保留 V3 的召回候选池，再用标题匹配、正文重叠或轻量规则对前 10 个 chunk 重排，目标是提升 `MRR@3`。
- 另一个方向是标题路径拼接：如果后续 parser 能输出父级 heading path，可把 embedding 输入从单个标题扩展为 `headingPath + title + content`，比机械重复标题更稳。
- 下一轮验收门槛建议：`title_exact Recall@5 >= 0.85`，`content_rewrite Recall@5 >= 0.95`，`content_rewrite MRR@3` 相对 V2' 下降不超过 `0.02`，并继续记录 `missCaseIds`。

## 18. V4 top-10 轻量 rerank 复跑结果

### 18.1 本次改动

- 记录日期：`2026-05-12`
- 改动点：`RagService.retrieve` 固定取至少 top-10 作为候选池，读取 chunk `metadata.title`，基于标题命中、正文包含和 query/chunk 词面重叠做轻量 rerank，再按调用方 `limit` 截断返回。
- embedding 输入保持 V3 的 `title + title + content`，本次不重新上传文档。
- 评测知识库：`b9904f1f-4d96-497d-80c6-17930b3f65a0`
- 评测文档：`812944df-21e4-4498-b744-90e95c30ba73`
- 验证命令：`mvn test "-Dtest=com.kama.jchatmind.rag.RagRecallEvaluationTest" "-Drag.eval.mode=real" "-Drag.eval.real-kb-id=b9904f1f-4d96-497d-80c6-17930b3f65a0" "-Ddocument.storage.base-path=D:\coding\Java\project\JchatMind\backend_v2\data\documents"`
- 验证结果：`BUILD SUCCESS`，`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`

### 18.2 overall

- total：`386`
- evaluated：`357`
- excluded：`29`
- coverage：`0.9249`
- recall@1：`0.8953`
- recall@3：`0.9355`
- recall@5：`0.9386`
- recall@10：`0.9416`
- weightedRecall@1：`0.8908`
- weightedRecall@3：`0.9328`
- weightedRecall@5：`0.9356`
- weightedRecall@10：`0.9384`
- mrr@3：`0.9104`
- mrr@10：`0.9115`
- hit 分布：`hit@1=318`，`hit@3非hit@1=15`，`hit@5非hit@3=1`，`miss@5=23`
- excludedReasons：`empty_rewrite_query=29`
- skippedDocumentReasons：无

### 18.3 title_exact

- total：`193`
- evaluated：`193`
- excluded：`0`
- coverage：`1.0000`
- recall@1：`0.8394`
- recall@3：`0.9016`
- recall@5：`0.9016`
- recall@10：`0.9016`
- weightedRecall@1：`0.8394`
- weightedRecall@3：`0.9016`
- weightedRecall@5：`0.9016`
- weightedRecall@10：`0.9016`
- mrr@3：`0.8687`
- mrr@10：`0.8687`
- hit 分布：`hit@1=162`，`hit@3非hit@1=12`，`hit@5非hit@3=0`，`miss@5=19`

### 18.4 content_rewrite

- total：`193`
- evaluated：`164`
- excluded：`29`
- coverage：`0.8497`
- recall@1：`0.9512`
- recall@3：`0.9695`
- recall@5：`0.9756`
- recall@10：`0.9817`
- weightedRecall@1：`0.9512`
- weightedRecall@3：`0.9695`
- weightedRecall@5：`0.9756`
- weightedRecall@10：`0.9817`
- mrr@3：`0.9593`
- mrr@10：`0.9617`
- hit 分布：`hit@1=156`，`hit@3非hit@1=3`，`hit@5非hit@3=1`，`miss@5=4`
- excludedReasons：`empty_rewrite_query=29`

## 19. V4 相对 V3 的变化

| 分组 | Recall@1 | Recall@3 | Recall@5 | Recall@10 | MRR@3 | MRR@10 |
| --- | --- | --- | --- | --- | --- | --- |
| overall | `0.7069 -> 0.8953`，`+0.1884` | `0.8744 -> 0.9355`，`+0.0611` | `0.9000 -> 0.9386`，`+0.0386` | `0.9416 -> 0.9416`，`+0.0000` | `0.7787 -> 0.9104`，`+0.1317` | `0.7905 -> 0.9115`，`+0.1210` |
| title_exact | `0.6943 -> 0.8394`，`+0.1451` | `0.8342 -> 0.9016`，`+0.0674` | `0.8549 -> 0.9016`，`+0.0466` | `0.9016 -> 0.9016`，`+0.0000` | `0.7547 -> 0.8687`，`+0.1140` | `0.7665 -> 0.8687`，`+0.1023` |
| content_rewrite | `0.7195 -> 0.9512`，`+0.2317` | `0.9146 -> 0.9695`，`+0.0549` | `0.9451 -> 0.9756`，`+0.0305` | `0.9817 -> 0.9817`，`+0.0000` | `0.8069 -> 0.9593`，`+0.1524` | `0.8187 -> 0.9617`，`+0.1430` |

## 20. V4 结果解读

- V4 的核心收益来自“候选池不变，排序前移”：`Recall@10` 没变，但 `Recall@1`、`MRR@3` 大幅提升，符合 top-10 rerank 的预期。
- `content_rewrite` 的 V3 副作用已被修复，`MRR@3` 从 `0.8069` 提升到 `0.9593`，并且超过 V2' 的 `0.8364`。
- `title_exact Recall@5` 从 V3 的 `0.8549` 继续提升到 `0.9016`，但仍未回到 V1 的 `0.9605`；说明标题类问题还可继续优化，但当前已明显优于 V2'。
- 本轮已达到上一轮设定的验收门槛：`title_exact Recall@5 >= 0.85`、`content_rewrite Recall@5 >= 0.95`、`content_rewrite MRR@3` 未下降且明显上升。
- 结论：V4 可以作为当前更优策略保留。后续优化重点应从“是否加入 rerank”转为“降低规则 rerank 对特殊 case 的误排风险”，例如输出 rerank 调试分、按 query 类型分桶评测、增加更多真实文档验证。

## 21. V4 多文档稳定性验证结果

### 21.1 本次验证说明

- 记录日期：`2026-05-12`
- 验证目标：验证 V4 在知识库由单文档扩展到多文档后是否仍稳定。
- 文档数量：`2`
- 解析章节数：文档 A `28` 个章节，文档 B `151` 个章节，总计 `179` 个章节。
- 评测性质：本次不是“同一文档前后 A/B”，而是“新验证集稳定性测试”，因此重点看不同 query 类型在知识库扩容后的退化点。

### 21.2 overall

- total：`358`
- evaluated：`275`
- excluded：`83`
- coverage：`0.7682`
- recall@1：`0.7861`
- recall@3：`0.7861`
- recall@5：`0.7913`
- recall@10：`0.7913`
- weightedRecall@1：`0.7309`
- weightedRecall@3：`0.7309`
- weightedRecall@5：`0.7345`
- weightedRecall@10：`0.7345`
- mrr@3：`0.7309`
- mrr@10：`0.7316`
- hit 分布：`hit@1=201`，`hit@3非hit@1=0`，`hit@5非hit@3=1`，`miss@5=73`
- excludedReasons：`empty_rewrite_query=83`

### 21.3 title_exact

- total：`179`
- evaluated：`179`
- excluded：`0`
- coverage：`1.0000`
- recall@1：`0.6034`
- recall@3：`0.6034`
- recall@5：`0.6034`
- recall@10：`0.6034`
- weightedRecall@1：`0.6034`
- weightedRecall@3：`0.6034`
- weightedRecall@5：`0.6034`
- weightedRecall@10：`0.6034`
- mrr@3：`0.6034`
- mrr@10：`0.6034`
- hit 分布：`hit@1=108`，`hit@3非hit@1=0`，`hit@5非hit@3=0`，`miss@5=71`

### 21.4 content_rewrite

- total：`179`
- evaluated：`96`
- excluded：`83`
- coverage：`0.5363`
- recall@1：`0.9688`
- recall@3：`0.9688`
- recall@5：`0.9792`
- recall@10：`0.9792`
- weightedRecall@1：`0.9688`
- weightedRecall@3：`0.9688`
- weightedRecall@5：`0.9792`
- weightedRecall@10：`0.9792`
- mrr@3：`0.9688`
- mrr@10：`0.9708`
- hit 分布：`hit@1=93`，`hit@3非hit@1=0`，`hit@5非hit@3=1`，`miss@5=2`
- excludedReasons：`empty_rewrite_query=83`

## 22. 多文档结果相对 V4 单文档基线的变化

| 分组 | Recall@1 | Recall@5 | Recall@10 | MRR@3 | 结论 |
| --- | --- | --- | --- | --- | --- |
| overall | `0.8953 -> 0.7861`，`-0.1092` | `0.9386 -> 0.7913`，`-0.1473` | `0.9416 -> 0.7913`，`-0.1504` | `0.9104 -> 0.7309`，`-0.1795` | 整体明显退化 |
| title_exact | `0.8394 -> 0.6034`，`-0.2360` | `0.9016 -> 0.6034`，`-0.2982` | `0.9016 -> 0.6034`，`-0.2982` | `0.8687 -> 0.6034`，`-0.2654` | 标题类大幅退化 |
| content_rewrite | `0.9512 -> 0.9688`，`+0.0175` | `0.9756 -> 0.9792`，`+0.0036` | `0.9817 -> 0.9792`，`-0.0025` | `0.9593 -> 0.9688`，`+0.0094` | 正文改写依然稳定 |

## 23. 多文档结果解读

- 本次最关键的结论不是“V4 失效”，而是“V4 对 content_rewrite 依然稳定，但对多文档 title_exact 不稳定”。
- `content_rewrite` 仍然很强，`Recall@5=0.9792`、`MRR@3=0.9688`，说明自然问答型 query 在多文档知识库下没有明显退化。
- 真正的问题集中在 `title_exact`：`Recall@1/3/5/10` 完全相同，`MRR@3` 也等于 `Recall@1`，这说明大量标题 query 不是“排位稍后”，而是“正确 chunk 根本没有进入 top-10 候选池”。
- `hit@3非hit@1=0`、`hit@5非hit@3=0` 也说明当前问题不是 rerank 排序问题，而是召回候选问题。V4 rerank 只能重排已有 top-10，无法补回未召回的 chunk。
- miss case 高度集中在同一份文档的 `title_exact` 上，且很多 query 的 top-10 几乎完全相同，说明知识库扩容后，embedding 检索把一批短标题/弱区分标题压到了同一组泛化 chunk 上。
- `excluded=83` 全部来自 `empty_rewrite_query`，说明这两份文档里有大量章节没有可生成的正文改写 query。因此本次 `content_rewrite` 样本数只有 `96`，解读时要区分“标题类稳定性”和“正文类稳定性”。

## 24. 下一步优化方向

- 下一步不要继续调 rerank 权重。因为这次 title 问题发生在 top-10 之前，继续调排序没有意义。
- 优先补一条标题召回旁路：对短 query 或标题型 query，增加 `metadata.title` 的精确匹配或 BM25/LIKE 召回，再与向量 top-k 合并。
- 如果不想先引入 BM25，最小实现可以是“标题精确命中优先”：
  查询先按 `metadata.title == query` 或高相似标题取若干候选，再和向量召回结果合并去重。
- 后续评测要拆成两层：
  `单文档收益验证` 与 `多文档稳定性验证` 分开记录，避免把不同测试集当成同一次优化的前后对比。

## 25. 标题精确命中旁路实验

### 25.1 本次改动

- 记录日期：`2026-05-12`
- 改动点：在向量召回前增加 `metadata.title` 精确命中候选，命中结果与向量 top-k 合并去重后再走现有 rerank。
- 目标：验证多文档场景下，`title_exact` 退化是否主要来自“完全相同标题没有进入候选池”。
- 验证知识库：`11b8554e-b02c-48f3-a1e9-a0320b51ab4e`

### 25.2 相对上一版多文档结果的变化

| 分组 | Recall@1 | Recall@3 | Recall@5 | Recall@10 | MRR@3 | MRR@10 |
| --- | --- | --- | --- | --- | --- | --- |
| overall | `0.7861 -> 0.7888`，`+0.0028` | `0.7861 -> 0.7944`，`+0.0084` | `0.7913 -> 0.8052`，`+0.0140` | `0.7913 -> 0.8052`，`+0.0140` | `0.7309 -> 0.7376`，`+0.0067` | `0.7316 -> 0.7399`，`+0.0083` |
| title_exact | `0.6034 -> 0.6089`，`+0.0056` | `0.6034 -> 0.6201`，`+0.0168` | `0.6034 -> 0.6313`，`+0.0279` | `0.6034 -> 0.6313`，`+0.0279` | `0.6034 -> 0.6136`，`+0.0102` | `0.6034 -> 0.6161`，`+0.0128` |
| content_rewrite | `0.9688 -> 0.9688`，`+0.0000` | `0.9688 -> 0.9688`，`+0.0000` | `0.9792 -> 0.9792`，`+0.0000` | `0.9792 -> 0.9792`，`+0.0000` | `0.9688 -> 0.9688`，`+0.0000` | `0.9708 -> 0.9708`，`+0.0000` |

### 25.3 结果解读

- 这次旁路实验是有效的，但收益有限。`title_exact Recall@5` 从 `0.6034` 提升到 `0.6313`，说明确实有一小部分 case 是“标题完全相等但未进入向量候选池”。
- `content_rewrite` 完全不受影响，这是好信号，说明标题旁路不会破坏正文问答能力。
- 但核心问题并没有被解决：`title_exact` 仍然很低，且大量 miss case 的 top-10 依然高度重复，说明主问题不是“精确标题没命中”，而是“相近标题/弱区分标题没有文本检索通道”。
- 结论：`title = query` 这种精确命中旁路可以保留，但它只能作为补丁，不能单独解决多文档标题召回问题。

## 26. 下一步建议

- 下一步应从“标题精确命中”升级为“标题文本召回”：
  优先做 `metadata.title` 的 `ILIKE` / trigram / BM25 候选召回，再与向量召回结果合并。
- 如果想保持最小改动，优先级建议是：
  `title contains query / query contains title` 候选召回 > trigram 相似度 > 独立 BM25。
- 继续调现有 rerank 权重的收益已经很低，因为问题发生在候选池生成阶段，不在排序阶段。

## 27. 结构化标题 contains 候选召回实验

### 27.1 本次改动

- 记录日期：`2026-05-12`
- 改动点：在标题精确命中之外，新增 `metadata.title contains query` 候选召回，并按标题长度接近度排序后与向量候选合并。
- 目标：验证多文档场景下，标题问题是否主要来自“完整标题不等值，但 query 是标题子串/核心短语”。
- 验证知识库：`11b8554e-b02c-48f3-a1e9-a0320b51ab4e`

### 27.2 相对上一版标题旁路实验的变化

| 分组 | Recall@1 | Recall@3 | Recall@5 | Recall@10 | MRR@3 | MRR@10 |
| --- | --- | --- | --- | --- | --- | --- |
| overall | `0.7888 -> 0.7888`，`+0.0000` | `0.7944 -> 0.7944`，`+0.0000` | `0.8052 -> 0.8052`，`+0.0000` | `0.8052 -> 0.8164`，`+0.0112` | `0.7376 -> 0.7376`，`+0.0000` | `0.7399 -> 0.7417`，`+0.0017` |
| title_exact | `0.6089 -> 0.6089`，`+0.0000` | `0.6201 -> 0.6201`，`+0.0000` | `0.6313 -> 0.6313`，`+0.0000` | `0.6313 -> 0.6536`，`+0.0223` | `0.6136 -> 0.6136`，`+0.0000` | `0.6161 -> 0.6188`，`+0.0027` |
| content_rewrite | `0.9688 -> 0.9688`，`+0.0000` | `0.9688 -> 0.9688`，`+0.0000` | `0.9792 -> 0.9792`，`+0.0000` | `0.9792 -> 0.9792`，`+0.0000` | `0.9688 -> 0.9688`，`+0.0000` | `0.9708 -> 0.9708`，`+0.0000` |

### 27.3 结果解读

- 这次 `title contains` 实验几乎没有改善 `title_exact` 的 top-5 表现，`Recall@5` 完全不变，说明“整句 contains”不是主要缺口。
- 它只把少量正确 chunk 推进到了 top-10，`title_exact Recall@10` 从 `0.6313` 提升到 `0.6536`，但没有进一步推进到 top-5。
- 这说明当前标题问题不是简单的“query 是标题子串”，而更可能是：
  标题之间语义相近、措辞有变体、或者需要按标题关键词拆分召回，而不是整句 contains。
- `content_rewrite` 依旧完全不受影响，说明结构化标题候选召回这条旁路本身是安全的。

## 28. 当前阶段结论

- V4 rerank 在单文档和正文改写场景已经成立，可以保留。
- 多文档退化问题仍然集中在 `title_exact` 候选池，而不是排序层。
- `title = query` 和 `title contains query` 两种最小旁路都只带来了有限收益，说明下一步需要更强的标题文本召回能力。

## 29. 下一步建议

- 不再继续堆 `LIKE` 规则。
- 下一步优先实现：
  `结构化标题字段 BM25 / trigram 相似召回`
  或
  `按标题关键词分词后的 OR 召回`
- 如果希望继续保持最小改动，我建议先做 `标题关键词 OR 召回 + 现有 rerank`，比直接上完整 BM25 成本更低，且更适合当前实验节奏。

## 30. 标题关键词 OR 召回实验

### 30.1 本次改动

- 记录日期：`2026-05-12`
- 改动点：在 `metadata.title` 精确命中、整句 contains 之外，新增按标题关键词拆分后的 OR 候选召回，并继续复用现有 rerank。
- 实现位置：
  - `backend_v2/src/main/java/com/kama/jchatmind/service/impl/RagServiceImpl.java`
  - `backend_v2/src/main/java/com/kama/jchatmind/mapper/ChunkBgeM3Mapper.java`
  - `backend_v2/src/main/resources/mapper/ChunkBgeM3Mapper.xml`
- 验证知识库：`11b8554e-b02c-48f3-a1e9-a0320b51ab4e`

### 30.2 相对上一版 title contains 实验的变化

| 分组 | Recall@1 | Recall@3 | Recall@5 | Recall@10 | MRR@3 | MRR@10 |
| --- | --- | --- | --- | --- | --- | --- |
| overall | `0.7888 -> 0.7888`，`+0.0000` | `0.7944 -> 0.7944`，`+0.0000` | `0.8052 -> 0.8052`，`+0.0000` | `0.8164 -> 0.8164`，`+0.0000` | `0.7376 -> 0.7376`，`+0.0000` | `0.7417 -> 0.7417`，`+0.0000` |
| title_exact | `0.6089 -> 0.6089`，`+0.0000` | `0.6201 -> 0.6201`，`+0.0000` | `0.6313 -> 0.6313`，`+0.0000` | `0.6536 -> 0.6536`，`+0.0000` | `0.6136 -> 0.6136`，`+0.0000` | `0.6188 -> 0.6188`，`+0.0000` |
| content_rewrite | `0.9688 -> 0.9688`，`+0.0000` | `0.9688 -> 0.9688`，`+0.0000` | `0.9792 -> 0.9792`，`+0.0000` | `0.9792 -> 0.9792`，`+0.0000` | `0.9688 -> 0.9688`，`+0.0000` | `0.9708 -> 0.9708`，`+0.0000` |

### 30.3 结果解读

- 这次“标题关键词 OR 召回”没有带来任何新增收益，说明问题已经不是“缺少简单关键词命中通道”。
- 现有多文档退化仍然稳定集中在 `title_exact`，而 `content_rewrite` 完全不受影响。
- 这进一步确认：当前主要瓶颈不在 rerank，也不在简单 title like 规则，而在更强的结构化标题字段候选生成能力。

### 30.4 当前结论

- `title = query`
- `title contains query`
- `title keywords OR`

以上三条最小旁路都已经验证过，收益有限或为零。

下一步应直接上更强的标题字段召回方案，优先顺序建议：

- `metadata.title` trigram similarity
- `metadata.title` BM25 / 全文检索
- 如果还想保持低改动，再考虑“字段级混合检索”，而不是继续堆规则

## 31. 标题 trigram 召回尝试状态

### 31.1 当前进展

- 记录日期：`2026-05-12`
- 已完成：
  - 在检索链路中接入 `metadata.title` 的 trigram 候选召回
  - 在初始化 SQL 中补充 `CREATE EXTENSION IF NOT EXISTS pg_trgm;`
  - 在初始化 SQL 中补充标题字段 trigram GIN 索引
- 已完成编译验证：
  - `mvn -DskipTests test-compile` 通过

### 31.2 当前阻塞

- 真实评测未能继续，原因不是 Java 代码报错，而是当前评测数据库中 `similarity(text, text)` 不存在。
- 这说明当前连接的 PostgreSQL 实例还没有启用 `pg_trgm` 扩展。

### 31.3 结论

- trigram 方案的代码已经就绪，但评测环境还不具备执行条件。
- 下一步不是继续调 Java 代码，而是先在当前评测库启用 `pg_trgm`，然后再复跑同一条评测命令。

## 32. 标题 trigram 召回复跑结果

### 32.1 本次改动

- 记录日期：`2026-05-13`
- 改动点：评测启动时自动执行 `CREATE EXTENSION IF NOT EXISTS pg_trgm`，并确保结构化标题字段 trigram 索引存在。
- 命名收敛：新增 `retrievableTitle` 作为通用可检索片段标题字段，旧数据继续兼容 `metadata.title`。
- 验证知识库：`11b8554e-b02c-48f3-a1e9-a0320b51ab4e`
- 验证命令：`mvn test "-Dtest=com.kama.jchatmind.rag.RagRecallEvaluationTest" "-Drag.eval.mode=real" "-Drag.eval.real-kb-id=11b8554e-b02c-48f3-a1e9-a0320b51ab4e" "-Ddocument.storage.base-path=D:\coding\Java\project\JchatMind\backend_v2\data\documents"`
- 验证结果：`BUILD SUCCESS`，`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`

### 32.2 指标结果

| 分组 | Recall@1 | Recall@3 | Recall@5 | Recall@10 | MRR@3 | MRR@10 |
| --- | --- | --- | --- | --- | --- | --- |
| overall | `0.7888` | `0.7944` | `0.8052` | `0.8164` | `0.7376` | `0.7417` |
| title_exact | `0.6089` | `0.6201` | `0.6313` | `0.6536` | `0.6136` | `0.6188` |
| content_rewrite | `0.9688` | `0.9688` | `0.9792` | `0.9792` | `0.9688` | `0.9708` |

### 32.3 相对标题关键词 OR 的变化

| 分组 | Recall@1 | Recall@3 | Recall@5 | Recall@10 | MRR@3 | MRR@10 |
| --- | --- | --- | --- | --- | --- | --- |
| overall | `0.7888 -> 0.7888`，`+0.0000` | `0.7944 -> 0.7944`，`+0.0000` | `0.8052 -> 0.8052`，`+0.0000` | `0.8164 -> 0.8164`，`+0.0000` | `0.7376 -> 0.7376`，`+0.0000` | `0.7417 -> 0.7417`，`+0.0000` |
| title_exact | `0.6089 -> 0.6089`，`+0.0000` | `0.6201 -> 0.6201`，`+0.0000` | `0.6313 -> 0.6313`，`+0.0000` | `0.6536 -> 0.6536`，`+0.0000` | `0.6136 -> 0.6136`，`+0.0000` | `0.6188 -> 0.6188`，`+0.0000` |
| content_rewrite | `0.9688 -> 0.9688`，`+0.0000` | `0.9688 -> 0.9688`，`+0.0000` | `0.9792 -> 0.9792`，`+0.0000` | `0.9792 -> 0.9792`，`+0.0000` | `0.9688 -> 0.9688`，`+0.0000` | `0.9708 -> 0.9708`，`+0.0000` |

### 32.4 结果解读

- `pg_trgm` 环境阻塞已解决，trigram 链路可以正常参与真实评测。
- 本轮指标相对标题关键词 OR 完全持平，说明在当前多文档验证集上，trigram 没有补回新的 `title_exact` miss case。
- `content_rewrite` 没有退化，说明结构化标题字段旁路仍然安全。
- 结论：trigram 代码和环境可保留，但它不是当前标题召回退化的有效解法。

## 33. 下一步建议

- 不继续堆叠标题 `LIKE`、关键词 OR 或 trigram 规则。
- 下一轮应进入 `metadata.retrievableTitle` 的 BM25 / 全文检索方案评估。
- 若继续保持 PostgreSQL 内实现，需单独确认中文分词策略；否则应评估是否引入独立全文检索组件。

## 34. contentPath 重建评测结果

### 34.1 本次改动

- 记录日期：`2026-05-13`
- 改动点：Markdown 解析结果新增 `contentPath`，chunk embedding 文本从 `title + title + content` 调整为 `contentPath + title + content`。
- 通用字段扩展：chunk metadata 新增 `contentPath`，继续兼容 `title` / `retrievableTitle`。
- 评测方式：新增 `-Drag.eval.rebuild-real-kb=true`，基于已有 Markdown 文件重建指定评测 KB 的 document/chunk 后再跑真实评测。
- 验证知识库：`11b8554e-b02c-48f3-a1e9-a0320b51ab4e`
- 验证命令：`mvn test "-Dtest=com.kama.jchatmind.rag.RagRecallEvaluationTest" "-Drag.eval.mode=real" "-Drag.eval.real-kb-id=11b8554e-b02c-48f3-a1e9-a0320b51ab4e" "-Drag.eval.rebuild-real-kb=true" "-Ddocument.storage.base-path=D:\coding\Java\project\JchatMind\backend_v2\data\documents"`
- 验证结果：`BUILD SUCCESS`，`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`

### 34.2 指标结果

| 分组 | Recall@1 | Recall@3 | Recall@5 | Recall@10 | MRR@3 | MRR@10 |
| --- | --- | --- | --- | --- | --- | --- |
| overall | `0.7941` | `0.7996` | `0.8104` | `0.8244` | `0.7412` | `0.7459` |
| title_exact | `0.6089` | `0.6201` | `0.6313` | `0.6592` | `0.6136` | `0.6197` |
| content_rewrite | `0.9792` | `0.9792` | `0.9896` | `0.9896` | `0.9792` | `0.9813` |

### 34.3 相对 trigram 复跑结果的变化

| 分组 | Recall@1 | Recall@3 | Recall@5 | Recall@10 | MRR@3 | MRR@10 |
| --- | --- | --- | --- | --- | --- | --- |
| overall | `0.7888 -> 0.7941`，`+0.0052` | `0.7944 -> 0.7996`，`+0.0052` | `0.8052 -> 0.8104`，`+0.0052` | `0.8164 -> 0.8244`，`+0.0080` | `0.7376 -> 0.7412`，`+0.0036` | `0.7417 -> 0.7459`，`+0.0042` |
| title_exact | `0.6089 -> 0.6089`，`+0.0000` | `0.6201 -> 0.6201`，`+0.0000` | `0.6313 -> 0.6313`，`+0.0000` | `0.6536 -> 0.6592`，`+0.0056` | `0.6136 -> 0.6136`，`+0.0000` | `0.6188 -> 0.6197`，`+0.0009` |
| content_rewrite | `0.9688 -> 0.9792`，`+0.0104` | `0.9688 -> 0.9792`，`+0.0104` | `0.9792 -> 0.9896`，`+0.0104` | `0.9792 -> 0.9896`，`+0.0104` | `0.9688 -> 0.9792`，`+0.0104` | `0.9708 -> 0.9813`，`+0.0104` |

### 34.4 结果解读

- `contentPath` 对多文档场景是有效增量，说明层级路径确实帮助 embedding 更好地区分同类标题和问答结构。
- 最大收益出现在 `content_rewrite`，`Recall@5` 与 `MRR@10` 都提升约 `+0.01`，说明正文语义召回更稳定了。
- `title_exact` 的 top-5 和 MRR 基本没动，只在 `Recall@10` 上有小幅改善，说明路径信息对“候选池补充”有一点帮助，但还不足以解决标题类主问题。
- 结论：`contentPath` 这条通用字段路线值得保留，并作为后续多格式检索的基础；但多文档标题类问题仍需继续上更强的标题字段召回。

## 35. 下一步建议

- 保留 `contentPath + title + content` 作为新的 chunk embedding 文本构成。
- 下一轮继续推进 `retrievableTitle` 的 BM25 / 全文检索召回，并与现有向量召回合并去重。
- `rag.eval.rebuild-real-kb=true` 仅用于评测重建，不建议当作生产链路入口。

## 36. 标题锚点召回最小实现结果

### 36.1 本次改动

- 记录日期：`2026-05-13`
- 生产链路改动：
  - `metadata.retrievableTitle` 精确命中作为标题锚点候选。
  - 标题精确候选从固定创建时间排序调整为按 query embedding 距离排序。
  - 标题精确候选上限从 `5` 提升到 `20`，避免同名标题过多时过早截断。
  - 保持 `retrievableTitle` 为叶子标题，不破坏现有 `metadata.title` 兼容。
- 评测口径改动：
  - `title_exact` 的 gold 从“当前 section 的内容精确 chunk”调整为“同文档同 `retrievableTitle` 的标题锚点候选”。
  - `content_rewrite` 仍沿用原来的内容级 gold，不改变正文问答召回判断。
- 验证知识库：`11b8554e-b02c-48f3-a1e9-a0320b51ab4e`
- 验证命令：`mvn test "-Dtest=com.kama.jchatmind.rag.RagRecallEvaluationTest" "-Drag.eval.mode=real" "-Drag.eval.real-kb-id=11b8554e-b02c-48f3-a1e9-a0320b51ab4e" "-Drag.eval.rebuild-real-kb=true" "-Ddocument.storage.base-path=D:\coding\Java\project\JchatMind\backend_v2\data\documents"`
- 验证结果：`BUILD SUCCESS`，`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`

### 36.2 指标结果

| 分组 | Recall@1 | Recall@3 | Recall@5 | Recall@10 | MRR@3 | MRR@10 |
| --- | --- | --- | --- | --- | --- | --- |
| overall | `0.9896` | `0.9896` | `0.9948` | `0.9948` | `0.9927` | `0.9935` |
| title_exact | `1.0000` | `1.0000` | `1.0000` | `1.0000` | `1.0000` | `1.0000` |
| content_rewrite | `0.9792` | `0.9792` | `0.9896` | `0.9896` | `0.9792` | `0.9813` |

### 36.3 结果解读

- 标题锚点召回本身已闭环：当 query 是叶子标题时，系统可以稳定召回同文档同标题锚点，`title_exact` 在锚点口径下达到 `1.0`。
- `content_rewrite` 没有退化，继续保持 `Recall@5=0.9896`、`MRR@10=0.9813`。
- 这次结果不能和旧版“内容级 title_exact”直接横向比较，因为旧口径要求在重复叶子标题中命中特定 section，而标题 query 本身无法表达具体 section。
- 早期尝试把 contains / keyword / trigram / BM25 全部放到向量候选前面会明显污染正文 query 排序；最终保留的第一版只让精确标题锚点优先，其他标题候选仍作为补充候选。

## 37. 下一步建议

- 标题锚点召回第一版可以保留。
- 后续如要解决“同名叶子标题下的具体 section 区分”，不要继续只靠 leaf title；需要引入 `contentPath` 或 `sourceName + contentPath + retrievableTitle` 作为更细粒度锚点 query。
- 下一轮建议做“标题锚点到内容 chunk 的二阶段映射”：先命中标题锚点，再根据 `contentPath`、正文摘要或相邻 chunk 上下文选择具体内容 chunk。

## 38. 标题锚点到内容 chunk 二阶段映射第一版

### 38.1 本次改动

- 记录日期：`2026-05-13`
- 生产链路改动：
  - chunk metadata 新增通用字段 `sourceType`、`sourceName`，继续保留 `title`、`retrievableTitle`、`contentPath`。
  - `retrievableTitleSearchText` 纳入 `sourceName`，为后续多文档、多格式标题检索保留来源维度。
  - rerank 增加 `contentPath/sourceName` 路径信号，但只在 query 明确包含路径分隔符 `>`、`/` 或 `\` 时启用，避免污染普通正文 query。
- 评测口径改动：
  - `overall` 仍只聚合 `title_exact + content_rewrite`，保持与上一版可比。
  - 新增诊断分组 `title_to_content`：query 为叶子标题，gold 为具体内容 chunk，用于验证“纯标题是否足以定位具体 section”。
  - 新增诊断分组 `title_path`：query 为 `contentPath`，gold 为具体内容 chunk，用于验证“带路径标题是否能定位具体 section”。
- 验证知识库：`11b8554e-b02c-48f3-a1e9-a0320b51ab4e`
- 验证命令：`mvn test "-Dtest=com.kama.jchatmind.rag.RagRecallEvaluationTest" "-Drag.eval.mode=real" "-Drag.eval.real-kb-id=11b8554e-b02c-48f3-a1e9-a0320b51ab4e" "-Drag.eval.rebuild-real-kb=true" "-Ddocument.storage.base-path=D:\coding\Java\project\JchatMind\backend_v2\data\documents"`
- 验证结果：`BUILD SUCCESS`，`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`

### 38.2 指标结果

| 分组 | Recall@1 | Recall@3 | Recall@5 | Recall@10 | MRR@3 | MRR@10 |
| --- | --- | --- | --- | --- | --- | --- |
| overall | `0.9896` | `0.9896` | `0.9948` | `0.9948` | `0.9927` | `0.9935` |
| title_exact | `1.0000` | `1.0000` | `1.0000` | `1.0000` | `1.0000` | `1.0000` |
| content_rewrite | `0.9792` | `0.9792` | `0.9896` | `0.9896` | `0.9792` | `0.9813` |
| title_to_content | `0.6089` | `0.6201` | `0.6313` | `0.6592` | `0.6136` | `0.6197` |
| title_path | `0.7430` | `1.0000` | `1.0000` | `1.0000` | `0.8715` | `0.8715` |

### 38.3 结果解读

- `overall` 与上一版持平，说明新增路径信号未污染主指标。
- `title_to_content` 仍停留在旧版内容级标题召回水平，证明纯 leaf title 无法稳定区分同名 section。
- `title_path Recall@3/5/10` 达到 `1.0`，说明当 query 携带层级路径时，系统可以稳定从标题锚点落到具体内容 chunk。
- 当前瓶颈从“能否召回标题锚点”转为“用户 query 是否携带足够的路径/上下文信息”。下一步不应继续堆 leaf title 规则，而应设计 query rewrite 或交互层，让标题类问题补入 `contentPath/sourceName`。

## 39. 结构化多 query 扩展实验

### 39.1 实验结论

- 记录日期：`2026-05-13`
- 实验策略：当 leaf title 命中多个标题锚点时，从候选 metadata 枚举 `sourceName + contentPath + retrievableTitle` 作为多条结构化 query，再合并召回结果。
- 验证知识库：`11b8554e-b02c-48f3-a1e9-a0320b51ab4e`
- 结果：`structured_multi_query` 与 `title_to_content` 指标持平，没有提升。

| 分组 | Recall@1 | Recall@3 | Recall@5 | Recall@10 | MRR@3 | MRR@10 |
| --- | --- | --- | --- | --- | --- | --- |
| title_to_content | `0.6089` | `0.6201` | `0.6313` | `0.6592` | `0.6136` | `0.6197` |
| structured_multi_query | `0.6089` | `0.6201` | `0.6313` | `0.6592` | `0.6136` | `0.6197` |
| title_path | `0.7430` | `1.0000` | `1.0000` | `1.0000` | `0.8715` | `0.8715` |

### 39.2 结果解读

- 枚举候选路径本身不能解决歧义：纯 leaf title 没有目标路径偏好，系统无法知道应该选择哪一个同名 section。
- 多 query 扩展还会引入多次 embedding 和多次检索成本；在没有收益前，不进入生产主链路。
- 当前应保留的方向是：让 query 在进入检索前获得真实上下文，例如用户选择文档/路径、会话上下文补全、或 LLM 在候选路径中做选择，而不是无约束枚举所有路径。

## 40. 上下文补全标题召回最小实现

### 40.1 本次改动

- 记录日期：`2026-05-13`
- 生产链路新增可选 `RagRetrievalContext`，字段使用通用语义：`sourceType`、`sourceName`、`contentPath`。
- `retrieve(kbId, query, limit)` 保持原行为；新增 `retrieve(kbId, query, context, limit)` 用于已知来源/路径上下文的检索。
- 带 context 时，向量候选和标题精确候选先按 `sourceName/sourceType/contentPath` 限定，再合并旧有候选并二次过滤。
- 评测新增诊断分组：
  - `source_scoped_title`：leaf title + 文档来源上下文。
  - `contextual_title_query`：leaf title + 文档来源 + 父级 `contentPath` 上下文。
- 验证知识库：`11b8554e-b02c-48f3-a1e9-a0320b51ab4e`
- 验证命令：`mvn test "-Dtest=com.kama.jchatmind.rag.RagRecallEvaluationTest" "-Drag.eval.mode=real" "-Drag.eval.real-kb-id=11b8554e-b02c-48f3-a1e9-a0320b51ab4e" "-Drag.eval.rebuild-real-kb=false" "-Ddocument.storage.base-path=D:\coding\Java\project\JchatMind\backend_v2\data\documents"`
- 验证结果：`BUILD SUCCESS`，`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`

### 40.2 指标结果

| 分组 | Recall@1 | Recall@3 | Recall@5 | Recall@10 | MRR@3 | MRR@10 |
| --- | --- | --- | --- | --- | --- | --- |
| overall | `0.9896` | `0.9896` | `0.9948` | `0.9948` | `0.9927` | `0.9935` |
| title_exact | `1.0000` | `1.0000` | `1.0000` | `1.0000` | `1.0000` | `1.0000` |
| content_rewrite | `0.9792` | `0.9792` | `0.9896` | `0.9896` | `0.9792` | `0.9813` |
| title_to_content | `0.6089` | `0.6201` | `0.6313` | `0.6592` | `0.6136` | `0.6197` |
| source_scoped_title | `0.6089` | `0.6201` | `0.6313` | `0.6592` | `0.6136` | `0.6197` |
| contextual_title_query | `0.9609` | `0.9777` | `0.9777` | `0.9888` | `0.9665` | `0.9681` |
| title_path | `0.7430` | `1.0000` | `1.0000` | `1.0000` | `0.8715` | `0.8715` |

### 40.3 结果解读

- 只限定 `sourceName/sourceType` 没有提升，说明当前主要歧义发生在同一文档内部的重复 leaf title。
- 增加父级 `contentPath` 后，`contextual_title_query Recall@5` 从 `0.6313` 提升到 `0.9777`，接近 `title_path` 的上限。
- `content_rewrite Recall@5=0.9896`、`MRR@10=0.9813`，未发生退化。
- 下一步重点不应再优化 leaf title 召回规则，而应进入“query 前置上下文补全/路径选择”：从会话、用户选择文档路径、或 LLM 路径候选选择中产生可靠的 `contentPath`。

## 41. Query 前置路径选择第一版

### 41.1 本次改动

- 记录日期：`2026-05-13`
- 生产链路新增内部 `RetrievalPlan`，在无显式 `RagRetrievalContext` 时尝试自动选择路径上下文。
- 自动路径选择只在 query 明确带路径线索时触发，例如包含 `>`、`/`、`\`、`.md`、`.markdown`，避免普通标题 query 和正文 query 额外扫路径候选。
- 路径选择候选来自 chunk metadata 的通用字段：`retrievableTitle`、`contentPath`、`sourceName`、`sourceType`。
- 候选打分使用 `title/path/sourceName` 词项重合，且要求最低分与第二名分差，避免无把握时强行选择。
- 评测新增 `auto_path_selection`：query 为 `父级 contentPath > leaf title`，不显式传 context，验证系统是否能从 query 中自动补全 `contentPath`。
- 验证知识库：`11b8554e-b02c-48f3-a1e9-a0320b51ab4e`
- 验证命令：`mvn test "-Dtest=com.kama.jchatmind.rag.RagRecallEvaluationTest" "-Drag.eval.mode=real" "-Drag.eval.real-kb-id=11b8554e-b02c-48f3-a1e9-a0320b51ab4e" "-Drag.eval.rebuild-real-kb=false" "-Ddocument.storage.base-path=D:\coding\Java\project\JchatMind\backend_v2\data\documents"`
- 验证结果：`BUILD SUCCESS`，`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`

### 41.2 指标结果

| 分组 | Recall@1 | Recall@3 | Recall@5 | Recall@10 | MRR@3 | MRR@10 |
| --- | --- | --- | --- | --- | --- | --- |
| overall | `0.9896` | `0.9896` | `0.9948` | `0.9948` | `0.9927` | `0.9935` |
| title_exact | `1.0000` | `1.0000` | `1.0000` | `1.0000` | `1.0000` | `1.0000` |
| content_rewrite | `0.9792` | `0.9792` | `0.9896` | `0.9896` | `0.9792` | `0.9813` |
| title_to_content | `0.6089` | `0.6201` | `0.6313` | `0.6592` | `0.6136` | `0.6197` |
| contextual_title_query | `0.9609` | `0.9777` | `0.9777` | `0.9888` | `0.9665` | `0.9681` |
| auto_path_selection | `0.7430` | `1.0000` | `1.0000` | `1.0000` | `0.8715` | `0.8715` |
| title_path | `0.7430` | `1.0000` | `1.0000` | `1.0000` | `0.8715` | `0.8715` |

### 41.3 结果解读

- `auto_path_selection` 达到 `title_path` 同等指标，说明当 query 携带明确路径线索时，系统可以在检索前自动形成有效 `contentPath` 上下文。
- 该策略不会解决纯 leaf title 歧义，`title_to_content Recall@5` 仍为 `0.6313`；这不是召回规则问题，而是 query 信息不足。
- 生产默认链路保持保守：无路径线索时不自动猜路径，避免误伤正文 query。
- 下一步如果要处理“纯标题但用户没有路径线索”的场景，需要引入交互或模型选择：让用户选候选路径，或让 LLM 在有限候选路径中做选择，而不是继续堆数据库召回规则。

## 42. 线上 RAG E2E 检索测试

### 42.1 测试定位

- 记录日期：`2026-05-13`
- 测试入口：`RagService.retrieve(kbId, query, limit)`，验证线上检索入口，而不是离线 gold benchmark。
- 测试类：`RagOnlineE2eEvaluationTest`
- 评测知识库：`11b8554e-b02c-48f3-a1e9-a0320b51ab4e`
- 测试样本：从真实 chunk metadata 自动构造 12 条用户风格 query，每类 4 条。
- TopK：`3`
- 报告输出：`backend_v2/target/rag-eval/online-e2e-report.json`
- 验证命令：`mvn.cmd test "-Dtest=com.kama.jchatmind.rag.RagOnlineE2eEvaluationTest" "-Drag.eval.real-kb-id=11b8554e-b02c-48f3-a1e9-a0320b51ab4e" "-Ddocument.storage.base-path=D:\coding\Java\project\JchatMind\backend_v2\data\documents"`
- 验证结果：`BUILD SUCCESS`，`Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`

### 42.2 指标结果

| 问法类型 | 样本数 | Hit@1 | Hit@Top3 |
| --- | --- | --- | --- |
| overall | `12` | `0.5833` | `0.9167` |
| path_aware | `4` | `0.5000` | `1.0000` |
| source_path | `4` | `0.5000` | `1.0000` |
| title_question | `4` | `0.7500` | `0.7500` |

### 42.3 结果解读

- `path_aware` 和 `source_path` 的 `Hit@Top3=1.0000`，说明带路径线索或文档名的用户问法可以稳定进入线上 Top3 候选。
- `Hit@1` 只有 `0.5833`，主要原因是部分 query 会把父级标题 chunk 排到目标子标题 chunk 前面；这对最终回答通常仍可用，但说明 Top1 排序仍有提升空间。
- `title_question` 有 1 条 miss：query 为 `回答 面试怎么回答`，该标题本身信息量过低，无法定位具体章节。这与离线评测中“纯 leaf title 无法消除同名 section 歧义”的结论一致。
- 线上测试补齐的是“用户问法 -> 检索入口 -> TopK 是否支撑回答”的链路；后续若继续优化，应优先补充用户上下文、候选路径选择或答案生成质量评估，而不是继续堆 leaf title 召回规则。
## 43. Query Rewrite 抽层与会话上下文链路接入
### 43.1 本轮目标

- 记录日期：`2026-05-13`
- 承接 40~42 节结论：当前 query rewrite 的重点不是继续做泛化同义改写，而是把用户已知的 `sourceName/contentPath` 上下文在检索前补齐并沿会话持续传递。
- 本轮目标是先完成生产链路最小闭环：`query rewrite -> retrieve(context) -> session metadata 持久化 -> 后续追问复用 context`。

### 43.2 本轮实现

- 新增 `QueryRewriteService` / `QueryRewriteServiceImpl` / `QueryRewriteResult`，把 query 预处理从 `RagServiceImpl` 中抽离。
- 所有 query 统一先做 `sanitize`，并保留或补全 `RagRetrievalContext`。
- 标题类候选旁路只在 `rewritten.isTitleQuery()` 为 `true` 时启用，避免普通正文问法被标题规则污染。
- 自动路径上下文补全逻辑迁移到 `QueryRewriteServiceImpl`，继续保持“只在 query 明确带路径线索时才自动猜路径”的保守策略。
- `ChatSessionDTO.MetaData` 新增 `retrievalContext`，`CreateChatSessionRequest`、`UpdateChatSessionRequest`、`ChatSessionVO` 与前端 `ui/src/api/api.ts` 已透传该字段。
- `ChatSessionFacadeService` / `ChatSessionFacadeServiceImpl` 新增 `getRetrievalContext` 与 `updateRetrievalContext`，用于按会话读写检索上下文。
- `KnowledgeTools` 改为优先读取 session `retrievalContext` 调用 `ragService.retrieve(kbId, query, context, 3)`，并将 top1 命中的 `sourceType/sourceName/parentContentPath` 回写到当前 session。
- `JChatMindFactory` 新增运行时 `bindRuntimeToolContext`，对 `KnowledgeTools` 按 `chatSessionId` 执行 `fork`，避免单例工具串会话上下文。

### 43.3 当前验证范围

- 已通过编译校验：`mvn -DskipTests test-compile`
- 已通过针对性单测：`mvn "-Dtest=com.kama.jchatmind.service.impl.QueryRewriteServiceImplTest,com.kama.jchatmind.agent.tools.KnowledgeToolsTest" test`
- `QueryRewriteServiceImplTest` 覆盖：
  - 显式 context 保留
  - 路径型 query 的自动父级 `contentPath` 选择
  - 自然语言正文问法不进入标题型 rewrite
- `KnowledgeToolsTest` 覆盖：
  - 从 session 读取 `retrievalContext`
  - 检索后按 top1 结果回写新的 `retrievalContext`
- `RagRecallEvaluationTest` 与 `RagOnlineE2eEvaluationTest` 本轮只补了 `QueryRewriteServiceImpl` 的测试装配，避免 Spring 测试配置因新依赖起不来。

### 43.4 本轮未复跑项

- 本轮尚未重新执行真实知识库上的 `RagRecallEvaluationTest`。
- 本轮尚未重新执行真实知识库上的 `RagOnlineE2eEvaluationTest`。
- 因此本节没有新增 Recall / MRR 指标，当前记录仅说明“链路已接通并完成编译 + 单测验证”，不代表真实召回基线已经更新。

### 43.5 当前结论

- 当前方向继续成立：query rewrite 应优先做“前置上下文补全”，而不是继续堆通用语义改写。
- 仅补 `sourceName/sourceType` 价值有限；真正关键的仍是可持续传递的父级 `contentPath`。
- 这轮完成后，用户在同一会话中的追问已经具备复用上一次命中文档路径上下文的基础链路。
- 下一步若要更新基线，应在真实 KB 上补跑评测，并重点观察：
  - `content_rewrite` 是否保持不退化
  - 路径型追问在真实会话里的收益
  - 是否需要新增 session-aware 的评测样本组
## 44. Query Rewrite + 会话上下文链路复跑结果
### 44.1 测试范围

- 记录日期：`2026-05-13`
- 评测知识库：`11b8554e-b02c-48f3-a1e9-a0320b51ab4e`
- 离线召回评测命令：
  - `mvn test "-Dtest=com.kama.jchatmind.rag.RagRecallEvaluationTest" "-Drag.eval.mode=real" "-Drag.eval.real-kb-id=11b8554e-b02c-48f3-a1e9-a0320b51ab4e" "-Drag.eval.rebuild-real-kb=false" "-Ddocument.storage.base-path=D:\coding\Java\project\JchatMind\backend_v2\data\documents"`
- 线上入口 E2E 命令：
  - `mvn test "-Dtest=com.kama.jchatmind.rag.RagOnlineE2eEvaluationTest" "-Drag.eval.real-kb-id=11b8554e-b02c-48f3-a1e9-a0320b51ab4e" "-Ddocument.storage.base-path=D:\coding\Java\project\JchatMind\backend_v2\data\documents"`
- 两项测试结果均为 `BUILD SUCCESS`

### 44.2 离线真实 KB 结果

- overall
  - total：`358`
  - evaluated：`275`
  - excluded：`83`
  - coverage：`0.7682`
  - recall@1：`0.9896`
  - recall@3：`0.9896`
  - recall@5：`0.9948`
  - recall@10：`0.9948`
  - mrr@3：`0.9927`
  - mrr@10：`0.9935`
- title_exact
  - total：`179`
  - evaluated：`179`
  - recall@1/3/5/10：`1.0000 / 1.0000 / 1.0000 / 1.0000`
  - mrr@3/10：`1.0000 / 1.0000`
- content_rewrite
  - total：`179`
  - evaluated：`96`
  - excluded：`83`
  - recall@1：`0.9792`
  - recall@3：`0.9792`
  - recall@5：`0.9896`
  - recall@10：`0.9896`
  - mrr@3：`0.9792`
  - mrr@10：`0.9813`
- 诊断分组
  - `title_to_content`：recall@5=`0.6313`，mrr@10=`0.6197`
  - `source_scoped_title`：recall@5=`0.6313`，mrr@10=`0.6197`
  - `contextual_title_query`：recall@1/3/5/10=`0.9609 / 0.9777 / 0.9777 / 0.9888`
  - `auto_path_selection`：recall@1/3/5/10=`0.7374 / 0.9888 / 1.0000 / 1.0000`
  - `title_path`：recall@1/3/5/10=`0.7374 / 0.9888 / 1.0000 / 1.0000`
- miss 情况
  - `content_rewrite` 仍只有 `1` 个 miss case
  - `excludedReasons` 仍为 `empty_rewrite_query=83`

### 44.3 线上入口 E2E 结果

- overall
  - total：`12`
  - hit@1：`0.5833`
  - hit@Top3：`0.9167`
- path_aware
  - total：`4`
  - hit@1：`0.5000`
  - hit@Top3：`1.0000`
- source_path
  - total：`4`
  - hit@1：`0.5000`
  - hit@Top3：`1.0000`
- title_question
  - total：`4`
  - hit@1：`0.7500`
  - hit@Top3：`0.7500`

### 44.4 结果分析

- 与 41 节、42 节上一版真实基线相比，本轮复跑后的四舍五入指标完全一致。
- 这说明本轮 `QueryRewriteService` 抽层、`RagServiceImpl` 接线、`ChatSession retrievalContext` 持久化和 `KnowledgeTools` 会话绑定，没有打坏原有“无上下文检索”链路。
- 当前离线评测与线上 E2E 入口本质上仍是无状态调用：
  - 离线评测走的是 `ragService.retrieve(kbId, query, limit)`
  - 线上 E2E 也没有经过 `KnowledgeTools -> session metadata -> follow-up query`
  - 所以这轮复跑验证到的是“无回归”，不是“会话上下文收益已经被现有基线证明”
- `source_scoped_title` 与 `title_to_content` 继续完全持平，说明只补 `sourceName/sourceType` 依然不能解决同文档内同名 leaf title 歧义。
- `contextual_title_query` 继续维持高位，`auto_path_selection` 与 `title_path` 继续对齐，说明“补父级 `contentPath`”这条方向仍然成立，而且本轮重构没有破坏它。
- 线上 E2E 仍然保留同一个典型 miss：
  - `title_question-4` 的 query 仍是 `回答 面试怎么回答`
  - 这类 query 本身没有路径和来源信息，现有无状态检索无法稳定定位
  - 这正是本轮会话上下文链路要解决的场景：上一轮已命中文档路径，下一轮追问只说“回答”时，应该复用 session `retrievalContext`

### 44.5 当前结论与下一步

- 本轮测试结论可以定性为：`Query Rewrite + session retrievalContext` 链路接入完成，且对现有 stateless RAG 基线零回归。
- 但当前文档还不能宣称“会话上下文让真实召回变好了”，因为现有评测没有覆盖多轮 follow-up 场景。
- 下一步建议新增一组 session-aware 评测：
  - 离线：先给一个 path-aware query 产生命中文档路径，再用同 session 下的 leaf title / `回答` 追问做第二跳评测
  - 线上：通过 `KnowledgeTools` 或 agent 入口模拟真实两轮对话，验证 `retrievalContext` 回写与复用后的 Hit@1 / Hit@Top3
## 45. 多轮会话上下文评测
### 45.1 测试目标

- 记录日期：`2026-05-14`
- 目标：验证本轮新增的 `session retrievalContext` 链路，是否能解决单轮无状态检索下无法定位的 follow-up query。
- 评测入口：新增 `RagSessionOnlineE2eEvaluationTest`
- 报告输出：`backend_v2/target/rag-eval/session-online-e2e-report.json`
- 验证命令：
  - `mvn test "-Dtest=com.kama.jchatmind.rag.RagSessionOnlineE2eEvaluationTest" "-Drag.eval.real-kb-id=11b8554e-b02c-48f3-a1e9-a0320b51ab4e" "-Ddocument.storage.base-path=D:\coding\Java\project\JchatMind\backend_v2\data\documents"`
- 验证结果：`BUILD SUCCESS`

### 45.2 测试设计

- 样本来源：真实知识库中 `retrievableTitle=回答` 的 chunk。
- 过滤条件：
  - 首轮 seed query 必须能在无状态检索下 `Hit@1`
  - 第二轮 follow-up query 固定为 `回答 面试怎么回答`
  - 第二轮无状态检索必须失败，才纳入样本
- 多轮链路：
  - 第一轮通过 `KnowledgeTools.knowledgeQuery` 命中目标 chunk
  - 工具把 top1 的 `sourceType/sourceName/parentContentPath` 回写到当前 `chatSession.metadata.retrievalContext`
  - 第二轮分别对比：
    - 无状态 `ragService.retrieve(kbId, followUpQuery, 3)`
    - 带 session context 的 `ragService.retrieve(kbId, followUpQuery, retrievalContext, 3)`

### 45.3 结果

- total：`2`
- stateless Hit@1：`0.0000`
- stateless Hit@Top3：`0.0000`
- contextual Hit@1：`1.0000`
- contextual Hit@Top3：`1.0000`

### 45.4 样本说明

- case 1
  - seed query：`通常不建议在每次重新发送验证码时直接清空失败计数`
  - follow-up query：`回答 面试怎么回答`
  - 目标路径：`三、验证码、Redis 与安全 > 16. 如果别人疯狂刷发送验证码接口，你怎么防刷？ > 20. 重新发送新验证码时，要不要清空失败计数？ > 回答`
  - 无状态结果：Top3 全部未命中目标
  - 带 session context：Top1 直接命中目标 chunk
- case 2
  - seed query：`我把日志脱敏放在 WebLogAspect 做，核心原因是：Controller`
  - follow-up query：`回答 面试怎么回答`
  - 目标路径：`五、异常处理与日志 > 47. 你为什么选择 @RestControllerAdvice + @ExceptionHandler 做全局异常处理？ > 55. 如果要完善日志脱敏，你会怎么设计？ > 回答`
  - 无状态结果：Top3 全部未命中目标
  - 带 session context：Top1 直接命中目标 chunk

### 45.5 结果解读

- 这组评测第一次直接证明了：`session retrievalContext` 对多轮追问场景有真实收益，而且收益不是轻微优化，而是从完全 miss 提升到稳定命中。
- 收益来源不是更强的通用 rewrite，而是第一轮命中后把 `parent contentPath` 持久化到了会话里。
- 第二轮 `回答 面试怎么回答` 这类极低信息量 query，本身不携带任何可区分路径；无状态检索天然无解，带上下文后才能精确收敛。
- 这与 44 节的结论互补：
  - 44 节证明本轮改动对既有 stateless 基线零回归
  - 45 节证明本轮新增的 session-aware 链路在真实多轮场景下有明确增益

### 45.6 当前结论

- Query rewrite 的下一阶段重点应该继续放在“会话上下文传递与复用”，而不是继续堆无状态 leaf title 规则。
- 当前最有价值的链路是：
  - 第一轮让用户或检索结果把文档/路径定位清楚
  - 后续追问通过 session `retrievalContext` 复用 `sourceName + parent contentPath`
  - 把低信息量 follow-up query 精确约束到正确 section
## 46. Query Rewrite 规则化升级与量化评测计划

### 46.1 本轮目标

- 记录日期：`2026-05-22`
- 本轮不引入 LLM rewrite，默认走规则优先。
- 改造目标不是泛化同义改写，而是把 Query Rewrite 升级成：
  - 检索意图决策
  - context `NONE/SOFT/HARD` 约束决策
  - 低信息 follow-up 的 standalone query 补全
- 主验收原则是保守不回归：
  - 先守住 `real/title_exact`
  - 再守住 `real/content_rewrite`
  - 同时新增 follow-up / topic switch 量化证明

### 46.2 本轮实施范围

- 生产代码：
  - `QueryRewriteResult`
  - `QueryRewriteServiceImpl`
  - `RagServiceImpl`
- 测试：
  - `QueryRewriteServiceImplTest`
  - `RagRecallEvaluationTest`
  - `RagSessionOnlineE2eEvaluationTest`
  - `KnowledgeToolsTest` 维持现有 session context 读写口径
- 文档：
  - 新增 `docs/Query Rewrite改造与评测方案.md`
  - 本文追加本轮计划与后续结果快照

### 46.3 本轮新增评测口径

- 离线新增诊断组：
  - `follow_up_contextual_rewrite`
  - `topic_switch_guard`
- Session E2E 分组：
  - `follow_up_low_info`
  - `topic_switch_after_context`
- Session E2E 报告增加：
  - `contextualGainAt1`
  - `contextualGainAtTopK`

### 46.4 验收红线

- `real/title_exact Recall@5` 相对上一版基线下降不得超过 `0.02`
- `real/content_rewrite Recall@5` 相对上一版基线下降不得超过 `0.02`
- 上述两组 `MRR@3` 相对上一版基线下降不得超过 `0.03`

### 46.5 增量目标

- `follow_up_contextual_rewrite Recall@1 >= 0.85`
- `follow_up_contextual_rewrite MRR@3 >= 0.90`
- `topic_switch_guard Recall@3 >= 0.90`
- `RagSessionOnlineE2eEvaluationTest`：
  - contextual `Hit@Top3 >= 0.90`
  - contextual `Hit@1` 相比 stateless 至少提升 `0.30`

### 46.6 当前状态

- 已完成：
  - Query Rewrite 规则化升级实现
  - Rag multi-query recall 与 `SOFT/HARD` context 消费改造
  - 离线评测分组扩展
  - session E2E 分组扩展
  - 方案文档落地
- 当前尚未写入本节的真实指标：
  - `RagRecallEvaluationTest` 真实 KB 复跑结果
  - `RagOnlineE2eEvaluationTest` 真实 KB 复跑结果
  - `RagSessionOnlineE2eEvaluationTest` 真实 KB 复跑结果
- 原因：
  - 需要本地可用的 `rag.eval.real-kb-id`
  - 仓库当前全量 `testCompile/test` 受无关 harness 测试污染，不能作为本轮可靠口径
- 因此本节先固化计划与阈值，待真实 KB 复跑后再补指标快照、对比表、miss 变化和采纳结论。

### 46.7 真实 KB 复跑结果

- 记录日期：`2026-05-22`
- 评测知识库：`11b8554e-b02c-48f3-a1e9-a0320b51ab4e`
- 离线召回命令：
  - `mvn test "-Dtest=com.kama.jchatmind.rag.RagRecallEvaluationTest" "-Drag.eval.mode=real" "-Drag.eval.real-kb-id=11b8554e-b02c-48f3-a1e9-a0320b51ab4e" "-Drag.eval.rebuild-real-kb=false" "-Ddocument.storage.base-path=D:\coding\Java\project\JchatMind\backend_v2\data\documents"`
- 无状态 E2E 命令：
  - `mvn test "-Dtest=com.kama.jchatmind.rag.RagOnlineE2eEvaluationTest" "-Drag.eval.real-kb-id=11b8554e-b02c-48f3-a1e9-a0320b51ab4e" "-Ddocument.storage.base-path=D:\coding\Java\project\JchatMind\backend_v2\data\documents"`
- Session E2E 命令：
  - `mvn test "-Dtest=com.kama.jchatmind.rag.RagSessionOnlineE2eEvaluationTest" "-Drag.eval.real-kb-id=11b8554e-b02c-48f3-a1e9-a0320b51ab4e" "-Ddocument.storage.base-path=D:\coding\Java\project\JchatMind\backend_v2\data\documents"`
- 三项测试结果均为 `BUILD SUCCESS`

#### 46.7.1 离线主指标

| 分组 | Recall@1 | Recall@3 | Recall@5 | Recall@10 | MRR@3 | MRR@10 |
| --- | --- | --- | --- | --- | --- | --- |
| `real/title_exact` | `1.0000` | `1.0000` | `1.0000` | `1.0000` | `1.0000` | `1.0000` |
| `real/content_rewrite` | `0.9792` | `0.9792` | `0.9896` | `0.9896` | `0.9792` | `0.9813` |

- overall：
  - total：`358`
  - evaluated：`275`
  - excluded：`83`
  - coverage：`0.7682`
  - Recall@1/3/5/10：`0.9896 / 0.9896 / 0.9948 / 0.9948`
  - MRR@3/10：`0.9927 / 0.9935`
- `content_rewrite` 仍只有 `1` 个 miss case：
  - `real/content_rewrite/9e3564a4-8b60-4ae5-b0fd-efac341f943d/142`
- `excludedReasons` 仍为：
  - `empty_rewrite_query=83`

#### 46.7.2 新增诊断组结果

| 分组 | Recall@1 | Recall@3 | Recall@5 | Recall@10 | MRR@3 | MRR@10 |
| --- | --- | --- | --- | --- | --- | --- |
| `real/follow_up_contextual_rewrite` | `0.8492` | `0.9385` | `0.9777` | `1.0000` | `0.8892` | `0.9015` |
| `real/topic_switch_guard` | `0.3503` | `0.6328` | `0.6328` | `0.6328` | `0.4859` | `0.4859` |

- 辅助分组仍保持原有水位：
  - `real/title_path Recall@1/3/5/10 = 0.7374 / 0.9888 / 1.0000 / 1.0000`
  - `real/auto_path_selection Recall@1/3/5/10 = 0.7374 / 0.9888 / 1.0000 / 1.0000`
  - `real/contextual_title_query Recall@1/3/5/10 = 0.9944 / 1.0000 / 1.0000 / 1.0000`

#### 46.7.3 无状态 E2E 结果

| 分组 | 样本数 | Hit@1 | Hit@Top3 |
| --- | --- | --- | --- |
| `overall` | `12` | `0.5833` | `0.9167` |
| `path_aware` | `4` | `0.5000` | `1.0000` |
| `source_path` | `4` | `0.5000` | `1.0000` |
| `title_question` | `4` | `0.7500` | `0.7500` |

#### 46.7.4 Session E2E 结果

| 分组 | 样本数 | Stateless Hit@1 | Stateless Hit@Top3 | Contextual Hit@1 | Contextual Hit@Top3 |
| --- | --- | --- | --- | --- | --- |
| `overall` | `4` | `0.2500` | `0.5000` | `0.2500` | `0.5000` |
| `follow_up_low_info` | `2` | `0.0000` | `0.0000` | `0.0000` | `0.5000` |
| `topic_switch_after_context` | `2` | `0.5000` | `1.0000` | `0.5000` | `0.5000` |

- contextual gain：
  - `contextualGainAt1 = 0.0000`
  - `contextualGainAtTop3 = 0.0000`

### 46.8 指标对比与验收结论

#### 46.8.1 不回归红线

- `real/title_exact Recall@5`
  - 本轮：`1.0000`
  - 结论：达标，且维持满分
- `real/content_rewrite Recall@5`
  - 本轮：`0.9896`
  - 结论：达标，未触发 `0.02` 回归红线
- `real/title_exact MRR@3`
  - 本轮：`1.0000`
  - 结论：达标
- `real/content_rewrite MRR@3`
  - 本轮：`0.9792`
  - 结论：达标，未触发 `0.03` 回归红线

#### 46.8.2 增量目标验收

| 指标 | 目标 | 本轮结果 | 结论 |
| --- | --- | --- | --- |
| `follow_up_contextual_rewrite Recall@1` | `>= 0.85` | `0.8492` | `未达标` |
| `follow_up_contextual_rewrite MRR@3` | `>= 0.90` | `0.8892` | `未达标` |
| `topic_switch_guard Recall@3` | `>= 0.90` | `0.6328` | `未达标` |
| session contextual `Hit@Top3` | `>= 0.90` | `0.5000` | `未达标` |
| session contextual `Hit@1` 相比 stateless 提升 | `>= 0.30` | `0.0000` | `未达标` |

#### 46.8.3 miss 变化与问题定位

- 主链路 miss 没有新增失控：
  - `content_rewrite` 仍只有历史上的单个 miss case
  - `title_exact` 继续稳定满分
- 新规则的主要短板集中在两类：
  - `follow_up_contextual_rewrite` 虽有明显收益，但仍会被父级 section chunk 压住，导致 Top1 与 MRR 不足
  - `topic_switch_guard` 明显不足，说明“query 中出现显式新标题/新路径时脱离旧 context”还不稳
- Session E2E 结果说明当前规则版还不能证明真实多轮链路已经达到上线目标：
  - `follow_up_low_info` 只把 `Hit@Top3` 从 `0.0000` 拉到 `0.5000`
  - `topic_switch_after_context` 反而从 `1.0000` 降到 `0.5000`，说明旧 context 仍可能把新主题拉偏

#### 46.8.4 本轮结论

- 结论标签：`部分采纳`
- 采纳部分：
  - 主链路可接受，`title_exact` 与 `content_rewrite` 均守住不回归红线
  - 规则化 follow-up 补全方向成立，离线 `follow_up_contextual_rewrite` 已接近目标线
- 暂不视为最终定稿的部分：
  - `topic_switch_guard` 未达标
  - Session E2E 未达标
  - 当前还不能证明“带 context 的真实多轮检索链路”已经稳定优于 stateless
- 下一轮优先级建议：
  - 优先修 `topic switch` 脱锚逻辑，而不是直接引入 LLM rewrite
  - 继续优化 session-aware rerank，让显式新标题/新路径优先覆盖旧 context
  - 对 `follow_up_low_info` 场景补更强的父路径与 leaf section 区分策略，再决定是否进入 LLM 二阶段

## 47. 新知识文档改造前基线记录

### 47.1 记录目的

- 记录日期：`2026-05-22`
- 本节用于冻结“两份新增知识文档”在 `Query Rewrite` 规则化升级前的基线结果。
- 为避免当前工作树中的改造代码污染基线，本节结果全部取自 `.baseline_head` 工作树下的现成评测产物。
- 本节作为“新文档改造前”对照组，后续分块 / rerank / rewrite 调整后，只与本节同批文档结果做对比。

### 47.2 基线代码与数据来源

- 基线代码工作树：`.baseline_head`
- 基线提交：`96e7966`
- 导入报告：`.baseline_head/backend_v2/target/rag-eval/import-report.json`
- 离线召回报告：`.baseline_head/backend_v2/target/rag-eval/report.json`
- 无状态 E2E 报告：`.baseline_head/backend_v2/target/rag-eval/online-e2e-report.json`
- Session E2E 报告：`.baseline_head/backend_v2/target/rag-eval/session-online-e2e-report.json`

### 47.3 本轮导入文档

- 知识库 ID：`3758c679-f263-49cb-9739-fe3df14e78a0`
- 知识库名称：`RAG Eval New Docs KB`
- 文档 1：`SQL调优与SQL八股梳理.md`
  - chunk 数：`28`
- 文档 2：`项目八股.md`
  - chunk 数：`336`
- 合计 chunk 数：`364`

### 47.4 改造前离线基线

#### 47.4.1 主指标

| 分组 | total | evaluated | excluded | Recall@1 | Recall@3 | Recall@5 | Recall@10 | MRR@3 | MRR@10 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `real` | `728` | `568` | `160` | `0.9804` | `0.9804` | `0.9828` | `0.9828` | `0.9859` | `0.9863` |
| `real/title_exact` | `364` | `364` | `0` | `1.0000` | `1.0000` | `1.0000` | `1.0000` | `1.0000` | `1.0000` |
| `real/content_rewrite` | `364` | `204` | `160` | `0.9608` | `0.9608` | `0.9657` | `0.9657` | `0.9608` | `0.9618` |

- `excludedReasons`：`empty_rewrite_query=160`
- `content_rewrite` miss case 数：`7`
- 7 个 miss 全部来自：`项目八股.md`

#### 47.4.2 诊断指标

| 分组 | Recall@1 | Recall@3 | Recall@5 | Recall@10 | MRR@3 | MRR@10 |
| --- | --- | --- | --- | --- | --- | --- |
| `real/contextual_title_query` | `0.9808` | `0.9835` | `0.9835` | `0.9918` | `0.9817` | `0.9827` |
| `real/auto_path_selection` | `0.6813` | `0.9725` | `0.9863` | `0.9890` | `0.8233` | `0.8272` |
| `real/title_path` | `0.6813` | `0.9725` | `0.9863` | `0.9890` | `0.8233` | `0.8272` |

### 47.5 改造前线上 E2E 基线

#### 47.5.1 无状态 E2E

| 分组 | 样本数 | Hit@1 | Hit@Top3 |
| --- | --- | --- | --- |
| `overall` | `12` | `0.6667` | `0.8333` |
| `path_aware` | `4` | `0.7500` | `1.0000` |
| `source_path` | `4` | `0.7500` | `1.0000` |
| `title_question` | `4` | `0.5000` | `0.5000` |

#### 47.5.2 Session E2E

| 分组 | 样本数 | Stateless Hit@1 | Stateless Hit@Top3 | Contextual Hit@1 | Contextual Hit@Top3 |
| --- | --- | --- | --- | --- | --- |
| `overall` | `2` | `0.0000` | `0.0000` | `1.0000` | `1.0000` |
| `session_follow_up` | `2` | `0.0000` | `0.0000` | `1.0000` | `1.0000` |

### 47.6 改造前瓶颈快照

- `title_exact` 已经满分，说明“显式标题定位”不是这批新文档的主问题。
- `content_rewrite` 仍有 `7` 个 miss，说明正文式问法进入向量召回后仍存在定位偏差。
- `title_question` 只有 `0.5000 / 0.5000`，暴露了“回答 / 原理 / 总结”这类泛化 leaf 标题冲突问题。
- `auto_path_selection` 与 `title_path` 的 `Recall@1` 只有 `0.6813`，说明路径型 query 虽能在 Top3 内基本找回，但 Top1 仍容易被父级 section 或近邻同名标题抢位。
- session follow-up 在这批文档上的旧基线反而很强：
  - 无上下文 `Hit@Top3 = 0.0000`
  - 带上下文 `Hit@Top3 = 1.0000`
  - 说明“上一轮先命中文档路径，下一轮追问只说回答”这类场景，本身具备明确上下文收益

### 47.7 使用说明

- 本节是“新增知识文档 + 改造前代码”的冻结结果。
- 后续若调整 Markdown 分块、chunk metadata、rerank 或 query rewrite：
  - 主对比口径优先看 `real/title_exact`、`real/content_rewrite`
  - 线上对比优先看 `title_question` 与 session follow-up
- `.baseline_head` 这一节不包含当前规则化升级后的 `follow_up_contextual_rewrite`、`topic_switch_guard` 新分组，因此这两组以后只在“改造后代码”口径下横向比较。

### 47.8 改造后结果（V4）

#### 47.8.1 代码与报告口径

- 记录日期：`2026-05-22`
- 改造后知识库 ID：`34d6eabb-9823-434a-9966-bc9eaa103739`
- 改造后知识库名称：`RAG Eval New Docs V3`
- 离线召回报告：`backend_v2/target/rag-eval/report.json`
- 无状态 E2E 报告：`backend_v2/target/rag-eval/online-e2e-report.json`
- Session E2E 报告：`backend_v2/target/rag-eval/session-online-e2e-report.json`
- 本轮已采纳的实现点：
  - Markdown 跳级标题路径修复
  - 编号问句标题进入 `titleQuery`
  - `topic switch` 路径分支脱锚规则

#### 47.8.2 主指标对比

| 分组 | 基线 Recall@1/3/5/10 | 改造后 Recall@1/3/5/10 | 基线 MRR@3 | 改造后 MRR@3 | 结论 |
| --- | --- | --- | --- | --- | --- |
| `real/title_exact` | `1.0000 / 1.0000 / 1.0000 / 1.0000` | `1.0000 / 1.0000 / 1.0000 / 1.0000` | `1.0000` | `1.0000` | 无回归 |
| `real/content_rewrite` | `0.9608 / 0.9608 / 0.9657 / 0.9657` | `0.9706 / 0.9706 / 0.9755 / 0.9755` | `0.9608` | `0.9706` | `Recall@5 +0.0098`，`MRR@3 +0.0098` |
| `real/overall` | `0.9804 / 0.9804 / 0.9828 / 0.9828` | `0.9853 / 0.9853 / 0.9877 / 0.9877` | `0.9859` | `0.9894` | 整体提升 |

#### 47.8.3 新增诊断组结果

| 分组 | Recall@1 | Recall@3 | Recall@5 | Recall@10 | MRR@3 | MRR@10 |
| --- | --- | --- | --- | --- | --- | --- |
| `real/user_like_question` | `0.5934` | `0.9945` | `0.9973` | `0.9973` | `0.7921` | `0.7928` |
| `real/follow_up_contextual_rewrite` | `0.8599` | `0.8929` | `0.9176` | `0.9423` | `0.8736` | `0.8842` |
| `real/topic_switch_guard` | `0.5635` | `0.9945` | `0.9945` | `0.9945` | `0.7776` | `0.7860` |

- 结果解读：
  - `real/user_like_question` 是本轮新增的实验性分组，query 不再直接使用标题或正文摘句，而是按更像真实用户问法的模板构造，例如：
    - `面试里如果问到 X，应该怎么回答`
    - `X 的核心原理是什么`
    - `X 的整体流程是什么`
  - 这组结果表现为 `Top3/Top5` 很强，但 `Top1/MRR` 明显弱于 `content_rewrite`，说明当前链路对口语化问法的主要瓶颈更偏排序而不是召回缺失
  - `follow_up_contextual_rewrite Recall@1` 已越过 `0.85` 门槛，但 `MRR@3` 仍未到 `0.90`
  - `topic_switch_guard Recall@3` 已从旧结果 `0.6328` 拉升到 `0.9945`，说明显式新标题 / 新路径 query 的脱离旧 context 已基本稳定

#### 47.8.4 线上 E2E 对比

无状态 E2E：

| 分组 | 基线 Hit@1 | 改造后 Hit@1 | 基线 Hit@Top3 | 改造后 Hit@Top3 |
| --- | --- | --- | --- | --- |
| `overall` | `0.6667` | `0.7500` | `0.8333` | `1.0000` |
| `path_aware` | `0.7500` | `0.5000` | `1.0000` | `1.0000` |
| `source_path` | `0.7500` | `0.7500` | `1.0000` | `1.0000` |
| `title_question` | `0.5000` | `1.0000` | `0.5000` | `1.0000` |

Session E2E：

| 分组 | Stateless Hit@1 | Stateless Hit@Top3 | Contextual Hit@1 | Contextual Hit@Top3 | 结论 |
| --- | --- | --- | --- | --- | --- |
| `overall` | `0.2500` | `0.5000` | `0.5000` | `1.0000` | Top3 达标，Hit@1 增益 `+0.25` |
| `follow_up_low_info` | `0.0000` | `0.0000` | `0.5000` | `1.0000` | 上下文收益明确 |
| `topic_switch_after_context` | `0.5000` | `1.0000` | `0.5000` | `1.0000` | 已不再被旧 context 拉偏 |

- contextual gain：
  - `contextualGainAt1 = 0.2500`
  - `contextualGainAtTop3 = 0.5000`

#### 47.8.5 验收判定

不回归红线：

| 指标 | 阈值 | 改造后结果 | 结论 |
| --- | --- | --- | --- |
| `real/title_exact Recall@5` | 相对基线下降不得超过 `0.02` | `1.0000 -> 1.0000` | 通过 |
| `real/content_rewrite Recall@5` | 相对基线下降不得超过 `0.02` | `0.9657 -> 0.9755` | 通过 |
| `real/title_exact MRR@3` | 相对基线下降不得超过 `0.03` | `1.0000 -> 1.0000` | 通过 |
| `real/content_rewrite MRR@3` | 相对基线下降不得超过 `0.03` | `0.9608 -> 0.9706` | 通过 |

增量目标：

| 指标 | 目标 | 改造后结果 | 结论 |
| --- | --- | --- | --- |
| `follow_up_contextual_rewrite Recall@1` | `>= 0.85` | `0.8599` | 通过 |
| `follow_up_contextual_rewrite MRR@3` | `>= 0.90` | `0.8736` | 未达标 |
| `topic_switch_guard Recall@3` | `>= 0.90` | `0.9945` | 通过 |
| session contextual `Hit@Top3` | `>= 0.90` | `1.0000` | 通过 |
| session contextual `Hit@1` 相比 stateless 提升 | `>= 0.30` | `0.2500` | 未达标 |

#### 47.8.6 miss 变化与结论

- 主链路结论：
  - `title_exact` 继续满分，无回归
  - `content_rewrite` 的 `Recall@5` 与 `MRR@3` 均较基线提升
- 更真实问法的补充观察：
  - `real/user_like_question Recall@3/5 = 0.9945 / 0.9973`，说明真实口语化 query 基本能稳定进入候选池
  - 但 `Recall@1 = 0.5934`、`MRR@3 = 0.7921`，显著低于 `content_rewrite`，当前更像是 Top1 排序问题
- 本轮最关键的变化：
  - `topic_switch_guard` 已从未达标变为通过，旧 context 对显式新路径 / 新标题 query 的干扰已基本消除
  - `title_question` 在线上无状态 E2E 中从 `0.5000 / 0.5000` 提升到 `1.0000 / 1.0000`
- 本轮仍未完全达标的点：
  - `follow_up_contextual_rewrite MRR@3 = 0.8736`，Top1 / Top2 仍会被父级 section chunk 抢位
  - Session `Hit@1` 增益只有 `+0.25`，离目标 `+0.30` 还差一点
- 结论标签：`部分采纳（较上版前进一大步）`
- 采纳建议：
  - 当前版本可作为规则版升级结果保留
  - 主链路、`topic switch guard`、session Top3 已具备采纳依据
  - 下一轮若继续优化，应集中打 `follow-up Top1/MRR` 与 session `Hit@1` 增益，不建议此时直接跳到全量 LLM rewrite

### 47.9 2026-05-24 RAG 多路召回 P1 收口实现

本轮目标不是继续深挖 Query Rewrite，而是把 RAG 主链路的召回架构收口到第一阶段可用版本：

- 新增内容侧 BM25 通道
- 用统一 RRF 融合替代顺序式 `mergeCandidates`
- 在 rerank 中补入内容 BM25 / 向量信号
- `P3a/P3b` 继续后置，不在本轮实现

本轮实际落地：

- `ChunkBgeM3Mapper` / `ChunkBgeM3Mapper.xml`
  - 新增 `selectContentLexicalCandidatesByKbIds`
- `RagRetrievalResult`
  - 新增 `rrfScore`
  - 新增 `vectorRank/vectorDistance`
  - 新增 `titleBm25Rank/titleBm25Score`
  - 新增 `contentBm25Rank/contentBm25Score`
- `RagServiceImpl`
  - 新增内容 BM25 候选计算
  - 标题 BM25 与内容 BM25 收口为统一 `findBm25Candidates`
  - 多通道候选改为统一 `rrfFuse`
  - rerank 新增三类轻量信号：
    - `titleBm25Signal`
    - `contentBm25Signal`
    - `vectorSignal`

测试与评测：

- `mvn -q -DskipTests compile`
  - 通过
- `mvn -q -Dtest=QueryRewriteServiceImplTest test`
  - 通过
  - 仍会输出预期内的 `LLM query rewrite failed, fallback to rule-based rewrite`
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

真实 KB 评测结论：

- 命令：
  - `mvn -q -Dtest=RagRecallEvaluationTest "-Drag.eval.mode=real" "-Drag.eval.real-kb-id=34d6eabb-9823-434a-9966-bc9eaa103739" "-Drag.eval.enable-ab-comparison=true" "-Drag.eval.ab-sample-size=20" "-Ddocument.storage.base-path=D:\coding\Java\project\JchatMind\backend_v2\data\documents" test`
- 现象：
  - 在当前环境下，`15` 分钟仍未跑完，被外部超时终止
  - 日志显示已完成真实文档解析（`336 + 28` sections），但评测阶段未在时限内结束
- 判断：
  - 这不是本轮主链路代码编译/启动失败
  - 当前瓶颈已经从“是否能跑”转为“真实 KB 全量评测耗时是否可接受”
  - 后续需要单独收口真实 KB 评测入口，而不是继续在功能逻辑里做局部打补丁

本轮结论：

- 第一阶段召回架构已落地：内容 BM25 + RRF + 自适应 rerank 已进入主链路
- 主链路 baseline 未见回归
- 最大剩余 gap 已从“缺功能”转为“两件事”
  - 真实 KB 全量评测耗时过长
  - `P3a/P3b` 仍未实现
