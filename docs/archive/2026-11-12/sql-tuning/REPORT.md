# SQL 调优前后性能对比报告

- 环境：本机 Docker `jchatmind-postgres`，沙箱库 `jchatmind_perf`（真实库未动）
- 数据量：20 KB / 200 document / 4000 chunk / 5000 chat_session / 6000 chat_message / 1000 user_memory / 25 agent×3 binding
- 口径：psql `\timing`（DB 内部执行耗时，不含 JDBC 网络往返）；同一脚本 `03-measure.sql` 前后各跑一次
- 明细：`baseline-before.txt`（改前）、`after-indexes.txt`（改后）

## 一、模式级优化（代码改动，N+1 → 批量/单条）

| 场景 | 改前行为 | 改后行为 | DB 内耗时（改前 → 改后） | 提升倍数 |
|---|---|---|---|---|
| S3b 记忆冲突查重 `handleConflictUpdate` | 全量拉 200 条 active memory（含 4KB embedding）后 Java 过滤 | `WHERE memory_type=? ... LIMIT 1` 单条 | 11.1 ms → 0.40 ms | ~25× |
| S4 `getDocuments` | 10 个 KB 循环 10 条 SQL | 1 条 `IN` 查询 | 2.56 ms → 0.58 ms | ~5× |
| S5 `getAgents` 绑定 | 25 个 agent 循环 25 条 SQL | 1 条 `IN` + 分组 | 4.2 ms → 0.18 ms | ~20× |
| S6 KB 归属校验 | 5 个 id 循环 5 条 SQL | 复用 `selectByIdBatch` 1 条 | 0.91 ms → 0.09~0.23 ms | ~5× |
| S7 RAG 标题路径候选 | 无界全量拉 KB 候选 | `ORDER BY created_at,id LIMIT 500` | 4.14 ms → 0.35 ms | ~12× |
| S9 删 chunk（删文档/重传） | SELECT 全行（含 embedding）+ 20 条单行 DELETE | 1 条 `DELETE WHERE doc_id` | ~5.4 ms → 0.09~0.38 ms | ~10×以上 |

**应用侧收益**：DB 内耗只是小头；N+1 模式每条 SQL 还有一次 JDBC 网络往返（本机约 0.5~1ms/次，远程库更高）。getAgents 从 25 次往返降到 1 次、getDocuments 10→1、删文档 21→1，实际端到端收益大于表中数字，且随数据量/绑定数线性放大。

## 二、索引级优化（order 250 迁移，5 个 B-tree）

| 索引 | 支撑查询 | 改前计划/耗时 | 改后计划/耗时 |
|---|---|---|---|
| `idx_chat_message_session_id` | 会话历史拉取/Agent 执行轮询（S1） | Seq Scan，0.69~1.24 ms | Bitmap Index Scan，0.42 ms |
| `idx_chat_session_user_id` | 会话列表 `selectByUserId`（S2） | Seq Scan，0.34 ms | Bitmap Index Scan，0.17 ms |
| `idx_user_memory_user_id` | 记忆列表/查重/召回（S3） | Seq Scan，11.1 ms | Index Scan，6.8 ms（剩余为 200 行×4KB embedding 物化，列表接口改后仍带 embedding，属 Tier 2 范围） |
| `idx_chunk_bge_m3_kb_id` | RAG 全部检索通道的 KB 过滤（S7/S10） | Seq Scan，0.64 ms | Bitmap Index Scan，0.20 ms |
| `idx_chunk_bge_m3_doc_id` | 删文档/重传的按 doc 过滤（S8） | 借道 `uq(id,doc_id)` Bitmap，0.38 ms | 专用 Bitmap，0.20 ms |

执行计划验证：改后 5 个索引均被 planner 选中（见 `after-indexes.txt` 中 Bitmap/Index Scan 行）。

## 三、结论

1. 全部 6 处模式级改动 + 5 个索引在沙箱库均验证生效，DB 内耗时下降 5×~25×，N+1 的 JDBC 往返数显著减少
2. 当前单机演示规模下绝对耗时都在毫秒级；这些改动的价值在数据量增长后兑现（无界查询与 N+1 均随行数/关联数线性放大）
3. 已知遗留（Tier 2/3 范围，未做）：user_memory 列表仍带 embedding 大字段、记忆查重可改走 DB 侧 `similaritySearch`、RAG 标题模糊检索无 trgm/GIN 支撑、摄入事务内 HTTP/磁盘 IO

## 五、p50 / p95 / p99 百分位对比（N=300，`05-pct-bench.sh`）

口径：每场景重复 300 次，每次为一轮完整调用（多语句场景累计各语句 `\timing`），改前=无 5 索引、改后=有 5 索引，同一份数据。明细 `pct-before.txt` / `pct-after.txt`。

**模式级（旧模式 vs 新模式，同相位对比，抗抖动）**

| 场景 | p50 改前→改后 | p95 改前→改后 | p99 改前→改后 |
|---|---|---|---|
| 记忆冲突查重（全量→LIMIT 1） | 8.70 ms → 0.13 ms（**66×**） | 18.19 → 0.42 ms（**43×**） | 24.75 → 1.58 ms（**16×**） |
| getDocuments（10 循环→1 IN） | 0.415 → 0.142 ms（**2.9×**） | 0.862 → 0.283 ms（**3.0×**） | 3.52 → 1.89 ms（1.9×） |
| getAgents 绑定（25 循环→1 IN） | 0.639 → 0.141 ms（**4.5×**） | 1.564 → 0.666 ms（**2.3×**） | 3.33 → 3.57 ms（持平=尾部噪声） |
| KB 归属校验（5 循环→1 IN） | 0.196 → 0.056 ms（**3.5×**） | 1.220 → 0.099 ms（**12×**） | 3.65 → 0.23 ms（**16×**） |
| 删 chunk（21 语句→1 DELETE） | 0.705 → 0.128 ms（**5.5×**） | 2.330 → 0.441 ms（**5.3×**） | 5.39 → 5.51 ms（持平=尾部噪声） |
| 标题路径候选（无界→LIMIT 500） | 0.321 → 0.322 ms（持平¹） | — | — |

¹ 本 KB 候选数（40）远小于 500，LIMIT 未触发截断，故无差异；该优化只在候选数 >500 的大 KB 生效（防无界增长）。

**索引级（改前→改后，同一语句）**

| 场景 | p50 | p95 | p99 |
|---|---|---|---|
| 会话消息（idx_chat_message_session_id） | 0.439 → 0.303 ms（**1.4×**） | 1.619 → 0.794 ms（**2.0×**） | 4.30 → 2.47 ms（**1.7×**） |
| 会话列表（idx_chat_session_user_id） | 0.202 → 0.075 ms（**2.7×**） | 0.644 → 0.168 ms（**3.8×**） | 2.16 → 0.93 ms（**2.3×**） |
| RAG KB 过滤（idx_chunk_bge_m3_kb_id） | 0.195 → 0.056 ms（**3.5×**） | 0.450 → 0.153 ms（**2.9×**） | 4.05 → 0.38 ms（**10.6×**） |
| 记忆列表（idx_user_memory_user_id） | 8.70 → 9.16 ms（持平²） | 持平 | 持平 |

² 该查询耗时由 200 行×4KB embedding 的传输/物化主导，索引只优化定位，与 REPORT.md 第二节结论一致。

**读数须知**
- p95/p99 由尾部长尾决定：本机 Docker 环境存在偶发 5-10ms 调度尖峰，亚毫秒场景的 p99（300 样本中第 3 大值）主要反映抖动而非查询本身；同相位的新旧模式对比不受影响（两侧承受相同抖动），跨相位的索引对比在 p99 上要保守解读
- s4_new 改后相位 p95=2.53ms 属同类噪声（document 表无新索引，语句本身未变）
- **N+1 模式对 p95/p99 的真实贡献在 DB 内部口径里看不到**：应用侧每条 SQL 还有 ~0.3-1ms JDBC 往返，25 次循环意味着应用 p95 至少叠加 25×RTT；批量化后该尾部开销降为 1×RTT——对 getAgents 这类接口，应用侧 p95/p99 的实际改善远大于表中数字

## 四、运行方式

```bash
# 复现测量（需 jchatmind-postgres 容器运行）
docker exec jchatmind-postgres psql -U postgres -c "CREATE DATABASE jchatmind_perf;"
docker exec -i jchatmind-postgres psql -U postgres -d jchatmind_perf -f - < docs/archive/2026-11-12/sql-tuning/01-schema.sql
docker exec -i jchatmind-postgres psql -U postgres -d jchatmind_perf -f - < docs/archive/2026-11-12/sql-tuning/02-seed.sql
docker exec -i jchatmind-postgres psql -U postgres -d jchatmind_perf -f - < docs/archive/2026-11-12/sql-tuning/03-measure.sql
docker exec -i jchatmind-postgres psql -U postgres -d jchatmind_perf -f - < docs/archive/2026-11-12/sql-tuning/04-apply-indexes.sql
docker exec -i jchatmind-postgres psql -U postgres -d jchatmind_perf -f - < docs/archive/2026-11-12/sql-tuning/03-measure.sql
```


## 六、迁移确认记录（2026-11-12）

- `MigrationLifecycleRuntimeL2Test`（`-Dmigration.lifecycle.l2=true`，独立容器随机库，不碰真实库）：**5/5 通过**
- 发布证据 `migration-release-l2.json`：status=SUCCEEDED，25 条迁移 clean install 全部 APPLIED（末条 `knowledge-base.create-hot-filter-indexes`），catalog 对账 catalogVerified=true，missing/unexpected/forbidden/mismatch 均为空
- 过程中发现并修复的存量问题（与 order 250 无关，属契约缺口）：
  1. `catalog-contract.json` 缺 `document_asset_chunk` 的 4 个列声明（asset_id/chunk_id/asset_document_id/chunk_document_id），required/allowedColumns 均无 → 已补齐并重算 contract SHA 回写 manifest
  2. `MigrationLifecycleRuntimeL2Test` 三处硬编码计数过期（applied 23→25、upgrade 15→17、ledger 24→26；ledger 含 1 条 baseline 行 order 0 status=APPLIED）→ 已同步
- 本机 dev 库 `jchatmind` 现状：无迁移台账（旧基线直建），已有 `idx_chat_session_user_id`/`idx_user_memory_user_id`，缺另外 3 个索引，且有遗留 `idx_chunk_*` 对象；接入迁移框架需发布系统提供基线快照，未接入前不手工建索引


## 七、dev 库收编迁移框架记录（2026-11-12）

本机 dev 库 `jchatmind`（建立在迁移框架之前、无台账、有遗留漂移）已完成 Flyway-baseline 式收编，发布报告 `release-dev-onboarding.json`（status=SUCCEEDED，catalogVerified=true，四类漂移全空）。步骤与证据：

1. **漂移清理**（经 Ban 批准，全部无数据损失）：DROP 15 张 0 行的 `t_*` 遗留表（另一项目残留）+ 7 个契约外索引（4 个 `idx_chunk_*` 旧 trgm/tsv/ivfflat + `idx_user_memory_candidate_user_id` + 与 order 250 同名的 2 个）。删除前定义留证见 `dev-legacy-index-defs.txt`
2. **追平 order 160**：手工执行 `2026-08-25-create-knowledge-base-deletion-task.sql`（dev 库此前从未建这两张表；属于"应存在但缺失"的历史，非掩盖漂移）
3. **补种台账**：按 `JdbcMigrationStore.ensureLedgerTable` 原样 DDL 建 `jchatmind_schema_migration_ledger`，INSERT baseline 行（`__baseline__`, order 0, baseline sha）+ order 10-230 共 23 条 APPLIED 行，sha256 逐条来自 manifest.json
4. **发布入口收编**：`MigrationReleaseApplication` upgrade 模式（baseline 取自 L2 测试 BASELINE_SQL 提取件 `dev-baseline-local.sql`，sha=dd11d9d2...）：首轮真实应用 order 240 + 250；第二轮幂等复核（appliedMigrationIds=[]，仅对账）。`--approve manual.owner-review` 覆盖 order 110 的种子历史前置
5. **对账收敛**：首轮对账暴露 5 项残余漂移——`pg_trgm` 扩展（遗留 trgm 索引依赖，DROP）+ 4 个原始 schema 合法外键（chat_session_agent_id_fkey / chunk_bge_m3_kb_id_fkey / user_memory_session_id_fkey / user_memory_candidate_session_id_fkey），已加入契约 `allowedConstraints`（与既有 `chunk_bge_m3.doc_id_fkey` 做法一致）并重算 contract SHA 回写 manifest
6. **终态**：台账 26 行（order 0-250）全 APPLIED；5 个索引齐备；`appliedMigrationIds=[]` 幂等确认

**注意事项**：
- dev 库今后的 schema 变更必须走迁移框架（新 order 260+），禁止手工 DDL
- 下次发布窗口对 dev 库跑入口时仍需提供同一 baseline 文件 + sha（归档副本 `dev-baseline-local.sql`，源真值是 `MigrationLifecycleRuntimeL2Test.BASELINE_SQL`）
- 删除的 `idx_chunk_retrievable_title_trgm` 原本可服务现行 trigram 检索（表达式匹配）；dev 规模下影响可忽略，Tier 2 需用契约化迁移补正式 trgm 索引
- 生产/其他环境若存在同类 4 个外键与 pg_trgm，收编时同样处理；契约已容纳


## 八、Tier 2-1 记忆链路去 embedding（2026-11-12）

改动：
1. `selectByUserId` / `selectActiveByUserId` SELECT 裁掉 embedding 列（列表/提取/召回 fallback 均不消费向量）
2. 记忆查重 `hasSemanticDuplicate` 从"全量加载 + Java 余弦"改为 `existsActiveSemanticDuplicate`（SQL `EXISTS` + `<=>` 距离阈值 0.05，**无 ORDER BY 不走 ivfflat，逐行精确计算**，语义与原 Java `1-clamp(sim)` 等价；零向量/NaN 两侧同为不匹配）
3. recall 补充路径的 `embedding == null` 过滤下推为专用查询 `selectActiveWithoutEmbeddingByUserId`（SQL `AND embedding IS NULL` + LIMIT）
4. 删除孤儿 `cosineDistance` / `getConfirmedMemoriesInternal`；删除 Java 余弦边界测试 1 个（极端量级语义随实现移入 pgvector），其余查重测试改为 mapper 桩

性能（perf 沙箱 200 行/用户，N=100）：

| 查询 | p50 | p95 | p99 |
|---|---|---|---|
| 记忆列表（旧，带 embedding） | 10.03 ms | 20.89 ms | 27.89 ms |
| 记忆列表（新，无 embedding） | 0.22 ms（**~45×**） | 1.28 ms | 4.86 ms |
| 查重 EXISTS（新） | 0.62 ms* | 2.46 ms | 4.60 ms |

* 查重计时含测量用的内联随机向量生成子查询（~0.5ms），生产传入字面量时更快；旧查重成本 ≈ 旧列表加载（10ms+）+ Java 余弦。


## 九、Tier 2-2 RAG 标题检索契约化 trgm 索引（2026-11-12，order 260）

改动：迁移 `sql/knowledge-base/2026-11-12-add-title-trgm-indexes.sql`（order 260，transactional）：
- `CREATE EXTENSION pg_trgm`（契约 requiredExtensions 同步）
- `idx_chunk_bge_m3_title_trgm`（GIN，表达式与 mapper 的 normalized_title 逐字符一致）
- `idx_chunk_bge_m3_content_path_trgm`（GIN，contentPath 表达式）

**索引表达式与查询谓词一致性是生效前提**（收编时删除的旧索引正是表达式对不上而失效）；perf 沙箱 EXPLAIN 验证：contains 的子查询别名过滤可被 PG 谓词下推拉平，三族查询均走 BitmapAnd(title/content_path_trgm ∩ kb_id)。

性能（kb-1 扩容至 5000 chunk / 1000 带标题，选择性模式，N=30）：

| 查询 | 无索引 p50 | 有索引 p50 | 提升 |
|---|---|---|---|
| contains（%1-1-5%） | 1.571 ms | 0.240 ms | **6.5×** |
| keywords（%extra-42-%） | 1.574 ms | 0.195 ms | **8×** |
| contentPath 前缀（/doc/extra/42/%） | 1.843 ms | 0.191 ms | **9.6×** |
| trigram similarity() 函数形式 | 5.781 ms | 5.672 ms | **不可索引**（`similarity() >= x` 函数调用不走 GIN；如需索引化需改写为 `%` 操作符 + GUC 阈值，涉语义变更，记为遗留） |

**边界结论**：宽泛模式（命中约 96% 行）下索引无收益（GIN 候选集≈全量）；小 KB（数百 chunk）下 planner 可能仍选 kb_id 扫描（无损害）。索引的收益在"单 KB chunk 数上千且查询模式有选择性"时兑现。

发布记录：dev 库经发布入口 upgrade 应用（applied=[knowledge-base.create-title-trgm-indexes]，对账全空），报告 `release-dev-trgm-260.json`。


## 十、隐蔽坑：Lombok @Data+@Builder 触发 MyBatis 构造器按列序自动映射（2026-11-12）

Tier 2-1 裁剪 `selectActiveByUserId` 的 embedding 列后，迁移 L2（真实 PG）暴露 `NumberFormatException: For input string: "2026-11-12 ..."`（created_at 被向量解析）。根因：

- `UserMemory` 为 `@Data + @Builder`，Lombok 两者同用时**不生成无参构造器**（只有全参构造器）
- MyBatis 找不到无参构造器时回退 **`applyColumnOrderBasedConstructorAutomapping`（按列序 ↔ 构造器参数序逐位对齐）**，resultMap 的属性映射在此路径下不生效
- 旧 13 列 SELECT 与 13 个构造器参数恰好 1:1，静默正确；裁掉 embedding 列后 `created_at` 错位喂给 `embedding`（float[] → PgVectorTypeHandler → Float.parseFloat）
- mock 单测无法发现（不真正映射结果集），只有真实执行环境的 L2 能暴露

修复：`UserMemory` 补 `@NoArgsConstructor + @AllArgsConstructor`（保留 @Builder），MyBatis 回到无参构造器 + resultMap 属性映射，缺列即 null。裁剪查询配套使用专属 `BaseResultMapNoEmbedding`。

**约定**：实体若为 @Data+@Builder 且被 MyBatis resultMap 读取，裁剪/增删 SELECT 列时必须核对列序与构造器参数序，或显式补无参构造器；全量校验必须包含迁移 L2（mock 测试对这类问题零覆盖）。
