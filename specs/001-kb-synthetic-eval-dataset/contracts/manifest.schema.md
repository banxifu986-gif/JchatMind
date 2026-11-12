# 契约: 数据集 manifest（manifests/kb-synthetic-v1.json）

单个 JSON 对象，**恰好 11 字段**（对应 strict record `RagEvaluationDatasetManifest`）。

```jsonc
{
  "datasetId": "kb-synthetic-v1",
  "schemaVersion": "1",
  "caseFile": "cases/kb-synthetic-v1.jsonl",
  "caseSha256": "<case 文件原始字节 SHA-256, hex>",
  "corpusFiles": ["corpus/kb-synthetic-v1/faq-01.md", "...共 100 项"],
  "corpusSha256": "<语料汇总 SHA-256, hex>",
  "chunkingVersion": "markdown-section-v1",
  "embeddingModel": "bge-m3",
  "embeddingInputVersion": "title-title-content-v1",
  "defaultTopK": 10,
  "status": "frozen"
}
```

## 校验和算法（逐字复刻 `RagEvaluationDatasetLoader.java`）

**caseSha256**（:30-33）：caseFile 的**原始文件字节**直接 SHA-256，hex 编码。冻结前文件必须 UTF-8 无 BOM、`\n` 行尾。

**corpusSha256**（`validateCorpus:97-102`）：

```python
# corpusFiles 按字符串自然排序（字典序）
content = ""
for f in sorted(corpus_files):                      # 路径字典序
    content += basename(f) + "\n"                   # 只取文件名，不含目录
    content += read_text(f, utf-8) + "\n"           # 全文 + 单个换行
corpus_sha256 = sha256(content.encode("utf-8")).hexdigest()
```

路径解析规则（`resolveSiblingPath:107-110`）：corpusFiles/caseFile 为**相对 manifest 所在目录**的路径（`rag-eval/datasets/` 下）。

## 一致性约束（`G2CurrentFixtureProfileLoader.validateCurrentFixture:56-63` 白名单化后仍生效）

- `chunkingVersion == "markdown-section-v1"`、`embeddingModel == "bge-m3"`、`defaultTopK == 10`
- `schemaVersion == "1"`、`status == "frozen"`（`validateManifest:61-67`）

## 冻结纪律

冻结后禁止覆盖；任何修订（语料/case/answer-gold/inventory 变更）→ 出 `kb-synthetic-v2`，重算全部校验和并新增 registry 条目。
