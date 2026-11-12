# 接口鉴权与令牌刷新

本文档为企业内部知识库文档，供员工与系统检索使用。

## 变更流程

本节规定接口鉴权与令牌刷新相关变更（包括鉴权规则调整、令牌有效期与刷新策略修改、密钥轮换、白名单与权限范围变更）的申请、审批与回滚要求。凡涉及生产环境的上述变更，均须走完本节流程后方可实施；未提交变更单的手工操作一律视为违规变更。

**变更申请**

申请人需在星澜工作台提交变更单，并至少填写以下内容：变更范围与涉及的接口清单、变更前后的鉴权或令牌行为差异、影响面评估（含调用方与下游系统）、灰度与分批计划、观察指标与判定标准、回滚预案及回滚责任人。涉及密钥轮换的变更，须说明新旧密钥并存方式与旧密钥的失效时点；涉及令牌有效期调整的，须说明对已有令牌的处理方式。变更单须附测试环境验证记录，未附验证记录的变更单不予受理。

**审批链路**

审批链路依次为：申请人直属主管、服务负责人、安全团队会签（仅在涉及密钥、全局鉴权策略或权限模型变更时触发）。审批流程标准时效为 2 个工作日，加急为 4 小时；申请加急需在变更单中注明理由与业务影响，并经服务负责人确认。审批通过后方可进入发布窗口；变更内容与审批内容不一致时，须重新提交。发布期间应通过星澜云控制台观察鉴权失败率与令牌刷新成功率，配置类变更依赖配置中心推送，配置中心推送延迟 P99 小于 1 秒，可作为判断推送是否完成的依据；若推送或指标异常超出观察阈值，立即停止后续批次。任何变更不得降低核心服务可用性 SLA 为 99.95% 的承诺水平。

**回滚方式**

变更单必须写明可执行的回滚方式，优先级为：功能开关回退、配置版本回退至上一已审批版本、密钥回退至并存期内有效的旧版本、代码版本回滚。回滚由变更单指定的回滚责任人执行，执行后须在变更单中记录回滚时间、回滚范围与回滚后的指标表现，并确认鉴权与令牌刷新恢复正常。回滚完成后，本次变更单关闭；如需再次实施，须重新提交变更申请并重新走审批链路。

## 概述

接口鉴权与令牌刷新是星澜工作台面向内部服务与外部集成方提供的统一凭证管理能力，负责调用方身份识别、访问令牌签发、有效期管理与自动续期。本文件说明该能力的定位、依赖组件与适用范围，帮助接入方在设计与排障阶段快速判断边界。

**服务定位**

该能力位于星澜工作台开放接口的准入层，是所有业务接口调用的前置环节。调用方在完成身份认证后取得访问令牌，凭令牌访问受保护资源；令牌到期前由刷新机制换发新令牌，避免业务中断。鉴权结果与令牌状态由平台统一维护，各业务服务不再自行实现校验逻辑，从而保证权限口径一致、审计记录完整。核心服务可用性 SLA 为 99.95%，接入方在容量规划与重试策略设计中应以此为前提。

**依赖组件**

- 统一身份服务：完成调用方身份核验与令牌签发、吊销。
- 配置中心：下发令牌有效期、刷新窗口与白名单等策略，配置中心推送延迟 P99 小于 1 秒，策略变更可在秒级生效。
- 审批流程：涉及高权限范围或长期有效凭证的申请需走审批，审批流程标准时效为 2 个工作日，加急为 4 小时。
- 星澜云控制台：提供凭证查看、状态查询与自助吊销入口；星澜工作台内可查看调用记录。

**适用场景**

适用于服务端到服务端的接口调用、控制台开放 API 对接、内部系统之间的数据同步，以及联调阶段的临时凭证申请。需要长期有效凭证或扩大授权范围的场景，应先提交申请并按审批时效预留时间；短时任务建议使用临时凭证，任务结束即吊销。若调用方仅需读取公开数据，不在本能力覆盖范围内。

接入前请确认已具备调用方身份标识与回调地址，并明确令牌存放方式；具体接口参数与错误码见后续章节。

## 配置参数

本节列出接口鉴权与令牌刷新在星澜工作台配置中心的可调参数、默认取值与取值范围。参数由平台按租户统一下发，业务方不得在代码中硬编码，也不得在本地覆盖。

| 参数 | 说明 | 默认值 | 取值范围 |
| --- | --- | --- | --- |
| auth.token.ttl | 访问令牌有效期 | 平台默认策略值，随租户套餐下发 | 大于 0，且不得超过刷新令牌有效期 |
| auth.refresh.ttl | 刷新令牌有效期 | 平台默认策略值，随租户套餐下发 | 大于访问令牌有效期，上限由安全基线约束 |
| auth.refresh.rotate | 刷新时是否轮换刷新令牌 | 开启 | 开启 / 关闭（关闭须经安全审批） |
| auth.clock.skew | 令牌校验允许的时钟偏移容差 | 平台默认策略值 | 非负整数秒，上限由安全基线约束 |
| auth.sign.alg | 令牌签名算法 | 平台默认算法 | 仅限安全基线白名单内的算法 |
| config.push.mode | 参数变更下发方式 | 长连接推送 | 长连接推送 / 轮询兜底 |

默认值与取值范围说明：

1. 表中“平台默认策略值”指配置中心按租户套餐下发的初始值，新建租户继承该值；当前生效值可在星澜云控制台的接口鉴权页查看。
2. 取值范围由安全基线统一约束。超出范围的写入会被配置中心直接拒绝并返回校验失败，不会出现部分参数生效的情况。
3. 参数变更经配置中心下发后生效，配置中心推送延迟 P99 小于 1 秒；轮询兜底模式下，生效时间以轮询周期为准。
4. 关闭刷新令牌轮换、放宽时钟偏移容差、变更签名算法等高风险操作须走变更审批：审批流程标准时效为 2 个工作日，加急为 4 小时。
5. 鉴权与配置下发属核心服务，核心服务可用性 SLA 为 99.95%。

调整参数前，请先在测试租户验证令牌签发与刷新链路，再按上述审批路径提交生产变更。

## FAQ

**Q1: Should I refresh the access token proactively, or wait for a 401 response?**

Refresh proactively. Start the refresh when the remaining lifetime of the access token falls below the safety window configured for your client, and serialize refresh calls per session with a single-flight lock. If a request still fails with a 401 caused by an expired access token, refresh once and retry that request once; do not retry in a loop. Concurrent refreshes from the same session can trigger refresh-token rotation and replay detection, which revokes the session and forces re-authentication. Keep access and refresh tokens in memory only, never in URLs or logs.

**Q2: How fast do signing-key rotations propagate, and may I cache the JWKS document?**

Key updates are published to the JWKS endpoint and distributed through the configuration center with a P99 push latency of less than 1 second. Cache the JWKS document with a short, bounded TTL and honor its cache headers. If a token presents an unknown key identifier, refetch the JWKS once and retry verification once; do not refetch per request. During rotation, keep the previous public key available for the overlap period so tokens signed before the change remain verifiable.

**Q3: What is the approval lead time for production credentials, and what availability should the client assume?**

Submit production credential requests through the standard approval process: the standard lead time is 2 business days, and the expedited path is 4 hours. Design clients to tolerate transient failures with bounded exponential backoff and jitter, and avoid synchronized retry storms. The core service availability SLA is 99.95%; monitor authentication failures separately from authorization denials so that availability events and permission errors are not conflated.
