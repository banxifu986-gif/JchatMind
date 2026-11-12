# Research: kb-synthetic-v1 设计决策

所有 NEEDS CLARIFICATION 已在规划阶段由用户确认（英文 queryType / 断点续跑 / judge 范围 / 抽检降级），本文件记录技术决策、依据与备选方案。引用位置已逐文件核实。

## D1. 答案级 gold 必须独立文件

- **Decision**: 180 条答案级 gold 存放 `answer-gold/kb-synthetic-v1-answer-gold.jsonl`，不混入 cases.jsonl
- **Rationale**: `RagEvaluationDatasetLoader.java:20` 使用裸 `new ObjectMapper()`（默认 FAIL_ON_UNKNOWN_PROPERTIES=true）解析 strict record `RagEvaluationCase`（15 字段）/ `RagEvaluationDatasetManifest`（11 字段），cases.jsonl 加附加字段必解析失败；manifest v1 无字段位承载 answer-gold 校验和
- **Alternatives**: (a) 升级 schema v2 加字段 —— 改动公共 record，影响存量 fixture 数据集，超范围；(b) 答案级 gold 拆成 15 字段 case —— 丢失 reference_answer/decision 等语义。选择独立文件 + registry 登记 + runner 启动自校验 sha256 的治理降级
- **风险明示**: answer-gold 不受 manifest sha256 链保护，README 明示

## D2. corpusSha256 / caseSha256 算法逐字复刻

- **Decision**: freeze 脚本按 `RagEvaluationDatasetLoader.validateCorpus:97-102` 逐字复刻：corpusFiles 路径**字典序**，逐文件拼 `basename + '\n' + 全文 + '\n'` 后整体 SHA-256；caseSha256 = case 文件原始字节（:30-33）
- **Rationale**: loader 冻结校验是硬门禁，算法偏差即加载失败
- **Alternatives**: 无（既有契约）

## D3. Java inventory 生成器为 chunk 唯一事实源

- **Decision**: 新增门控测试用真实 `MarkdownParserServiceImpl` 解析 100 份语料，输出 `working/chunk-inventory.jsonl`（logicalChunkId = docId#contentPath#chunkIndex，复制 `G2CurrentFixtureRuntimeCorpusLoader:82-133` 方案）
- **Rationale**: `MarkdownParserServiceImpl.java:32-33` 有 `maxSectionContentLength=2000` 拆分与 `minPreferredChunkLength=1000` 合并行为，后者合并逻辑未逐行核实，不能假设"1 section = 1 chunk"；Python 侧 300~1900 字符约束仅求边界可预期
- **Alternatives**: (a) Python 复刻分块算法 —— 双实现漂移风险；(b) 逐行核实合并逻辑后纯 Python —— 仍以真实解析器为准更稳

## D4. Multi-CPR 采样改种子化蓄水池

- **Decision**: `MultiCprEvaluationTest.loadCorpus:164-209` 前 N 行截断 → 每文件 `new Random(0)` 固定种子蓄水池采样，`requiredPassages` 强制纳入逻辑（:190-204）保留
- **Rationale**: 前 N 行偏置文件头部；固定种子保证两次运行 corpus 集合一致（可复现验收项）
- **Alternatives**: 全量导入 —— 20K 采样口径变化，破坏与历史基线可比性

## D5. 批量 embed 复用 MmarcoZhSampledOllamaBatchEmbedder

- **Decision**: `importPassages:345-383` 逐条 embed → 同包 `MmarcoZhSampledOllamaBatchEmbedder.embedAll` 批量预计算，入库文本与现有 `ragService.embed(text)` 完全一致；新增 `@Value("${multi-cpr.embed-batch-size:32}")`
- **Rationale**: 现约 4 条/秒，千级语料需数小时；批量 embeder 已存在且文本契约一致保证向量可比；目标 ≥20 chunk/s
- **Alternatives**: 新写 embeder —— 重复造轮子

## D6. 白名单只改 2 处

- **Decision**: 仅 `G2CurrentFixtureProfileLoader.java:58` 与 `G2CurrentFixtureAnswerQualityEvaluator.java:39` 改 `SUPPORTED_DATASET_IDS = Set.of("fixture-fast-v1", "kb-synthetic-v1")`；其余 9 处 `"fixture-fast-v1"` 字面量（RuntimeCorpusLoader 的 4 文档映射等）不动
- **Rationale**: 新数据集不走 G2 fixture 全管线（含 router ablation/citation candidate 超范围组件），走新 runner；精准修改原则
- **Alternatives**: 全部白名单化 —— 扩大修改范围且多数路径对新数据集语义不成立

## D7. 新 runner 组合既有组件

- **Decision**: 新增 `RagDatasetE2eEvaluationL2Test`（门控 `rag.eval.dataset.e2e.enabled`），链 = `RagEvaluationDatasetLoader` 加载 → corpus 导入（解析+批量 embed+`ChunkBgeM3Mapper` 入库，映射从 manifest corpusFiles 读取）→ `RagServiceImpl.retrieve` 逐条 replay（断点续跑 + `-Drag.eval.dataset.e2e.limit=N`）→ `RagFastRegressionEvaluator` 指标 → 白名单化 judge 最小接入 → 报告含 judge model/prompt/temperature
- **Rationale**: 五套存量评测轨道均为门控 JUnit 测试（`@EnabledIfSystemProperty`），沿此模式零生产改动；断点续跑为用户确认需求（全量约 1~2 小时）
- **Alternatives**: 独立 CLI 模块 —— 需新装配与构建配置，超范围

## D8. Python 脚本 stdlib-only + 环境变量配置

- **Decision**: `urllib.request` 调 OpenAI 兼容 API，零 pip 依赖（先例：`datasets/ecommerce-small-appliances/scripts/build.py`）；`KB_SYNTH_LLM_BASE_URL/MODEL/API_KEY` 环境变量
- **Rationale**: 不加新依赖规则；密钥不硬编码
- **Alternatives**: requests/httpx —— 新依赖，违规

## D9. 已知风险与观测项（不在本次修复范围）

1. `<1000` 字符 section 合并行为未逐行核实 → D3 的 inventory 兜底
2. BM25 中文词典（`rag_bm25_token_dictionary`）对英文 query 表现未知 → 冒烟分语言报告，异常只记录
3. judge 评分与 gold 不对齐（只算 faithfulness/relevancy）→ 用户确认本次最小接入，judge-gold 对齐列为后续方向
4. 全量 2000 条单次评测约 1~2 小时 → runner 断点续跑
5. 合成成本约 5000+ 次 LLM 调用 → 断点续跑 + 单 section ≤800 token 输出控制
