# Quickstart: kb-synthetic-v1 构建与评测验证

按阶段验证，每阶段有独立验收项。详细数据契约见 [contracts/](./contracts/)，实体模型见 [data-model.md](./data-model.md)。

## 前置条件

- Java 17 + Maven（`backend_v2/mvnw.cmd`）
- Docker（隔离评测库）：`docker compose -f docker-compose.rag-eval.yml up -d`（PostgreSQL 127.0.0.1:55432/jchatmind_rag_eval，schema 由 `rag-eval/init/002-evaluation-schema.sql` 建立）
- 本地 Ollama + `bge-m3` 模型
- LLM API 环境变量：`KB_SYNTH_LLM_BASE_URL` / `KB_SYNTH_LLM_MODEL` / `KB_SYNTH_LLM_API_KEY`（语料合成与 case 生成用，judge 用现有 deepseek-chat 配置）

## P0 链路修复回归（不依赖新数据集）

```powershell
# 存量 fixture 全管线回归（白名单改动不破坏存量）
cd backend_v2
./mvnw.cmd test "-Dtest=G2CurrentFixture*Test" "-Dg2.current.fixture.runtime.enabled=true"

# Multi-CPR 修复验证（小样本）
./mvnw.cmd test "-Dtest=MultiCprEvaluationTest" "-Drag.eval.multi-cpr.enabled=true"
```

验收：fixture 回归全绿；Multi-CPR 两次运行 corpus 集合一致（固定种子）、仅 grade>0 计相关、embed 速率显著高于原约 4 条/秒。

## P1 语料合成

```powershell
cd datasets/kb-synthetic-v1/scripts
python -I generate_corpus.py      # 断点续跑：中断后重跑只补缺失
python -I validate_dataset.py --stage corpus
```

验收：100 份 md（30/25/30/15 分布）；自动校验全绿（长度/格式/数字事实逐字/跨文档 n-gram 去重）；抽检 10 份全文 + 20 份 section 记录 `working/review-log.json`。

## P2 case 生成

```powershell
python -I generate_cases.py       # 输入 chunk-inventory.jsonl
python -I validate_dataset.py --stage cases
```

验收：2000 行 cases（六类占比 R3.1 ±2%、英文 ~8% 可按 queryType 统计）；180 行 answer-gold；gold 自动校验 100% 通过（inventory 命中 + 证据子串 + sourceDocumentIds 一致）；no_answer 200 条与 answer-gold 180 条 100% 自复核。

## P3 冻结

```powershell
python -I freeze_dataset.py       # 计算校验和 → 写 manifest → 登记 registry
```

验收（无 DB、秒级）——扩展 `RagEvaluationDatasetLoaderTest` 直读新 manifest：

```powershell
cd backend_v2
./mvnw.cmd test "-Dtest=RagEvaluationDatasetLoaderTest"
```

loader 全绿 = caseSha256/corpusSha256 匹配、2000 case schema 合法、无重复 caseId、拒答/可回答约束成立。

## P4 端到端评测冒烟

```powershell
cd backend_v2
# 小批冒烟（含 gold 对齐断言 + 断点续跑验证：中断后重跑不重复已完成 case）
./mvnw.cmd test "-Dtest=RagDatasetE2eEvaluationL2Test" `
  "-Drag.eval.dataset.e2e.enabled=true" "-Drag.eval.dataset.e2e.limit=200"
# 全量 2000 条（约 1~2 小时，支持断点续跑）
./mvnw.cmd test "-Dtest=RagDatasetE2eEvaluationL2Test" `
  "-Drag.eval.dataset.e2e.enabled=true"
# judge 冒烟（answer-gold 子集限 20 条）
# 报告需含 judge model/prompt/temperature
```

验收：导入 ≥20 chunk/s；分语言（zh/en）分 queryType 分难度指标报告落盘；Recall@5 首跑值即基线（不设门禁）；judge 逐条 faithfulness/relevancy 输出，judge 不可用时 skipReason 路径正确。

## 最终状态

- `backend_v2/src/test/resources/rag-eval/datasets/` 冻结产物 + manifest + registry 登记
- `datasets/kb-synthetic-v1/` 构建工作区（脚本/config/审计痕迹）提交入库
- 基线报告存档（报告路径带时间戳，不覆盖）
