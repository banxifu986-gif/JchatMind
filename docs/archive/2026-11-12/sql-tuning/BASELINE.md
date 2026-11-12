# SQL 调优性能基线（改动前）

- 环境：本机 Docker `jchatmind-postgres`（vchord_bm25 镜像），独立沙箱库 `jchatmind_perf`，真实库未动
- 数据量：20 KB / 200 document / 4000 chunk / 5000 chat_session / 6000 chat_message / 25 agent×3 binding / 1000 user_memory
- 口径：psql `\timing`（DB 内部执行耗时，不含 JDBC 往返）；每场景 2 次重复取后一次；EXPLAIN ANALYZE 看计划
- 说明：单机演示规模下所有查询都在毫秒级；N+1 的真实收益主要是省掉应用侧 N 次网络往返（每次约 0.5~1ms），随数据量线性放大

| 场景 | 旧行为 | 新行为 | DB 内耗时对比 |
|---|---|---|---|
| S3 记忆冲突查重（全量 200 行含 embedding） | 8.0~11.1 ms | LIMIT 1 单条 | 0.37 ms（~25×） |
| S4 getDocuments（10 KB 循环） | 10 条合计 ~2.56 ms | 1 条 IN | 0.41 ms（~6×） |
| S5 getAgents 绑定（25 agent 循环） | 25 条合计 ~4.2 ms | 1 条 IN | 0.29 ms（~14×） |
| S6 KB 归属校验（5 id 循环） | 5 条合计 ~0.91 ms | 1 条 IN | 0.14 ms（~6×） |
| S7 标题路径候选（无界） | 4.14 ms | ORDER+LIMIT 500 | 0.83 ms（~5×） |
| S9 删 chunk（select+20×delete） | ~5.4 ms 合计 | 1 条 DELETE | 0.52~0.83 ms（~7×） |
| S1 会话消息（session_id 无索引） | Seq Scan 0.7~1.2 ms | 待建索引复测 | — |
| S2 会话列表（user_id 无索引） | Seq Scan ~0.34 ms | 待建索引复测 | — |
| S3' user_memory(user_id) | Seq Scan | 待建索引复测 | — |
| S8 chunk(doc_id) | 已被 uq(id,doc_id) Bitmap 覆盖（0.16ms） | 待建索引复测 | 边际收益 |
| S10 chunk(kb_id) 过滤 | Seq Scan 0.64 ms | 待建索引复测 | — |

明细：`baseline-before.txt`（97 条语句计时 + 执行计划）。复测脚本同为 `03-measure.sql`，索引应用用 `04-apply-indexes.sql`。
