# RAG 检索流程详解

## 什么是 RAG？

RAG（Retrieval-Augmented Generation）= 检索 + 生成

核心思想：**让 AI 在回答问题前，先从知识库检索相关信息**，而不是仅靠模型自己的知识。

---

## 核心组件

| 组件 | 作用 |
|------|------|
| `KnowledgeTools` | Agent 调用 RAG 的工具入口 |
| `RagService` | RAG 业务逻辑（Embedding + 检索） |
| `ChunkBgeM3Mapper` | 数据库操作 |
| `pgvector` | 向量存储和相似度计算 |
| `Ollama + bge-m3` | Embedding 模型 |

---

## 完整检索流程

```
用户查询 → Embedding → 向量数据库检索 → 返回结果
```

---

## 第一步：Agent 调用 KnowledgeTool

```java
// KnowledgeTools.java
@org.springframework.ai.tool.annotation.Tool(
    name = "KnowledgeTool",
    description = "从指定知识库中执行相似性检索（RAG）"
)
public String knowledgeQuery(String kbsId, String query) {
    // 调用 RAG 服务检索
    List<String> strings = ragService.similaritySearch(kbsId, query);
    return String.join("\n", strings);
}
```

---

## 第二步：RagService 生成 Embedding

```java
// RagServiceImpl.java

// 1. 调用本地 Ollama 的 bge-m3 模型生成向量
private float[] doEmbed(String text) {
    EmbeddingResponse resp = webClient.post()
        .uri("/api/embeddings")
        .bodyValue(Map.of(
            "model", "bge-m3",
            "prompt", text
        ))
        .retrieve()
        .bodyToMono(EmbeddingResponse.class)
        .block();
    return resp.getEmbedding();
}

// 2. 对外暴露的 embed 方法
public float[] embed(String text) {
    return doEmbed(text);
}
```

**作用**：把用户查询文本转换成向量表示

---

## 第三步：向量相似度检索

```java
// RagServiceImpl.java
public List<String> similaritySearch(String kbId, String title) {
    // 1. 生成查询向量
    String queryEmbedding = toPgVector(doEmbed(title));

    // 2. 从 pgvector 检索最相似的 3 条
    List<ChunkBgeM3> chunks = chunkBgeM3Mapper.similaritySearch(kbId, queryEmbedding, 3);

    // 3. 返回内容列表
    return chunks.stream().map(ChunkBgeM3::getContent).toList();
}

// 3. 转换为 pgvector 格式 [0.123, -0.456, ...]
private String toPgVector(float[] v) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < v.length; i++) {
        sb.append(v[i]);
        if (i < v.length - 1) sb.append(",");
    }
    sb.append("]");
    return sb.toString();
}
```

---

## 第四步：SQL 相似度检索

```xml
<!-- ChunkBgeM3Mapper.xml -->
<select id="similaritySearch" resultMap="BaseResultMap">
    SELECT id, kb_id, doc_id, content, metadata, embedding, created_at, updated_at
    FROM chunk_bge_m3
    WHERE kb_id = CAST(#{kbId} AS uuid)
    ORDER BY embedding <-> #{vectorLiteral}::vector  <!-- 欧氏距离排序 -->
    LIMIT #{limit}                                    <!-- 取最相似的 N 条 -->
</select>
```

**`<->` 是 pgvector 的欧氏距离操作符**，距离越小越相似。

---

## 流程图解

```
用户问："如何配置 Spring Boot？"
      │
      ▼
┌─────────────────────────────────────┐
│ KnowledgeTool.knowledgeQuery()      │
│   kbsId = "知识库A"                  │
│   query = "如何配置 Spring Boot？"   │
└─────────────────────────────────────┘
      │
      ▼
┌─────────────────────────────────────┐
│ RagService.doEmbed()                │
│   调用 Ollama bge-m3 模型            │
│   → 生成 1024 维向量                 │
└─────────────────────────────────────┘
      │
      ▼
┌─────────────────────────────────────┐
│ SQL: SELECT ...                     │
│   WHERE kb_id = '知识库A'             │
│   ORDER BY embedding <-> query_vec  │
│   LIMIT 3                           │
│   → 返回最相似的 3 个 chunk           │
└─────────────────────────────────────┘
      │
      ▼
┌─────────────────────────────────────┐
│ 返回内容列表给 Agent                 │
│ ["配置步骤1...", "配置步骤2...", "..."]│
└─────────────────────────────────────┘
```

---

## 数据表结构

```sql
CREATE TABLE chunk_bge_m3 (
    id          UUID PRIMARY KEY,
    kb_id       UUID,           -- 知识库 ID
    doc_id      UUID,           -- 文档 ID
    content     TEXT,            -- 文本内容（被分割的 chunk）
    metadata    JSON,            -- 元数据
    embedding   vector(1024),    -- bge-m3 生成的向量
    created_at  TIMESTAMP,
    updated_at  TIMESTAMP
);
```

---

## RAG 在 Agent 中的作用

```
用户: "帮我查下公司有哪些规章制度？"
  │
  ▼
Agent Think: 需要查询知识库
  │
  ▼
Execute: 调用 KnowledgeTool(kbId="制度库", query="规章制度")
  │
  ▼
RAG 检索: 返回相关制度文档片段
  │
  ▼
Agent Think: 有上下文了，直接整理回答
  │
  ▼
返回: "根据知识库，公司有以下规章制度..."
```

---

## 总结

| 步骤 | 操作 | 技术 |
|------|------|------|
| 1 | 查询文本 → 向量 | Ollama + bge-m3 |
| 2 | 向量相似度搜索 | pgvector `<->` |
| 3 | 返回 top-K 结果 | SQL LIMIT |

**核心价值**：让 Agent 能够访问外部知识，而不仅依赖模型自身的知识。
