# 契约: 评测 case（cases/kb-synthetic-v1.jsonl）

JSONL，每行一个对象，**恰好 15 字段**（对应 strict record `RagEvaluationCase`，多余字段解析即失败）。

## 字段定义

```jsonc
{
  "caseId": "kb-syn-0001",                          // 唯一，建议 kb-syn-XXXX
  "datasetId": "kb-synthetic-v1",                   // 必须等于 manifest.datasetId
  "query": "接口鉴权的令牌多久刷新一次？",            // 非空
  "queryType": "content_rewrite",                   // 见枚举
  "difficulty": "medium",                           // easy/medium/hard
  "conversation": [],                               // follow_up 类必填，结构参照 fixture-fast-v1-007
  "kbScope": ["kb-synthetic-v1"],                   // 非空
  "goldChunkIds": ["techdoc-07#技术文档>接口鉴权>令牌刷新#0"],  // 必在 chunk-inventory
  "goldFacts": ["令牌有效期 30 分钟，刷新后旧令牌立即失效"],     // 空白归一化后为对应 chunk content 子串
  "shouldAbstain": false,                           // 拒答 case 为 true 且 goldChunkIds/goldFacts 为空
  "abstentionReason": null,                         // 拒答 case 必填: out_of_scope | missing_evidence | permission_denied
  "sourceDocumentIds": ["techdoc-07"],              // 与 gold chunk 的 sourceDocumentId 一致
  "labels": [],                                     // no-answer / permission-boundary 等
  "createdBy": "synthetic",                         // 人工修改过 → manual
  "reviewStatus": "approved"                        // 冻结前全部 approved（loader:78 硬校验）
}
```

## queryType 枚举

| 值 | 目标占比（2000 条） | 说明 |
|---|---|---|
| title_path / title_to_content | 合计 20%（400） | 标题/路径类 |
| content_rewrite / user_like_question | 合计 30%（600） | 内容改写与口语提问 |
| follow_up_contextual_rewrite | 20%（400） | 多轮，conversation 必填 |
| cross_document | 15%（300） | 2~3 个 goldChunkIds |
| no_answer | 10%（200） | shouldAbstain=true |
| permission_boundary | 5%（100） | 越权问法，shouldAbstain=true |
| title_path_en / content_rewrite_en / cross_document_en | 合计 ~8%（~160），从对应中文类的配额中划出 | 英文维度，query 为英文 |

## 硬校验规则（loader `validateCases:69-91` + 本数据集自动校验）

1. `reviewStatus == "approved"`、caseId 全局唯一、query/queryType/kbScope 非空
2. 可回答 case（shouldAbstain=false）必有非空 goldChunkIds；拒答 case 必无 gold
3. goldChunkId 必在 chunk-inventory；goldFacts 证据串（去空白后）必为对应 chunk content 子串
4. sourceDocumentIds 与 gold chunk 的 sourceDocumentId 一致
5. 去重：query 归一化（去标点/全半角）后 4-gram Jaccard ≥0.7 剔除；与 fixture-fast-v1 现有 20 条跨集查重

## 参照实例

`backend_v2/src/test/resources/rag-eval/datasets/cases/fixture-fast-v1.jsonl`（现有 20 条，字段契约同源）。
