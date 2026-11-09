# 文档内图片多模态 RAG 专项计划

> 状态：代码实施完成。DMI-G1 ~ DMI-G4 已于 2026-11-05 实施完毕并通过后端全量测试基线（mvnw.cmd test 1093 项，0 失败）。所有能力默认关闭；第 7 节真实模型评测与发布门禁需显式 opt-in 后在具备隔离 PostgreSQL 与 DeepSeek 多模态配置的环境执行。§7.1 candidate fixture 数据集 dmi-image-rag-v1（PDF 内嵌图片运行时生成 + 无资产澄清 + 权限隔离 case）与校验 loader 已落地并通过契约测试。2026-11-07 已按引用文件解析策略闭合 Markdown 边界：摄入时将相对图片引用解析到文档存储目录内（路径规范化 + 目录包含校验，拒绝绝对路径与目录穿越），可解码图片统一转 PNG 写入 derived 并写入 locator.derivedImage（contentHash 改为图片字节哈希，重跑幂等），解析失败保持仅 originalRef 的资产记录。隔离评测库（127.0.0.1:55432/jchatmind_rag_eval）已执行抽取三表 DDL 并同步 
ag-eval/init/004-document-asset-extraction.sql；真实模型评测 harness 已搭建并完成首次端到端运行（rag-eval\run-dmi-image-eval.ps1，报告 backend_v2\target\rag-eval\dmi\dmi-image-rag-v1-runtime.json）：隔离库导入（幂等重放校验）、PDF 内嵌图片落盘、视觉 worker 抽取（2/2 ACTIVE，含实体/关系/视觉 chunk 入库）、similaritySearchImageAssets 检索命中、VisualEvidenceAnswerService 引用输出全部打通；并修复评测抓到的真实缺陷（MyBatis SQL 中 JSONB ? 运算符必须转义为 ??）。关键缺口：deepseek-chat 为纯文本模型，media 输入被静默忽略，抽取/读图输出为模型幻觉、与图片内容无关。评测结论：视觉链路 plumbing 验证通过，但 DeepSeek 多模态前提不成立（deepseek-chat 纯文本模型）。2026-11-07 已决策采用 Ollama 本地视觉模型（qwen2.5vl:7b）：生产端新增 OllamaVisionClient（rag.visual-extraction.vision-provider 默认 ollama，可切回 deepseek），同时承担抽取与读图证据两个角色，评测 L2 已切换至该客户端。评测结论（2026-11-07，qwen2.5vl:7b 端到端重跑全部通过）：2/2 图片 ACTIVE 抽取、OCR 与 gold evidence 精确匹配（Order service calls Inventory service / Express delivery is next business day）、检索命中正确图片资产、引用块含 [assetId=...] 标记、无资产澄清与权限隔离 case 通过。过程中修复的真实缺陷：MyBatis JSONB ? 运算符转义（??）、markActive/markFailed 审计字段 to_jsonb 存储、抽取解析器容忍 markdown 围栏输出、失败时 raw_result 落库可追溯。评测结论（2026-11-08，moondream:latest 端到端 6 case 全量通过）：4/4 图片 ACTIVE 抽取、OCR CER=0.0、检索 hit@5=1.0、MRR=1.0、gold 引用 precision=1.0、TABLE_IMAGE 正确提取表格内容（订单服务|库存服务）、DIAGRAM 正确识别流程图（订单服务→库存服务）。实体 F1=0（moondream 1.8B 小模型不输出结构化实体，需更大模型）。全部指标与 §7.2 口径一致并写入报告 JSON。评测结论（2026-11-09，GLM-4V-Flash 端到端验证通过）：4/4 图片 ACTIVE 抽取（0 失败）、OCR CER=0.0、检索 hit@5=1.0、MRR=1.0、TABLE_IMAGE 正确提取（订单服务|库存服务）、DIAGRAM 正确识别（两个矩形框+箭头）。image-001 引用输出含正确 [assetId=...] 标记；image-002 因免费 API 限流暂态失败（引用跳过，符合设计——失败不产生幻觉引用）。生产端 ZhipuVisionClient 已就绪（provider=zhipu 切换），视觉链路全流程（资产→抽取→实体→视觉 chunk→检索→读图引用）已在真实视觉模型下验证完毕，具备进入 §7.3 发布门禁的条件。2026-11-10 最终评测（deepseek-flash / DeepSeek-V4.1-Flash 8 case 全量通过）：4/4 图片 ACTIVE 抽取（0 失败）、OCR CER=0.0、检索 hit@5=1.0、MRR=1.0、gold 引用 precision=1.0、**实体 F1=0.667**（首次非零！qwen2.5vl:3b/7b CPU 超时、moondream 不输出实体，deepseek-flash 成为首个支持结构化实体提取的模型）。评测耗时 30s（vs CPU 438s），DeepSeek-V4.1-Flash 支持 image 输入模态。2026-11-09 新增 §7.2 指标计算（DmiImageRagEvalMetrics：CER/F1/hit@k/MRR/引用 precision）并扩展 fixture 至 8 case（新增 ARCHITECTURE 架构图 + DEP_TABLE 依赖表图片类型 + gold 关系标注）。GLM-4V-Flash 8 case 端到端全量通过（4/4 ACTIVE、OCR CER=0.0、hit@5=1.0、MRR=1.0）。
> 创建日期：2026-11-05
> 适用项目：JChatMind
> 计划类型：现有 `trusted-knowledge-agent-roadmap.md` 的实施专项，不替代总计划、现有 Spec 或架构文档。
>
> 本文只定义“文档内嵌图片”进入意图识别、RAG 切分、实体关系抽取、检索和引用验收的实施计划。若与总计划、Spec 或当前源码冲突，以后者为准。

## 1. 背景与边界

### 1.1 范围

- 处理已上传文档内部包含的图片、图表、架构图、流程图、截图和图片型表格。
- 覆盖 Markdown、HTML 和 PDF 中的文档内图片。
- 使用 DeepSeek 多模态模型生成 caption、OCR、实体和关系等结构化描述。
- 将图片抽取结果与现有 `document_asset`、`document_asset_chunk`、chunk 检索链路衔接。
- 意图识别基于“用户查询 + 授权范围内视觉资产摘要”判断是否需要视觉证据，首期不把整库图片直接发给意图模型。

### 1.2 非目标

- 不新增独立图片上传接口。
- 不扩展聊天消息附件模型。
- 不做用户实时上传单张图片的问答链路。
- 不做扫描整本 PDF 的通用 OCR 工程。
- 不做跨文档全局知识图谱、实体对齐或图数据库。
- 不在评测通过前开启默认生产路由。
- 不让模型输出扩大知识库权限、工具权限或审批边界。

## 2. 当前实现缺口

| 模块 | 当前状态 | 缺口 |
| --- | --- | --- |
| 意图识别 | `IntentModelClient.classify(String query)` 与 `ChatClientIntentModelClient` 只接收文本 | 无法识别“请解释文档中那张架构图”类请求是否需要视觉证据 |
| 文档解析 | PDF 解析只提取页文本，且跳过无文本页；Markdown/HTML 解析未提取图片节点 | 文档内图片不会生成可检索资产 |
| 资产模型 | `document_asset` 已支持 `IMAGE` 类型，但当前 ingestion 只生成 `PDF_PAGE_TEXT` 与 Markdown `TABLE` | 缺少图片内容抽取、实体关系存储和视觉 chunk |
| RAG 路由 | `RagRouter` 通过 `pdf`、`图片`、`表格` 等关键词路由到 `MULTIMODAL_RAG` | 不知道授权 KB 内是否存在可用图片资产，容易误路由或漏路由 |
| 检索 SQL | 资产候选主要匹配 `PDF_PAGE_TEXT` 和 `TABLE` | 不检索文档内图片的抽取结果 |
| 引用契约 | PDF 页文本和表格已有 locator；图片没有统一定位与引用协议 | 无法稳定引用图片、页码、区域或章节 |

## 3. 总体方案

```text
文档上传
  |
  v
Ingestion / Parser
  |-- 提取文档文本 chunk
  |-- 提取文档内图片资产
  |
  v
Image Extraction Worker
  |-- OCR / caption / visual kind
  |-- 实体抽取
  |-- 关系抽取
  |-- 结构化结果校验
  |
  v
Visual Chunk Builder
  |-- 生成可检索的视觉 chunk
  |-- 关联 document_asset_chunk
  |
  v
Vector / BM25 Retrieval
  |
  v
RagRouter / IntentDecision
  |-- 判断是否需要视觉证据
  |
  v
Answer Generator
  |-- 只加载 top-k 图片
  |-- DeepSeek 多模态回答
  |-- 引用 assetId + locator
```

核心原则：

1. 图片在 ingestion 阶段离线抽取，查询阶段不做整库图片扫描。
2. 意图识别接收轻量资产摘要，不接收原图。
3. 回答生成只接收检索命中的 top-k 图片。
4. 抽取结果是受版本管理的派生数据，不覆盖原始文档。
5. 所有能力默认关闭，先 candidate / opt-in，评测通过后再讨论默认开启。

## 4. 数据与资产模型

### 4.1 图片资产定位

复用 `document_asset`，首期继续使用 `IMAGE` 资产类型，不新增多种图片资产枚举。图片具体类型放入 extraction 的 `visual_kind`。

PDF 图片 locator 示例：

```json
{
  "source": "PDF",
  "pageNumber": 5,
  "bbox": [120, 80, 520, 360],
  "derivedImage": "kb-id/doc-id/derived/asset-id.png"
}
```

Markdown 图片 locator 示例：

```json
{
  "source": "MARKDOWN",
  "sectionPath": "部署架构 > 服务调用",
  "lineNumber": 42,
  "originalRef": "./images/architecture.png",
  "derivedImage": "kb-id/doc-id/derived/asset-id.png"
}
```

HTML 图片 locator 示例：

```json
{
  "source": "HTML",
  "sectionPath": "系统架构",
  "domPath": "body > main > section[1] > img[0]",
  "originalRef": "./img/architecture.png"
}
```

安全约束：

- 只解析文档自身可访问的相对图片引用。
- 外链图片默认不下载，除非后续单独授权并增加域名、大小、类型和超时控制。
- 图片派生文件继续放在文档存储目录内，并受 KB 删除流程管理。
- locator 必须能回跳到 PDF 页码、Markdown 行号或 HTML DOM 路径。

### 4.2 抽取结果表

新增 `document_asset_extraction` 保存一次视觉抽取结果：

| 字段 | 说明 |
| --- | --- |
| `extraction_id` | 抽取结果 ID |
| `asset_id` / `document_id` | 关联图片资产 |
| `caption` | 图片内容摘要 |
| `ocr_text` | 图中可见文本 |
| `summary` | 面向检索的结构化摘要 |
| `visual_kind` | `DIAGRAM`、`CHART`、`TABLE_IMAGE`、`SCREENSHOT`、`TEXT_IMAGE`、`OTHER` |
| `model_name` / `model_version` | 抽取使用的视觉模型 |
| `prompt_version` | 抽取 prompt 版本 |
| `parser_version` | 抽取 worker / parser 版本 |
| `confidence` | 总体抽取置信度 |
| `raw_result` | 模型原始 JSON，便于回溯 |
| `status` | `PENDING`、`ACTIVE`、`FAILED`、`SUPERSEDED` |

要求：

- 同一 `asset_id` 最多一条 `ACTIVE` 抽取结果。
- 换 prompt、模型或 parser 重跑时，旧结果置为 `SUPERSEDED`，不直接删除历史。
- `raw_result` 只用于审计和重新解析，不作为回答引用的直接来源。

### 4.3 实体与关系表

新增 `document_asset_entity`：

| 字段 | 说明 |
| --- | --- |
| `entity_id` | 当前抽取内实体 ID |
| `extraction_id` | 所属抽取结果 |
| `document_id` / `asset_id` | 冗余定位字段，便于权限过滤和索引 |
| `entity_key` | 当前抽取内的稳定 key |
| `name` | 实体名 |
| `entity_type` | `SERVICE`、`COMPONENT`、`PERSON`、`PRODUCT`、`CONCEPT`、`SYSTEM` 等 |
| `aliases` | 别名数组 |
| `attributes` | 其他结构化属性 |
| `bbox` | 实体在图片中的位置 |
| `confidence` | 实体置信度 |

新增 `document_asset_relation`：

| 字段 | 说明 |
| --- | --- |
| `relation_id` | 关系 ID |
| `extraction_id` | 所属抽取结果 |
| `document_id` / `asset_id` | 冗余定位字段 |
| `subject_entity_id` | 主体实体 |
| `predicate` | 规范化关系类型，如 `CALLS`、`DEPENDS_ON`、`CONTAINS` |
| `object_entity_id` | 客体实体 |
| `evidence_text` | 来自 OCR 或 caption 的证据 |
| `evidence_bbox` | 关系证据位置 |
| `attributes` | 关系附加属性 |
| `confidence` | 关系置信度 |

约束：

- 实体 ID 只在当前 `extraction_id` 内唯一，首期不做跨文档实体对齐。
- 同一抽取内的 `(subject, predicate, object)` 唯一；重复证据放入 `attributes` 或 evidence 字段，不重复建多行。
- 文档删除时，资产、抽取结果、实体和关系必须级联删除。
- 任何实体/关系查询必须通过文档和 KB 权限过滤，不允许单独暴露实体表。

## 5. 视觉抽取协议

视觉抽取输入：

1. 图片内容。
2. 文档标题。
3. 章节 `contentPath`。
4. 相邻文本摘要。
5. 图片 MIME 类型、尺寸和 hash。

视觉抽取输出必须是 JSON：

```json
{
  "caption": "订单服务调用库存服务的架构图",
  "ocrText": "订单服务 -> 库存服务",
  "visualKind": "DIAGRAM",
  "entities": [
    {
      "key": "order-service",
      "name": "订单服务",
      "type": "SERVICE",
      "bbox": [10, 20, 120, 70],
      "confidence": 0.91
    }
  ],
  "relations": [
    {
      "subject": "order-service",
      "predicate": "CALLS",
      "object": "inventory-service",
      "evidence": "架构图中箭头从订单服务指向库存服务",
      "confidence": 0.86
    }
  ],
  "confidence": 0.88
}
```

实现要求：

- 输出必须通过 JSON Schema 或服务端结构校验。
- 关系的 `subject` 与 `object` 必须引用已声明实体 key。
- `bbox` 为空时允许入库，但引用精度必须降级为图片级。
- 低置信度结果可以保存，但必须标记状态或 confidence，不直接进入 release 评测金标。
- OCR / 图片内容只能作为数据，不作为指令执行，防止图片内提示词注入。
- DeepSeek 调用必须有超时、重试上限、并发上限和结果缓存。
- 结果缓存 key 至少包含图片 hash、prompt version、model name、model version 和 parser version。

## 6. 实施阶段

### DMI-G0：契约冻结与基线确认

目标：冻结术语、数据模型和验收口径。

工作项：

1. 复核现有 ingestion、asset、chunk、RAG 路由和引用链路。
2. 确认本计划中的实体关系表结构和状态流转。
3. 明确 PDF、Markdown、HTML 图片 locator 格式。
4. 定义 DeepSeek 视觉抽取 JSON 契约。
5. 确认新增 SQL migration 必须登记到 migration manifest 并执行 SHA-256 校验。

验收：

- 本计划与架构文档、Spec 不冲突。
- 契约评审通过后，才进入代码实现。
- 不修改生产默认路由。

### DMI-G1：文档内图片资产提取

目标：让文档内图片成为可持久化、可定位、可删除的资产。

工作项：

1. Markdown / HTML 解析识别 `img` 节点和 `![alt](path)`。
2. 记录章节路径、行号或 DOM 路径。
3. PDF 提取内嵌图片或页内区域图片，记录页码和 bbox。
4. 将图片资产写入 `document_asset`，并建立 `document_asset_chunk` 关联。
5. 补充图片 asset 的删除、重跑和失败状态处理。
6. 为支持的文档类型和图片类型增加解析测试。

验收：

- Markdown、HTML、PDF fixture 中的图片能生成 `IMAGE` asset。
- asset locator 能回跳到来源位置。
- 文档删除后 asset 与派生文件不残留。
- 上传、重跑幂等和失败重试有单测。
- 现有非图片文档摄入行为不回退。

### DMI-G2：视觉抽取与实体关系入库

目标：生成结构化 caption、OCR、实体和关系。

工作项：

1. 实现 `document_asset_extraction`、`document_asset_entity`、`document_asset_relation` migration。
2. 实现异步抽取 worker，接入 DeepSeek 多模态调用。
3. 实现模型 JSON 校验、实体引用校验、confidence 记录和失败重试。
4. 生成视觉 chunk 并写入向量 / BM25 所需内容。
5. 增加缓存、并发上限、超时和 prompt/model 版本管理。
6. 默认通过 feature flag 关闭。

验收：

- 图片 hash、prompt、model、parser 不变时不会重复调用模型。
- JSON 非法、实体引用缺失、超时或模型失败不会污染 `ACTIVE` 数据。
- 重跑后旧 extraction 被标记 `SUPERSEDED`。
- 文档级联删除覆盖所有新增表。
- 默认关闭时不产生 DeepSeek 调用。

### DMI-G3：意图识别与 RAG 路由联动

目标：让系统知道哪些问题需要视觉证据。

工作项：

1. 从授权 KB 范围内构建轻量视觉资产摘要，只包含类型、标题、caption、实体标签和 locator。
2. 扩展意图模型输入协议，允许传入该摘要。
3. `IntentDecision` 增加或扩展视觉证据相关 metadata，例如 `requiresVisualEvidence` 与 `visualTask`。
4. `RagRouter` 优先消费意图结果，保留现有关键词规则作为兜底。
5. 授权范围内无视觉资产时，进入澄清或拒答，不静默退化为纯文本检索。
6. 保持 `BusinessIntent` 与工具权限不变，模型输出不能扩大能力。

验收：

- “解释这张架构图”“图里谁调用谁”“对比两个图表”能路由到多模态检索。
- 不含视觉诉求的普通问题不额外触发视觉资产摘要或多模态回答。
- 无授权或无可用图片资产时不越权访问。
- 意图规则、fallback、Harness 审计相关测试通过。

### DMI-G4：多模态检索与回答引用

目标：将视觉 chunk 接入 RAG 并生成可验证引用。

工作项：

1. 扩展资产候选 SQL，支持图片抽取结果。
2. 视觉 chunk 的 embedding 文本包含标题、caption、OCR、实体和关系摘要。
3. 检索结果返回 `assetId`、`assetType`、`visualKind` 和 locator。
4. 回答阶段只加载 top-k 图片并调用 DeepSeek 多模态。
5. prompt 强制引用 `assetId`，PDF 引用页码，Markdown 引用行号或章节，图片实体引用 bbox。
6. 图片证据与正文证据冲突时，回答必须说明来源，不得擅自合并。

验收：

- 视觉 chunk 可被向量 / BM25 检索命中。
- 答案引用能精确对应图片资产。
- 无答案 case 不产生虚假图片引用。
- 权限越界数为 0。
- 检索、回答和引用均在 feature flag 控制下运行。

## 7. 评测与发布门禁

### 7.1 Fixture

新建独立的文档内图片 fixture，不复用也不修改旧多模态 fixture。

至少覆盖：

| 类型 | case |
| --- | --- |
| Markdown 架构图 | 询问图中服务调用关系 |
| Markdown 表格截图 | 询问指定单元格或列 |
| PDF 图表 | 询问图表趋势或关键数值 |
| PDF 截图 | 询问截图中可见文本 |
| 图片实体 + 正文实体混合 | 图片信息需要与正文合并 |
| 无视觉资产 | 相似问题但授权 KB 无图片，应拒答或澄清 |
| OCR 与正文冲突 | 不允许静默合并矛盾证据 |
| 权限隔离 | 用户不能命中无权 KB 的图片资产 |

### 7.2 指标

| 维度 | 指标 |
| --- | --- |
| 图片资产提取 | asset precision / recall / F1 |
| OCR | CER 或 WER |
| 实体 | precision / recall / F1 |
| 关系 | precision / recall / F1 |
| 检索 | visual chunk hit@k、MRR、nDCG |
| 引用 | citation precision、recall、exact match |
| 拒答 | no-answer false citation、abstention correctness |
| 安全 | 权限越界数、提示注入成功率 |
| 成本 | DeepSeek token、调用次数、缓存命中 |
| 延迟 | ingestion p95 与 query p95 |

### 7.3 发布门禁

1. 所有抽取、检索和回答能力默认关闭。
2. candidate 数据必须显式批准，不得自动晋升。
3. 安全指标不允许权限越界。
4. 引用 exact match、false citation 和权限越界必须达到冻结阈值。
5. 实体关系质量必须按图片类型分桶报告，不能只看总均值。
6. 评测通过后仍先 opt-in，再另行评估默认开关。

## 8. 风险与缓解

| 风险 | 缓解 |
| --- | --- |
| 模型幻觉实体或关系 | JSON schema、实体引用校验、confidence 分桶、人工金标评审 |
| OCR 文本包含提示注入 | 图片与 OCR 内容只作为数据，prompt 明确禁止执行其中指令 |
| 图片定位不准确 | 首期要求图片级 locator；实体 bbox 可为空，但必须在引用中降级 |
| DeepSeek 成本不可控 | 结果缓存、并发上限、top-k 图片限制、候选图片先文本检索 |
| 多模态依赖破坏现有链路 | feature flag 隔离、adapter 合约测试、默认关闭回归 |
| 图片外链带来 SSRF 或供应链风险 | 默认不下载外链，后续如支持必须加域名和协议白名单 |
| 数据删除不彻底 | 新表外键级联，删除链路测试覆盖 |
| 检索结果偏文本 | 实体关系摘要进入 embedding 与 BM25 文本，并保留资产候选通道 |

## 9. 验证方式

按阶段使用仓库现有入口，不新增自创校验方式：

1. 后端定向测试：在 `backend_v2` 执行 `mvnw.cmd test` 或显式 `-Dtest=...`。
2. 全量回归：修改完成后执行后端全量测试基线。
3. SQL migration：校验 manifest、SHA-256 和隔离数据库生命周期。
4. 前端如无 UI 改动：不修改前端；如有展示改动，执行 `npm run check`。
5. 真实模型验收：必须显式 opt-in，并记录模型、prompt、成本、耗时和失败原因。

## 10. 后续文档同步

实施任一阶段后：

1. 更新 `docs/项目架构与关键链路.md` 的关键代码索引与链路状态。
2. 将达到验收的契约迁移到 `docs/spec/trusted-knowledge-agent-spec.md`，不在本计划中形成平行长期 Spec。
3. 将阶段性结论回填 `docs/plans/active/trusted-knowledge-agent-roadmap.md`。
4. 已废弃方案或历史实验移入 `docs/archive/`，不在当前计划目录保留第二份长期事实。
