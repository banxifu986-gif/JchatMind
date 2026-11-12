# 契约: 答案级 gold（answer-gold/kb-synthetic-v1-answer-gold.jsonl，180 行）

JSONL，每行一个对象。**不进 manifest sha256 链**（manifest v1 无字段位、loader 为 strict record）——改由 registry 登记 sha256 + 新 runner 启动时自校验。此治理降级在数据集 README 明示。

格式复用 `datasets/ecommerce-small-appliances` 的 `gold_answers.jsonl`（`scripts/evaluation.py:68-84`）：

```jsonc
{
  "caseId": "kb-syn-0137",                          // 与 cases.jsonl 一致（存在性校验）
  "decision": "answer",                             // answer | clarify | refuse | handoff
  "reference_answer": "令牌有效期 30 分钟，过期后需用 refresh_token 换取新令牌…",
  "required_facts": ["令牌有效期 30 分钟", "refresh_token 单次有效"],
  "citations": [                                    // refuse 类必须为空数组
    {
      "document_id": "techdoc-07",                  // #章节 定位到 contentPath
      "section": "技术文档>接口鉴权>令牌刷新",
      "supporting_text": "令牌有效期 30 分钟，刷新后旧令牌立即失效"   // 逐字来自原文
    }
  ],
  "forbidden_behaviors": ["编造未在语料中出现的时效数字"],
  "grading": { "mode": "required_facts_covered", "threshold": 1.0 }
}
```

## 抽样规则（180 条）

| 层 | 数量 | 说明 |
|---|---|---|
| no_answer | 40 | decision=refuse，citations 空 |
| cross_document | 40 | 多引用 |
| multi-turn（FAQ/手册） | 30 | clarify 决策为主 |
| 含精确数字事实的 medium/hard | 60 | 数字逐字核验 |
| 英文（`_en`） | 30 | 观测英文端到端质量 |
| 补齐 | 至 180 | difficulty 偏 medium/hard |

## 校验规则

1. caseId 必存在于 cases.jsonl，且一条 case 至多一条 gold
2. `supporting_text`（空白归一化后）必为对应语料 section 原文子串
3. refuse/handoff 类 citations 为空；answer 类 citations 非空且 document_id 在语料清单内
4. 文件整体 SHA-256 登记 registry artifacts；runner 启动时重算比对
