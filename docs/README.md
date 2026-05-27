# JChatMind 文档

## 目录导航

### reference/ — 稳定知识

长期有效、需要反复查阅的技术资料。

| 文件 | 说明 |
|------|------|
| [resume-versions.md](reference/resume-versions.md) | 简历项目表述版本记录 |
| [ai/agent-interview-prep.md](reference/ai/agent-interview-prep.md) | Agent 面试八股，按题目组织项目落点 |
| [ai/rag-governance.md](reference/ai/rag-governance.md) | RAG 优化收口与推进规范 |
| [RAG评测框架架构设计.md](reference/RAG评测框架架构设计.md) | RAG 三层评测金字塔：标准基准、多维度召回、线上 E2E |

### plans/ — 执行计划

| 子目录 | 说明 |
|--------|------|
| [active/](plans/active/) | 规划中：MCP 双向集成、下一阶段系统路线图、RAG 优化路线图、用户记忆系统 |
| [done/](plans/done/) | 已完成：Agent Harness、Query Rewrite、多路召回、工具调用改进 |

### records/ — 事件记录

重大 bug 修复与阶段性收口，按主题分目录。

→ [records/README.md](records/README.md) 完整索引

| 主题 | 说明 |
|------|------|
| rag/ | RAG 检索链路：基线、评测、多路召回、收口闭环 |
| user-memory/ | 用户记忆系统：根因修复、降级收口、告警抑制 |
| startup/ | 启动问题：循环依赖修复 |
| sse/ | SSE 推送：超时与异常处理器修复 |

### 其他

- [测试清单.md](测试清单.md) — 后端测试覆盖清单（2026-05-25 生成）
