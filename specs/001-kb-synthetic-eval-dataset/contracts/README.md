# Contracts: kb-synthetic-v1 数据产物契约

本目录定义 5 个数据契约，是构建脚本（Python）与消费端（Java loader / runner）之间的接口。所有 JSONL 文件 UTF-8 无 BOM、`\n` 行尾。

| 契约 | 消费端 | 严格性 |
|---|---|---|
| [case.schema.md](./case.schema.md) | `RagEvaluationDatasetLoader`（strict record） | 恰好 15 字段 |
| [manifest.schema.md](./manifest.schema.md) | `RagEvaluationDatasetLoader`（strict record） | 恰好 11 字段 |
| [answer-gold.schema.md](./answer-gold.schema.md) | 新 runner + 人工复核 | 宽松（Python 侧校验） |
| [chunk-inventory.schema.md](./chunk-inventory.schema.md) | case 生成 / gold 对齐 / runner 导入 | 生成器唯一产出 |
| [registry-entry.schema.md](./registry-entry.schema.md) | `rag-eval/external-benchmark-registry.json` | 与既有条目结构对齐 |

校验和算法（caseSha256 / corpusSha256）见 manifest.schema.md，逐字复刻 `RagEvaluationDatasetLoader.java:30-33,97-102`。
