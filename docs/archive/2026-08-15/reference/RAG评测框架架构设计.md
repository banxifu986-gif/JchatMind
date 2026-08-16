# RAG 评测框架架构设计

## 概述

RAG 评测框架采用三层金字塔结构，分别覆盖标准基准、系统化召回评测和线上快速冒烟三个层面。所有评测共用同一套 `RagService.retrieve()` 检索接口，确保测试结果能真实反映生产链路表现。

## 三层架构

### 第一层：MultiCprEvaluationTest — 公开基准评测

**定位**: 用学术标准数据集建立可对外比较的检索基线。

**数据来源**: Multi-CPR 中文段落检索基准，包含 ecom / medical / video 三个领域，每个领域提供 corpus.tsv（段落语料）、dev.query.txt（查询）、qrels.dev.tsv（人工标注相关段落 ID）。

**流程**:

1. 加载标准数据集三件套
2. 对 corpus 做采样（默认 20000 条）+ 强制保留所有 qrels 标注涉及的 passage
3. 逐条调用 `ragService.embed()` 生成 BGE-M3 向量并写入 PostgreSQL pgvector
4. 逐条用 `ragService.retrieve()` 检索 top-K，对比 qrels 标注计算命中
5. 输出 Recall@1/3/5/10 + MRR，报告落盘 `target/multi-cpr-eval/{domain}-report.json`

**配置**: 通过 `application-multi-cpr.yaml` 的 `multi-cpr.domain` 属性切换领域，`rerun-import: false` 可跳过已导入数据。

**输出指标**: Recall@1, Recall@3, Recall@5, Recall@10, MRR, Hit 分布, Miss 案例列表（top 20）。

---

### 第二层：RagRecallEvaluationTest — 内部多维度召回评测

**定位**: 最核心的系统化评测，衡量 Markdown 知识库检索在多个维度上的表现。

#### 评测模式

| 模式 | 说明 |
|------|------|
| Fixture | 内置测试 Markdown (`fixture-kb.md`)，可快速重复，不依赖外部数据 |
| Real | 导入真实知识库的 Markdown 文档，评测线上实际数据 |

#### 10 种查询维度

每个 Markdown section 自动生成以下查询用例：

| 维度 | 查询构造方式 | 测试目标 |
|------|------------|---------|
| `title` | 直接用 section 标题作为查询 | 精确标题匹配 |
| `title_path` | 完整 contentPath（如 `A > B > C`） | 路径级定位 |
| `title_to_content` | 用标题查该 section 的 body 内容 | 标题→内容跨层匹配 |
| `source_scoped_title` | 标题 + sourceName/sourceType 上下文 | 限定来源域的定位 |
| `contextual_title_query` | 标题 + sourceName + sourceType + parentContentPath | 带上下文的标题查询 |
| `auto_path_selection` | `parentContentPath > 标题` 格式，含自动路径选择逻辑 | 路径感知能力 |
| `rewrite` | 从 section body 提取关键句作为查询 | 内容改写召回 |
| `user_like_question` | 根据标题语义生成自然问法（如 "X 的原理是什么"） | 自然语言泛化 |
| `follow_up_contextual_rewrite` | 低信息追问（如 "这部分该怎么处理"）+ 上下文 | 上下文追问补全 |
| `topic_switch_guard` | 用下一个 section 的 contentPath 搭配当前上下文 | 主题切换守卫 |

#### Gold Standard 解析策略

自动匹配 section 到对应 chunk，优先级从高到低：

1. **exact_content** — section 内容与 chunk 内容精确匹配
2. **title_anchor** — section 标题与 chunk 的 retrievableTitle 匹配
3. **metadata_section_index** — section 索引与 chunk 元数据 sectionIndex 匹配
4. **section_order_exact** — 按创建时间的顺序匹配
5. **content_overlap** — section 与 chunk 内容存在包含关系
6. **not_found** — 无法匹配，该 case 被排除

#### 4 个评价维度

| 维度 | 组成 | 关注点 |
|------|------|--------|
| title_recall | title + title_path + source_scoped_title + contextual_title_query + auto_path_selection | 标题、路径和上下文定位能力 |
| query_rewrite | rewrite + user_like_question | 自然问法和内容改写后的召回 |
| rerank_quality | title + rewrite + title_path + user_like_question | 排序质量（Recall@1, MRR） |
| follow_up_contextual | follow_up_contextual_rewrite + topic_switch_guard | 低信息追问和主题切换 |

#### A/B 消融对比

自动构建两种变体进行消融分析：

- **no_query_expansion**: 关闭 `retrievalQueries` 扩展，仅保留原始 query，测量查询扩展层贡献
- **no_rerank**: 关闭 rerank，仅保留 RRF 融合后的原始顺序，测量排序层贡献
- **bottleneck_assessment**: 根据 MRR 差值自动判断当前瓶颈层（`query_expansion_dominant` / `rerank_dominant` / `mixed_or_close`）

#### 回归检测

通过 `rag.eval.compare-with` 指向上一轮报告路径，自动计算：
- `newMissCases`: 本轮新增的漏检 case
- `fixedMissCases`: 较上轮已修复的漏检 case

---

### 第三层：RagOnlineE2eEvaluationTest — 线上端到端快速冒烟

**定位**: 在真实知识库上快速抽样，验证端到端检索体验。

**流程**:

1. 从线上 chunk 中读取具备 sourceName + contentPath + title 的可用 chunk
2. 自动生成 3 类查询：
   - `path_aware`: `parentContentPath > title`
   - `source_path`: 在来源域内指定路径的问法
   - `title_question`: 根据标题语义生成自然问法
3. 控制每类最多 4 条、每个 source 最多 2 条、查询不超过 80 字符
4. 检索 top-3，验证命中的 chunkId 或 contentPath 是否与预期一致
5. 输出 Hit@1 / Hit@TopK，报告落盘 `target/rag-eval/online-e2e-report.json`

---

## 公共设计模式

### 独立测试配置

每个评测类各有一个 `@Configuration` 内部类，精确保温所需 Bean：
- 通过 `@ImportAutoConfiguration` 只加载 Jackson / DataSource / JdbcTemplate / MyBatis
- 通过 `@Import` 注入具体 Service 实现类
- 通过 `@ActiveProfiles` 隔离配置（`multi-cpr` / `rag-eval`）
- 避免 Spring AI MCP 自动配置等无关组件干扰

### 面向真实检索接口

所有评测直接调用 `RagService.retrieve(kbIds, query, context, limit)`，走完整的多路召回链路：
向量检索 / BM25 标题检索 / BM25 内容检索 / 精确标题检索 / 标题包含检索 / 关键词检索 / Trigram 检索 → RRF 融合 → 重排序

### 标准化报告

每轮评测生成 JSON 报告写入 `target/` 目录：
- Multi-CPR: `target/multi-cpr-eval/{domain}-report.json`
- Recall: `target/rag-eval/report.json`
- Online E2E: `target/rag-eval/online-e2e-report.json`

---

## 使用流程

### 运行标准基准评测

```bash
# 修改 application-multi-cpr.yaml 中的 domain 切换领域
mvn test -Dtest=MultiCprEvaluationTest -Dspring.profiles.active=multi-cpr
```

### 运行内部召回评测

```bash
# Fixture 模式（快速）
mvn test -Dtest=RagRecallEvaluationTest -Dspring.profiles.active=rag-eval \
  -Drag.eval.mode=fixture

# Real 模式（完整，需先配置 real-kb-id）
mvn test -Dtest=RagRecallEvaluationTest -Dspring.profiles.active=rag-eval \
  -Drag.eval.mode=real -Drag.eval.real-kb-id=<知识库ID>

# 对比上一轮报告
mvn test -Dtest=RagRecallEvaluationTest -Dspring.profiles.active=rag-eval \
  -Drag.eval.compare-with=target/rag-eval/report.json
```

### 运行线上 E2E 冒烟

```bash
mvn test -Dtest=RagOnlineE2eEvaluationTest -Dspring.profiles.active=rag-eval \
  -Drag.eval.real-kb-id=<知识库ID>
```

---

## 评测结果

### 基线 (2026-05-26)

| 指标 | ecom | medical | video |
|------|------|---------|-------|
| Recall@10 | 0.755 | 0.609 | 0.868 |
| MRR | 0.544 | 0.455 | 0.650 |

配置: BGE-M3 嵌入, 20K 采样, 无 LLM 改写, 10 路多召回 + RRF + 重排序

### Step 3 LLM 改写 (ecom, 2026-05-27)

Recall@10: 0.755 → 0.761 (+0.006), MRR: 0.544 → 0.545 (+0.001)

结论: 对 ecom 领域效果甚微。medical 和 video 待评测。

---

## 第四层：Chunk 多样性指标 (2026-05-29)

**定位**: 衡量检索结果在文档来源和内容路径上的分布广度，补充传统 Recall/MRR 只关注"命中与否"的不足。

**指标**:

| 指标 | 说明 |
|------|------|
| `uniquePaths` | top-K 结果中不重复的 contentPath 数量 |
| `uniqueSources` | top-K 结果中不重复的 sourceName 数量 |
| `pathDiversityRatio` | uniquePaths / totalSlots，衡量路径分散度 |
| `sourceDiversityRatio` | uniqueSources / totalSlots，衡量来源分散度 |

**计算方式**: 解析每个检索结果的 metadata JSON 中的 `contentPath` 和 `sourceName` 字段，统计全局唯一值，分别在 k=5 和 k=10 两个窗口计算。多样性指标同时输出到 aggregate、dimension 和 breakdown 三层。

**用途**: 如果 top-5 全是同一章节的不同层级 chunk，即使 Recall=1.0 也存在多样性风险——LLM 生成时可用的信息面太窄。

---

## 第五层：答案质量评测 (2026-05-29)

**定位**: 在检索层之上增加 LLM-as-judge 的答案质量评测，弥补纯检索指标无法反映端到端生成质量的不足。

**模式**: 完全可选，默认关闭。启用后对采样 query cases 执行以下流程：

1. 检索 top-5 chunk 拼接为上下文
2. 调用 ChatClient 基于上下文生成回答
3. LLM-as-judge 评判两个维度：
   - **Faithfulness**：回答是否严格基于提供的上下文（无幻觉）
   - **Answer Relevancy**：回答是否直接回应了查询（不偏题）

**配置** (`application-rag-eval.yaml`):

```yaml
rag:
  eval:
    answer-quality:
      enabled: false     # 默认关闭
      sample-size: 10    # 每次评测采样条数
      model: deepseek-chat
```

**启用方式**:

```bash
mvn test -Dtest=RagRecallEvaluationTest \
  -Dspring.autoconfigure.exclude="" \
  -DRAG_EVAL_ANSWER_QUALITY_ENABLED=true
```

**降级**: ChatClientRegistry 不可用时自动跳过，不阻塞主评测流程。

---

## 跨文档 Fixture (2026-05-29)

原 fixture 仅覆盖单文档 3 个 section。现已扩展为 4 份电商客服域 Markdown 文档：

| 文件 | 内容 | Sections |
|------|------|----------|
| `fixture-kb.md` | 订单退款、发货时效、会员积分 | 3 |
| `fixture-kb-returns.md` | 退货退款政策（嵌套层级） | ~8 |
| `fixture-kb-logistics.md` | 发货与物流（含交叉术语） | ~7 |
| `fixture-kb-membership.md` | 会员体系与积分规则 | ~6 |

交叉术语（"订单退款"同时出现在物流文档，"会员积分"同时出现在三个文档）可检验跨文档检索的辨别能力。

通过 `rag.eval.fixture.multi-doc` 开关控制，默认开启。设为 `false` 回退到单文档兼容模式。

---

## 已知局限

- 当前评测配置需排除 MCP 自动配置（`ToolCallbackConverterAutoConfiguration` 等），否则启动超时
- `MultiCprEvaluationTest` 启动依赖 `DeepSeekChatModel`（来自 `application.yaml` 中 `spring.ai.openai` 配置），即使未启用 LLM 改写
- Ollama 本地 CPU 运行 embedding 速率约 4 条/秒，大批量导入耗时较长
- 答案质量评测依赖 LLM API 调用，成本较高，不适合高频回归；当前仅对主评测采样 10 条
- 答案质量评测默认关闭，启用需手动排除 LLM 自动配置排除项
