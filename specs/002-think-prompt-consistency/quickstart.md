# Quickstart: 主循环提示词一致化验证

按 spec 阶段 S0→S4 的验收顺序给出可执行命令。所有命令在 `backend_v2/` 下执行（Git Bash）。

## 前置条件

- Java 21 + Maven wrapper 可用
- S0/S4 的 judge 部分：现有 answer-quality evaluator 的系统属性门控已开启所需环境变量（API key 走现有配置，wiki/14 记录 judge 走火山端点）；无此环境时该步退化跳过并如实记录

## S0 改动前基线

```bash
# L1 快速回归（零依赖）
./mvnw.cmd test -Dtest=RagFastRegressionEvaluatorTest

# judge 20 条（系统属性门控，按 wiki/14 的入口开启）
# 结果 log 归档到 specs/002-think-prompt-consistency/baseline-l1.log / baseline-judge.log
```

预期：L1 全部通过；judge 报告落盘，记录 faithfulness/relevancy 均值作为基线。

## S1–S3 实施后编译与回归

```bash
./mvnw.cmd compile
./mvnw.cmd test -Dtest=RagFastRegressionEvaluatorTest
```

预期：编译通过；L1 通过（检索指标不受提示词改动影响）。

## S4 改动后对比

```bash
# 全量基线（宪法原则 VII）
./mvnw.cmd test

# judge 20 条同口径重跑，log 归档 after-l1.log / after-judge.log
```

判定口径（spec Clarifications）：
- judge 分数下降 ≤0.05 → 持平通过
- 下降 0.05~0.10 → 重跑一次 judge，取两次较好结果判定
- 下降 >0.10 → 判回退，标红人工评估
- `mvnw.cmd test` 必须全绿（0 失败/errors）

## 手工冒烟（可选）

启动应用后向默认 Agent 提一个知识库可回答的问题与一个知识库外问题，观察：回答引用的标识能在当轮检索结果中找到；不相关时出现明确拒答而非硬编。
