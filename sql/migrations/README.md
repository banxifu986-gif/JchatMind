# JChatMind 数据库发布迁移

`manifest.json` 是唯一的增量迁移顺序来源，`catalog-contract.json` 是迁移拥有对象的 PostgreSQL catalog 对账契约。两者都必须进入发布工件并在发布前核对 Git 提交 SHA；批准基线 schema 不在仓库内，必须由发布系统提供不可变文件和 SHA-256。入口只负责核对发布系统传入的 SHA 与实际文件内容一致，不能替代发布系统的签名、权限和不可变工件存储。

## 显式发布入口

迁移不会随应用启动自动执行。发布窗口中，在 `backend_v2` 目录通过 `MigrationReleaseApplication` 显式运行，并将数据库密码只注入 `--password-env` 指定的进程环境变量：

```powershell
$env:JCHATMIND_DB_PASSWORD = '<由发布系统注入，不要写入仓库或命令日志>'
.\mvnw.cmd -q spring-boot:run `
  '-Dspring-boot.run.main-class=com.kama.jchatmind.migration.MigrationReleaseApplication' `
  '-Dspring-boot.run.arguments=--confirm-schema-release --project-root .. --jdbc-url jdbc:postgresql://<host>:<port>/<database> --username <user> --password-env JCHATMIND_DB_PASSWORD --baseline <approved-baseline.sql> --baseline-sha256 <64位小写SHA-256> --manifest-sha256 <批准的manifest文件SHA-256> --lock-timeout-ms 30000 --approve manual.owner-review --release-id <release-id> --code-revision <commit-or-release-id> --release-record target/migration-release/release.json'
```

入口要求显式 `--confirm-schema-release`，只接受 canonical `sql/migrations/manifest.json`，且 `--catalog`（如传入）必须与 manifest 声明完全一致；迁移完成后在同一 advisory lock 保护下用迁移后 `REPEATABLE READ` 快照读取托管表、列、约束、索引、扩展、函数和触发器。缺失对象、禁留对象、额外托管对象、定义漂移或迁移异常都会以非零状态结束；报告记录 manifest/baseline/catalog SHA、人工批准项、release/code 标识、回退状态和 catalog 差异，但不记录 JDBC 地址、用户名、密码、SQL 正文或基线内容。

发布成功后应保留 `release-record`，并将该报告与发布编号关联。生产数据库不允许通过重复执行 SQL、`IF NOT EXISTS` 或跳过基线核对来掩盖未知/部分漂移；发现漂移时停止发布并人工比对。
