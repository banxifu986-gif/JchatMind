# G2 RAG Baseline Architecture

## PostgreSQL 原生 BM25 迁移

标题和正文的 BM25 检索必须在 PostgreSQL 内执行，应用层只接收 chunkId、通道内 rank、provenance 和展示字段。原生词法分数不能与 pgvector distance 直接相加。

## JVM 词法候选边界

`selectContentLexicalCandidatesByKbIds` 和 `selectLexicalCandidatesByKbIds` 会把授权知识库内的候选全文拉回 JVM 计算 BM25。迁移完成后，这两个全量候选读取入口不得继续参与正文或标题 BM25。

## HARD 会话上下文

当会话上下文为 HARD 时，kbId、sourceName、sourceType 和规范化 contentPath 过滤必须在数据库词法 Top-N 的 LIMIT 之前执行。范围外的高分 chunk 不能挤掉范围内 gold。

## 受控 Router 与拒答

Router 只能在已授权知识库范围内制定检索计划。没有证据、权限不足或未获外部资料许可时，入口必须拒答或追问，不能调用外部工具或泄露私有来源。

## API 路径与标题通道

`/api/knowledge-bases/{kbId}/documents` 是知识库文档上传接口路径。新的 API 路径和代码标识符不是 follow-up 信号，仍应保留标题检索通道。
