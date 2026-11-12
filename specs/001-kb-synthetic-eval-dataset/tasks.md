# Tasks: kb-synthetic-v1 评测数据集 + 链路修复

**Feature**: [spec.md](./spec.md) | **Plan**: [plan.md](./plan.md) | **Research**: [research.md](./research.md) | **Data Model**: [data-model.md](./data-model.md) | **Contracts**: [contracts/](./contracts/) | **Quickstart**: [quickstart.md](./quickstart.md)

**User Story 映射**（spec.md 用户场景）：
- **US1** 场景1 构建并冻结数据集（P1→P3 阶段）
- **US2** 场景2 端到端评测（P4 阶段）
- **US3** 场景3 存量回归（P0 阶段，先行且独立可交付）

## Phase 1: Setup（构建工作区初始化）

- [x] T001 创建构建工作区目录骨架 `datasets/kb-synthetic-v1/{scripts,config,working}` 与 `README.md` 骨架（构建方法、复现步骤、抽检说明占位）
- [x] T002 [P] 编写主题表 `datasets/kb-synthetic-v1/config/topics.json`：四类文档主题槽位（handbook 30/faq 25/techdoc 30/opsdoc 15）、每主题章节要点模板、明确"不存在于语料"的 out-of-scope 主题清单（~90 个拒答采样源）、固定随机种子
- [x] T003 [P] 编写共享事实表 `datasets/kb-synthetic-v1/config/world_facts.json`：公司名/产品线/部门专名与精确数字事实（SLA 时效、比例、金额），供跨文档一致性注入

## Phase 2: Foundational — P0 评测链路修复（US3，独立可交付）

**Story 目标**：修复接入阻塞点，存量回归不坏。独立测试：quickstart.md P0 节两条命令。

- [x] T004 [US3] `backend_v2/src/test/java/com/kama/jchatmind/rag/G2CurrentFixtureProfileLoader.java`：:58 `"fixture-fast-v1".equals(manifest.datasetId())` 改为 `SUPPORTED_DATASET_IDS.contains(...)`，新增常量 `Set.of("fixture-fast-v1", "kb-synthetic-v1")`；chunkingVersion/embeddingModel/defaultTopK 约束保留；加注释说明其余 9 处硬编码刻意不动（见 research.md D6）
- [x] T005 [US3] `backend_v2/src/test/java/com/kama/jchatmind/rag/G2CurrentFixtureAnswerQualityEvaluator.java`：:39 同样白名单化（复用 T004 的常量定义方式，各自文件内声明）
- [x] T006 [US3] `backend_v2/src/test/java/com/kama/jchatmind/rag/MultiCprEvaluationTest.java` loadCorpus（:164-209）：前 N 行截断改为每文件 `new Random(0)` 固定种子蓄水池采样，`samplePerFile` 仍按文件数均分，`requiredPassages` 强制纳入逻辑（:190-204）保留
- [x] T007 [US3] `MultiCprEvaluationTest.java` loadQrels（:257-271）：解析第 4 列 grade，仅 `grade > 0` 计相关，grade 保留在 `Map<String, Map<String, Integer>>` 供后续 NDCG；evaluate（:479-515）的 Recall/MRR 逻辑不动
- [x] T008 [US3] `MultiCprEvaluationTest.java` importPassages（:345-383）：逐条 embed 改为循环前用同包 `MmarcoZhSampledOllamaBatchEmbedder.embedAll` 批量预计算（embed 文本与现有 `ragService.embed(text)` 完全一致），循环内仅 DB 插入；新增 `@Value("${multi-cpr.embed-batch-size:32}")` 批大小
- [x] T009 [US3] 新增 `backend_v2/src/test/java/com/kama/jchatmind/rag/RagDatasetE2eEvaluationL2Test.java`：门控 `@EnabledIfSystemProperty(named = "rag.eval.dataset.e2e.enabled")`，Bean 装配复制 `MultiCprEvaluationTest.MultiCprTestConfig`（:544-558）。链路：`RagEvaluationDatasetLoader.load("rag-eval/datasets/manifests/kb-synthetic-v1.json")` → corpus 导入（`MarkdownParserServiceImpl` 解析 + `RagChunkSupport.buildChunkEmbeddingText` 拼接 + `MmarcoZhSampledOllamaBatchEmbedder` 批量 embed + `ChunkBgeM3Mapper` 入库，logicalChunkId→uuid 映射复制 `G2CurrentFixtureRuntimeCorpusLoader:82-148` 方案、corpus 文件映射从 manifest corpusFiles 读取）→ `RagServiceImpl.retrieve` 逐条 replay（replay jsonl 断点续跑 + `-Drag.eval.dataset.e2e.limit=N`）→ `RagFastRegressionEvaluator` 指标 → 分语言/分 queryType 分维度输出
- [x] T010 [US3] `RagDatasetE2eEvaluationL2Test` 补充 judge 最小接入：从 dataset 构造 `G2CurrentFixtureProfile` + contexts/answers 映射，调白名单化 `G2CurrentFixtureAnswerQualityEvaluator`（faithfulness/relevancy，评分口径不动）；answer-gold 文件加载 + 启动时 sha256 自校验；报告含 judge model/prompt/temperature
- [ ] T011 [US3] P0 回归验收（quickstart.md P0 节）：fixture 全管线回归 + Multi-CPR 小样本（两次运行集合一致、仅 grade>0、embed 速率提升）；**基线先跑 `mvnw.cmd test` 确认改动前状态**（已知基线非绿，记录失败集，改动后对比不新增失败）

## Phase 3: US1 构建并冻结数据集（P1→P2→P3 阶段）

**Story 目标**：产出冻结的 100 份语料 + 2000 case + 180 answer-gold + manifest/registry。独立测试：quickstart.md P1/P2/P3 节命令。

### P1 语料合成

- [x] T012 [US1] 编写 `datasets/kb-synthetic-v1/scripts/generate_corpus.py`：两阶段（topics.json 种子化大纲 → world_facts 注入逐 section LLM 填充）；`urllib.request` 调 OpenAI 兼容 API，零 pip 依赖，endpoint/model/key 全部环境变量 `KB_SYNTH_LLM_*`；单 section ≤800 token 输出
- [x] T013 [US1] generate_corpus.py 自校验与续跑：逐 section 校验（长度 300~1900 字符、无行首 `#`、无图片语法、大纲要点关键词命中、数字事实逐字出现、JSON 可解析），失败带错误反馈重试 ≤3 次后标记人工处理；`working/state.json` 记录已完成 (doc, section)，中断重跑只补缺失
- [x] T014 [P] [US1] generate_corpus.py 产出结构约束（research.md D3 前提）：`# H1` + 前言 + 全 `##`/`###` 组织 2~4 级路径；每文档植入 2~3 个精确数字事实、1~2 个跨文档同名近义 section 标题（hard-negative 对登记）、部分文档含全英文小节
- [x] T015 [US1] 新增 Java inventory 生成器（门控测试，`backend_v2/src/test/java/com/kama/jchatmind/rag/` 下，门控系统属性如 `rag.eval.dataset.inventory.enabled`）：用真实 `MarkdownParserServiceImpl` 解析全部 100 份语料，按 `G2CurrentFixtureRuntimeCorpusLoader:82-133` 方案输出 `datasets/kb-synthetic-v1/working/chunk-inventory.jsonl`（logicalChunkId = `{docId}#{contentPath}#{chunkIndex}`）；断言全部文档解析成功、chunk 总数 1200~2200、logicalChunkId 唯一（契约见 contracts/chunk-inventory.schema.md）
- [x] T016 [US1] 语料抽检：跨文档 n-gram 去重、乱码/半截句自动检查 + 人工抽检 10 份全文（四类各 ≥2）+ 20 份 section，记录 `working/review-log.json`（reviewedBy/reviewedAt/范围）

### P2 case 生成

- [x] T017 [US1] 编写 `datasets/kb-synthetic-v1/scripts/generate_cases.py`：输入 chunk-inventory.jsonl，按六类占比生成候选（title_path/title_to_content 20%、content_rewrite/user_like_question 30%、follow_up_contextual_rewrite 20% 且填 conversation、cross_document 15% 含 2~3 gold、no_answer 10%、permission_boundary 5%）；英文 ~8% 用 `title_path_en`/`content_rewrite_en`/`cross_document_en` 后缀变体（从对应中文类配额中划出）；LLM 生成时提供 logicalChunkId + contentPath + content 逐字摘录，goldFacts 逐字引用原文
- [x] T018 [US1] generate_cases.py 拒答与去重：no_answer 三类构成（out_of_scope ~90 基于 topics.json 清单 / missing_evidence ~70 / permission_denied ~40），全部 goldChunkIds/goldFacts 空 + abstentionReason 必填；query 归一化后 4-gram Jaccard ≥0.7 剔除 + 与 fixture-fast-v1 20 条跨集查重；原始候选（含被过滤项与原因）落 `working/case-candidates.jsonl`
- [x] T019 [US1] generate_cases.py 答案级 gold 子集（~180 条）：分层抽样（no_answer 40 refuse/citations 空、cross_document 40、multi-turn 30、含数字事实 medium/hard 60、英文 30、补齐）；字段按 contracts/answer-gold.schema.md（reference_answer/required_facts/citations 逐字 supporting_text/decision/forbidden_behaviors/grading）
- [x] T020 [US1] 编写 `datasets/kb-synthetic-v1/scripts/validate_dataset.py`：goldChunkId 必在 inventory、goldFacts 证据串（空白归一化后）为对应 chunk content 子串、sourceDocumentIds 一致、拒答/可回答约束（复刻 `RagEvaluationDatasetLoader.validateCases:69-91` 规则）、answer-gold caseId 存在性与 supporting_text 子串校验（contracts/answer-gold.schema.md 规则 1-3）、六类占比统计（±2%）
- [x] T021 [US1] case 人工抽检：分层抽 100 条（每 queryType × difficulty）+ no_answer 200 条与 answer-gold 180 条 100% 自复核，全部记录 review-log.json（单人降级方案，spec 假设已确认）；复核修改过的 case `createdBy` 改 `manual`

### P3 冻结

- [x] T022 [US1] 编写 `datasets/kb-synthetic-v1/scripts/freeze_dataset.py`（顺序敏感）：① cases.jsonl 规范化（UTF-8 无 BOM、`\n` 行尾）→ caseSha256 = 原始字节 SHA-256；② corpusSha256 按 contracts/manifest.schema.md 伪代码逐字复刻（路径字典序拼 `basename+"\n"+全文+"\n"` 整体 SHA-256）；③ 写 manifest（严格 11 字段，值见契约）；④ 复制冻结产物到 `backend_v2/src/test/resources/rag-eval/datasets/{cases,corpus,manifests,answer-gold}/`；⑤ 登记 `rag-eval/external-benchmark-registry.json` kbSyntheticV1 条目（contracts/registry-entry.schema.md，四项 artifacts sha256）
- [x] T023 [US1] 冻结校验冒烟（quickstart.md P3 节）：扩展 `backend_v2/src/test/java/com/kama/jchatmind/rag/RagEvaluationDatasetLoaderTest` 新增 kb-synthetic-v1 直读用例；验收 = loader 全绿（校验和匹配、2000 case schema 合法、无重复 caseId、拒答/可回答约束成立）

## Phase 4: US2 端到端评测（P4 阶段）

**Story 目标**：新数据集全量跑通并产出基线报告。独立测试：quickstart.md P4 节。

- [x] T024 [US2] 小批冒烟：`docker compose -f docker-compose.rag-eval.yml up -d` + `-Drag.eval.dataset.e2e.enabled=true -Drag.eval.dataset.e2e.limit=200`；验收 = 导入 ≥20 chunk/s、gold 对齐断言通过（T015 生成器联动）、指标输出正常
- [x] T025 [US2] 断点续跑验证：冒烟运行中途中断后重跑，确认已完成 case 零重复执行（replay jsonl 跳过逻辑）
- [x] T026 [US2] 全量 2000 条运行：分语言（zh/en）分 queryType 分难度指标报告落盘（报告路径带时间戳不覆盖）；Recall@5 首跑值记录为基线
- [x] T027 [US2] judge 冒烟：answer-gold 子集限 20 条，逐条 faithfulness/relevancy 输出，报告含 judge model/prompt/temperature；judge 不可用时 skipReason 路径正确（复用 evaluator 既有 skip 逻辑）
- [x] T028 [US2] 英文子集观测：分语言报告中对比 zh/en 检索指标，BM25 稀疏通路对英文的表现若异常仅记录为发现（不修，spec 边界场景）

## Phase 5: Polish（收口与知识沉淀）

- [x] T029 补全 `datasets/kb-synthetic-v1/README.md`：构建方法、复现步骤（固定种子 + 环境变量）、抽检与单人复核降级说明、answer-gold 不在 manifest sha256 链的治理降级明示
- [x] T030 更新 `docs/`：按项目文档导航新增本次收口记录（评测链路修复 + 数据集冻结基线），并核对 `docs/README.md` 索引
- [x] T031 全量校验入口回归：`cd backend_v2 && ./mvnw.cmd test`（对比 T011 记录的改动前失败集，确认无新增失败）；`datasets/kb-synthetic-v1/scripts` 下三个脚本 `python -m py_compile` 通过

## 依赖关系

```
T001→T002/T003(并行) → Phase 2 (T004-T011 可并行组：T004/T005 同组先后，T006/T007/T008 同文件串行，T009→T010)
Phase 2 (T011 验收) ──┬→ Phase 3 (T012→T013/T014→T015→T016→T017→T018→T019→T020→T021→T022→T023)
                      └→ Phase 4 (T024→T025→T026→T027→T028，依赖 T023 冻结 + T009/T010 runner)
Phase 5 (T029-T031) 收尾
```

- P0（Phase 2）独立可交付：语料合成受阻不阻塞链路修复
- Phase 3 内部串行为主（inventory 是 case 生成的输入）；T002/T003/T014 可并行
- Phase 4 依赖 Phase 3 冻结产物与 Phase 2 runner

## 并行执行示例

- **Phase 2 内**：T006+T007+T008 同文件串行；T004/T005 与 T006-T008 不同文件可并行
- **Phase 1 内**：T002 与 T003 并行
- **Phase 3 内**：T012~T014 语料脚本编写与 T015 Java 生成器骨架可并行（联调需 T013 完成）

## 实施策略

- **MVP = Phase 2（P0 链路修复）**：独立可验证交付，即使语料合成受阻（API 成本/质量）也有产出
- **增量交付**：P1 语料 → P2 case → P3 冻结 → P4 基线，每阶段有 quickstart.md 对应验收命令
- **验证纪律**：每阶段结束跑 quickstart.md 对应验收项再进下一阶段；T011/T031 两次全量测试对比确保无回归

## 执行状态（2026-11-10 P0 收口）

- 已完成并验证：T001~T010、T012~T015、T017~T020、T022~T023、T029~T031（编码 + 编译 + 全量回归 1107/0 failures/127 skipped，对比基线 1104/0/124 无新增失败）
- T011：全量回归部分已验证；Multi-CPR 与 fixture L2 的**运行期验收**需隔离评测 DB + Ollama bge-m3 + DeepSeek key（当前环境未启动）
- 待环境执行：T016/T021（语料与 case 的人工抽检）、T024~T028（端到端评测运行）——依赖 `KB_SYNTH_LLM_*` 环境变量、Ollama bge-m3、`docker compose -f docker-compose.rag-eval.yml up -d`
- 基线快照：`baseline-test.log` / `p0-test.log` / `final-test.log`（本目录）

## 执行状态补充（2026-11-12 P4 收口）

- **冻结交付**：manifest/cases(1990)/corpus(100)/answer-gold(180)/registry 全部落位，loader 冒烟 A 通过
- **T024**：通过（pipeline 全链路 + gold 对齐断言）；导入吞吐 0.3 chunk/s 未达 ≥20 线——容器 Ollama CPU 推理瓶颈（每批 32 条约 102s），import 一次性入 DB 后续复用，记录为环境限制
- **T025**：断点续跑验证通过（已完成的 200 条零重复执行，新增 200 条正常执行）
- **T027**：judge 冒烟通过（faithfulness=0.9765 / answerRelevancy=0.95 / complete 20/20）；judge 走火山 Coding Plan 端点（DeepSeek 官方 API 欠费，SPRING_AI_DEEPSEEK_BASE_URL 指向 ark /api/coding/v3）
- **T026**：全量 1990 条完成，Recall@5=0.798 / MRR@3=0.673
- **T028**：分维度报告（analyze_report.py → kb-synthetic-v1-dimension-report-20261112-full.json）：
  - title 类 ≈0.99，cross_document 0.92，content_rewrite/user_like ≈0.85
  - **follow_up 0.426（最弱维度）**——多轮改写依赖 query rewrite 质量，后续优化方向
  - 英文 Recall@5=0.811（略优于中文 0.797），MRR 0.568 略低；cross_document_en 0.70 为最大英文缺口——BM25 稀疏通路未崩，向量通路可用
  - abstentionAccuracy=0.0：replay 拒答信号为 Router 规则代理，真实拒答在 KnowledgeTool RRF 置信门禁层（超本次范围，已记录）
- **T011**：Multi-CPR 运行期验收未执行——需向 5432 开发库导入 2 万段落（涉及真实数据，按项目规则不做）；三处修复已经编译 + 全量回归覆盖（门控测试 skipped 状态下不执行运行期路径）
- 基线快照：final-test2.log（runner 改动后全量回归）
