# 事件记录

重大 bug 修复与阶段性收口的单篇记录，按主题分目录。

## rag/ — RAG 检索链路

- [rag-baseline.md](rag/rag-baseline.md) — 离线召回基线
- [2026-05-23-eval.md](rag/2026-05-23-eval.md) — 离线召回评测
- [2026-05-24-multi-recall-implementation.md](rag/2026-05-24-multi-recall-implementation.md) — 多路召回实现
- [2026-05-24-closure.md](rag/2026-05-24-closure.md) — 阶段收口闭环
- [2026-05-29-eval-improvements.md](rag/2026-05-29-eval-improvements.md) — 评测框架三项改进：多样性指标、跨文档 Fixture、答案质量评测

关键结论：当前瓶颈在排序层（rerank），不在 query expansion；LLM rewrite 默认关闭，仅规则版到平台期后才考虑默认开启。

## user-memory/ — 用户记忆系统

- [2026-05-25-root-cause-fix.md](user-memory/2026-05-25-root-cause-fix.md) — 记忆表缺列 + embedding 不可达导致聊天链路中断的根因与修复
- [2026-05-25-fallback-reclaim.md](user-memory/2026-05-25-fallback-reclaim.md) — 记忆降级链路收口
- [2026-05-25-schema-warning-suppression.md](user-memory/2026-05-25-schema-warning-suppression.md) — Schema 迁移告警抑制
- [2026-05-25-chat-start-memory-failure.md](user-memory/2026-05-25-chat-start-memory-failure.md) — 新建对话记忆链路失败

关键结论：长期记忆不是聊天主链路的必要条件，召回失败时主链路必须可降级运行。

## startup/ — 启动问题

- [2026-05-25-circular-dependency.md](startup/2026-05-25-circular-dependency.md) — MCP Server + Query Rewrite LLM wiring 叠加导致的循环依赖修复

## sse/ — SSE 推送

- [2026-05-25-timeout-and-handler-fix.md](sse/2026-05-25-timeout-and-handler-fix.md) — SSE 超时后异常处理器类型冲突修复

## auth/ — 鉴权体系

- [2026-05-27-auth-system-implementation.md](auth/2026-05-27-auth-system-implementation.md) — 从零引入完整认证授权系统（JWT + Spring Security + Redis 限流 + RabbitMQ 异步邮件）
