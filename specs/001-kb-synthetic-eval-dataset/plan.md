# Implementation Plan: kb-synthetic-v1 千级 RAG 端到端评测数据集 + 链路修复

**Branch**: main（spec 目录与分支解耦，实施时按需开分支）
**Spec**: [spec.md](./spec.md) | **Checklist**: [checklists/requirements.md](./checklists/requirements.md)
**Date**: 2026-11-10

## Technical Context

| 维度 | 取值 |
|---|---|
| 运行环境 | Windows 11 + Git Bash；Java 17 / Spring Boot 3.5.8；Python（仅标准库） |
| 后端模块 | `backend_v2`（评测代码全部在 test tree，无生产模块改动） |
| 数据存储 | 隔离评测 PostgreSQL（`docker-compose.rag-eval.yml`，127.0.0.1:55432/jchatmind_rag_eval） |
| Embedding | Ollama `bge-m3:latest`（1024 维），输入契约 = contentPath + "\n" + title + "\n" + content |
| LLM 服务 | OpenAI 兼容接口（deepseek-chat 一致），endpoint/model/key 走环境变量 `KB_SYNTH_LLM_*` |
| 硬约束 | 评测数据登记 `rag-eval/external-benchmark-registry.json`（SHA-256）；冻结后修订出新版本号 |

## Constitution Check（无 .specify constitution，以项目 CLAUDE.md 替代）

| 项目规则 | 检查结果 |
|---|---|
| 不加新依赖未经确认 | PASS — Python 脚本仅标准库；Java 复用现有测试依赖与 bean |
| 不硬编码密钥 | PASS — 全部环境变量 |
| 精准修改、不扩大范围 | PASS — 出界清单见 spec；G2 其余 9 处硬编码不动 |
| 先确认基线再改动 | PASS — P0 先行且含存量回归（fixture L2 + Multi-CPR） |
| 分阶段验收 | PASS — spec 阶段表 P0~P4 |

## 设计决策摘要（详见 research.md）

1. **答案级 gold 独立文件** — 现有 loader 为 strict record 解析，不可扩展字段
2. **Java inventory 为 chunk 唯一事实源** — Python 长度约束只求边界可预期，不依赖"1 section = 1 chunk"假设
3. **`_en` 后缀 queryType** — 用户确认新增枚举变体；loader 只校验 hasText，无破坏
4. **种子化蓄水池采样 + 批量 embed** — 修 Multi-CPR 偏置与吞吐
5. **断点续跑双层** — Python state.json（构建侧）+ replay jsonl（评测侧）

## Phases

- **Phase 0**: research.md（本目录）
- **Phase 1**: data-model.md、contracts/（5 个数据契约）、quickstart.md
- **实施**: 按 tasks.md（P0→P1→P2→P3→P4，见 speckit-tasks）

## 关键文件（实施目标）

| 文件 | 动作 |
|---|---|
| `backend_v2/src/test/java/com/kama/jchatmind/rag/G2CurrentFixtureProfileLoader.java` | 改：datasetId 白名单（:58） |
| `backend_v2/src/test/java/com/kama/jchatmind/rag/G2CurrentFixtureAnswerQualityEvaluator.java` | 改：datasetId 白名单（:39） |
| `backend_v2/src/test/java/com/kama/jchatmind/rag/MultiCprEvaluationTest.java` | 改：采样（:164-209）/ qrels（:257-271）/ 批量 embed（:345-383） |
| `backend_v2/src/test/java/com/kama/jchatmind/rag/RagDatasetE2eEvaluationL2Test.java` | 新增：统一评测入口 |
| `backend_v2/src/test/resources/rag-eval/datasets/{cases,corpus,manifests,answer-gold}/kb-synthetic-v1*` | 新增：冻结交付物 |
| `datasets/kb-synthetic-v1/`（scripts/config/working/README） | 新增：构建工作区 |
| `rag-eval/external-benchmark-registry.json` | 改：登记新条目 |

## 复用清单（不重造）

`RagEvaluationDatasetLoader`（冻结校验）、`RagAsMetrics`、`RagFastRegressionEvaluator`（replay+指标）、`MmarcoZhSampledOllamaBatchEmbedder`（批量 embed）、`MmarcoZhSampledEvaluator`（NDCG+bootstrap，后续方向）、`G2CurrentFixtureRuntimeCorpusLoader`（logicalChunkId 方案模板）、`MarkdownParserServiceImpl` + `RagChunkSupport`（解析与 embedding 契约）、`G2CurrentFixtureAnswerQualityEvaluator`（judge，白名单化后复用）。
