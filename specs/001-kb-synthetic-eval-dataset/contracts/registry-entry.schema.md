# 契约: registry 条目（rag-eval/external-benchmark-registry.json 新增）

在既有 registry JSON 中新增 `kbSyntheticV1` 条目，结构对齐现有条目（`mmarco` 等）与 checksumPolicy。

```jsonc
{
  "kbSyntheticV1": {
    "description": "自合成企业知识库端到端评测数据集（手册/FAQ/技术/运维，2000 query）",
    "origin": "original_synthetic",                   // 非 external benchmark
    "status": "frozen",
    "version": "v1",
    "verifiedAt": "<冻结日期 ISO8601>",
    "location": {
      "cases": "backend_v2/src/test/resources/rag-eval/datasets/cases/kb-synthetic-v1.jsonl",
      "corpus": "backend_v2/src/test/resources/rag-eval/datasets/corpus/kb-synthetic-v1/",
      "answerGold": "backend_v2/src/test/resources/rag-eval/datasets/answer-gold/kb-synthetic-v1-answer-gold.jsonl",
      "chunkInventory": "datasets/kb-synthetic-v1/working/chunk-inventory.jsonl"
    },
    "artifacts": {
      "caseFileSha256": "<同 manifest.caseSha256>",
      "corpusSha256": "<同 manifest.corpusSha256>",
      "answerGoldSha256": "<answer-gold 文件字节 SHA-256>",
      "chunkInventorySha256": "<inventory 文件字节 SHA-256>"
    },
    "checksumPolicy": "requiredBeforeImport"          // 与现有规则对齐
  }
}
```

## 规则

1. 四项 artifacts 全部在冻结时计算，与 manifest 共享 case/corpus 两项值
2. runner 启动时对 answer-gold 与 inventory 重算比对（它们不在 manifest sha256 链内）
3. 修订出 `kb-synthetic-v2` 时新增条目，不改写 v1 历史条目
