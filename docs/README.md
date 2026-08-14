# JChatMind 文档

## 从这里开始

| 目的 | 首选文档 |
|------|------|
| 理解当前系统设计、主链路和关键代码 | [项目架构与关键链路.md](项目架构与关键链路.md) |
| 了解本地启动、技术栈和目录 | [根 README](../README.md) |
| 分析 RAG 效果、记忆与多 Agent 演进 | [RAG设计与评测分析.md](RAG设计与评测分析.md) |
| 执行或维护 RAG 指标与数据集 | [RAGAS风格指标维护说明.md](RAGAS风格指标维护说明.md) |
| 查找历史故障、基线与决策 | [records/README.md](records/README.md) |

## 文档分类

| 位置 | 内容 | 维护规则 |
|------|------|------|
| 根目录 | 当前架构总览与跨模块专题 | 当前实现变化时同步更新。 |
| [reference/](reference/) | 稳定专题设计与可复用资料 | 不记录一次性排查过程。 |
| [spec/](spec/) | 开发前规格、数据 schema 和验收标准 | 实施范围变化时先更新。 |
| [plans/active/](plans/active/) | 未完成的方案、风险与下一步 | 完成后记录验收并移至 `done/`。 |
| [plans/done/](plans/done/) | 已完成方案的目标和收口信息 | 不替代当前架构总览。 |
| [records/](records/) | 事件、故障、实验基线与复盘 | 按主题和日期新增，不覆盖旧记录。 |

## 稳定参考

### reference/ — 稳定知识

长期有效、需要反复查阅的技术资料。

| 文件 | 说明 |
|------|------|
| [resume-versions.md](reference/resume-versions.md) | 简历项目表述版本记录 |
| [ai/rag-governance.md](reference/ai/rag-governance.md) | RAG 优化收口与推进规范 |
| [RAG评测框架架构设计.md](reference/RAG评测框架架构设计.md) | RAG 三层评测金字塔：标准基准、多维度召回、线上 E2E |
| [登录注册功能架构设计.md](reference/登录注册功能架构设计.md) | 登录注册功能设计 |
| [鉴权架构设计.md](reference/鉴权架构设计.md) | JWT、AOP 和权限边界设计 |
| [Redis邮箱验证码架构设计.md](reference/Redis邮箱验证码架构设计.md) | 验证码限流与异步邮件设计 |
| [排行榜功能架构设计.md](reference/排行榜功能架构设计.md) | 排行榜功能设计 |

### 根目录专题分析

| 文件 | 说明 |
|------|------|
| [项目架构与关键链路.md](项目架构与关键链路.md) | 当前系统架构、运行链路、关键代码索引、边界和维护规则 |
| [RAG设计与评测分析.md](RAG设计与评测分析.md) | 当前 RAG 设计合理性、通用评测框架、量化指标、记忆系统与多 Agent 演进建议 |
| [RAGAS风格指标维护说明.md](RAGAS风格指标维护说明.md) | RAGAS 风格指标实现、L1 快速回归与报告口径 |

### spec/ — 实施规格

| 文件 | 说明 |
|------|------|
| [RAGAS风格指标补充Spec.md](spec/RAGAS风格指标补充Spec.md) | RAGAS 风格上下文与答案指标的范围、定义、配置、报告结构和验收标准 |

### plans/ — 执行计划

| 子目录 | 说明 |
|--------|------|
| [active/](plans/active/) | 规划中：MCP 双向集成、下一阶段系统路线图、RAG 优化路线图、用户记忆系统 |
| [done/](plans/done/) | 已完成：Agent Harness、Query Rewrite、多路召回、工具调用改进 |

| 文件 | 说明 |
|------|------|
| [rag-evaluation-dataset-governance-plan.md](plans/active/rag-evaluation-dataset-governance-plan.md) | RAG 评测分层、数据集 manifest、样本与 Gold 规范、数据切分和防污染计划 |
| [regression-v1-candidate-review.md](plans/active/regression-v1-candidate-review.md) | regression-v1 候选集审核、来源追溯与冻结条件 |
| [memory-system-improvement.md](plans/active/memory-system-improvement.md) | 用户记忆系统改进方向 |
| [mcp.md](plans/active/mcp.md) | MCP 双向集成方案 |
| [rag-optimization-roadmap.md](plans/active/rag-optimization-roadmap.md) | RAG 优化路线图 |
| [next-phase-system-roadmap-2026-05-25.md](plans/active/next-phase-system-roadmap-2026-05-25.md) | 系统阶段路线图 |
| [trusted-knowledge-agent-roadmap.md](plans/active/trusted-knowledge-agent-roadmap.md) | 可信研发知识协作 Agent 总计划：数据、任务、Skill、动态 RAG、记忆、多 Agent 与并发治理 |

### records/ — 事件记录

重大 bug 修复与阶段性收口，按主题分目录。

→ [records/README.md](records/README.md) 完整索引

| 主题 | 说明 |
|------|------|
| rag/ | RAG 检索链路：基线、评测、多路召回、收口闭环 |
| user-memory/ | 用户记忆系统：根因修复、降级收口、告警抑制 |
| auth/ | 鉴权体系引入记录 |
| startup/ | 启动问题：循环依赖修复 |
| sse/ | SSE 推送：超时与异常处理器修复 |

### 其他

- [测试清单.md](测试清单.md) — 后端测试覆盖清单（2026-05-25 生成）

## 更新约定

新增接口、主链路、外部依赖、RAG 索引口径或记忆 schema 时，先更新 [项目架构与关键链路.md](项目架构与关键链路.md)，再按性质补充 `reference`、`spec`、`plans` 或 `records`。不要删除旧 `records` 来“保持整洁”；历史结论应通过新记录修正或失效标注。
