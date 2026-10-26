# JChatMind 文档

当前维护架构、当前计划、实施 Spec 和稳定参考资料。历史方案、完成计划、评测报告、故障记录与专题资料统一保存在 [归档区](archive/2026-08-15/)，不作为当前实现依据。

## 当前文档

| 类型 | 文档 | 维护边界 |
|------|------|----------|
| 架构 | [项目架构与关键链路.md](项目架构与关键链路.md) | 当前实现、主链路、源码索引和已知边界 |
| 当前计划 | [trusted-knowledge-agent-roadmap.md](plans/active/trusted-knowledge-agent-roadmap.md) | 技术能力演进状态与验收；业务场景尚未确定，研发知识协作仅为候选方向 |
| 实施专项计划 | [intent-recognition-enterprise-plan.md](plans/active/intent-recognition-enterprise-plan.md) | 意图分类、路由、风险、评测和验收 |
| Spec | [trusted-knowledge-agent-spec.md](spec/trusted-knowledge-agent-spec.md) | Agent/RAG 等技术能力的实施契约、SDD/TDD 追溯与验收；不代表已确定业务场景 |
| 参考资料 | [JChatMind-简历技术深挖与面试八股.md](reference/JChatMind-简历技术深挖与面试八股.md) | 简历项目表述、源码证据、技术栈八股和当前边界 |

## 使用顺序

1. 先读本索引和 [根 README](../README.md) 了解项目范围与启动方式。
2. 再读架构文档确认当前代码链路和边界。
3. 需要实施改动时，以当前计划确定优先级，以 Spec 确定范围和验收。
4. 准备项目介绍或面试时，查阅[简历技术深挖与面试八股](reference/JChatMind-简历技术深挖与面试八股.md)，并回到源码核对具体口径。
5. 历史结论只从归档区查阅；若仍然有效，先回填架构、计划或 Spec，再引用它。

## 更新规则

- 当前实现、接口、主链路或边界变化：更新架构文档。
- 跨模块目标、阶段、风险或取舍变化：更新当前计划文档。
- 实施范围、数据 schema、配置或验收变化：更新现有 Spec，不新增平行 Spec。
- 已完成或废弃的方案移动到归档区，不在 `plans/active/` 保留第二份当前计划。
- 故障、实验和评测历史只归档保存；持续有效的结论必须同步到当前三份文档。
