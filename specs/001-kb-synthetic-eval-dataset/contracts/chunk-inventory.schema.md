# 契约: chunk 清单（working/chunk-inventory.jsonl）

JSONL，每行一个 chunk。由 **Java inventory 生成器**（门控测试，解析逻辑复制 `G2CurrentFixtureRuntimeCorpusLoader:82-133`）以真实 `MarkdownParserServiceImpl` 解析 100 份语料产出——是 gold 对齐与 chunk 总量验收的唯一事实源。

```jsonc
{
  "logicalChunkId": "techdoc-07#技术文档>接口鉴权>令牌刷新#0",  // {docId}#{contentPath}#{chunkIndex}
  "sourceDocumentId": "techdoc-07",
  "contentPath": "技术文档>接口鉴权>令牌刷新",            // 解析器产出的标题路径
  "title": "令牌刷新",
  "content": "……全文……"
}
```

## 生成与校验规则

1. 全部 100 份语料必须解析成功（任一失败即构建中断）
2. `logicalChunkId` 全局唯一
3. chunk 总数落在 **500~2500**（2026-11-11 修正：实际分块器合并 <1000 字符相邻 section，100 文档实测 617 chunk）；越界则调整语料章节结构后重新生成，不得手改清单
4. 生成器同时断言：全部可回答 case 的 goldChunkIds 100% 命中清单（Phase B 冒烟 B 的验收项）
5. runner 导入时以清单驱动：logicalChunkId → DB uuid 映射，corpus 文件映射从 manifest corpusFiles 读取（不硬编码）

## 与 embedding 契约的关系

导入时 embedding 输入 = `RagChunkSupport.buildChunkEmbeddingText`（contentPath + "\n" + title + "\n" + content），与 manifest `embeddingInputVersion: title-title-content-v1` 对应。
