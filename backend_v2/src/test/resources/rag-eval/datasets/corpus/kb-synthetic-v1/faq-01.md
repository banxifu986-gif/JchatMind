# VPN 使用 FAQ

本文档为企业内部知识库文档，供员工与系统检索使用。

## 注意事项

**Q1：申请 VPN 账号时，需要哪些权限和审批环节？**

星澜工作台的 VPN 账号由星澜云控制台统一管理。提交申请前，确认你已持有在职员工身份，并填写明确的接入用途与需要访问的系统范围。账号权限申请审批人为直属主管与系统 Owner 双审批，也就是说，仅取得直属主管同意并不代表开通完成，必须等系统 Owner 在流程中也确认通过后，权限才会生效。任一方驳回或流程长时间未处理，均视为未获批准，请勿反复提交相同申请，应先与对应审批人确认信息是否完整。

**Q2：VPN 账号使用有时间限制吗？**

账号权限按申请时填写的用途和期限开通，请以流程中审批通过的起止时间为准。使用期限届满后权限自动失效，需要继续使用的，应在到期前重新发起申请并完成双审批，不要在权限失效后临时找人代开。若岗位或项目发生变化，也请同步更新申请中的访问范围，避免保留与当前工作无关的系统权限。

**Q3：哪些情况属于常见易错点？**

第一，只拿到直属主管同意就认为可以连接，忽略系统 Owner 审批，导致实际登录失败。第二，申请中访问范围填写过于笼统（例如只写“全部系统”），审批人无法判断必要性，容易被退回。第三，把 VPN 与办公网络混用：总部及上海研发中心办公区接入 WiFi 访客网络时，该网络带宽限制为 10Mbps，不适合承担大文件传输或长时间数据同步，需要稳定访问内部系统时请使用 VPN。第四，在公共网络环境下连接 VPN 后仍随意访问外部链接，或把账号借给同事使用，这既违反账号实名使用要求，也会影响故障排查。第五，出现连接异常时反复重装客户端，而未先确认自身权限状态是否已生效或已到期。

**Q4：遇到问题应该怎么处理？**

请先自查三项内容：审批流程是否已完成双审批、当前时间是否在权限有效期内、所访问系统是否在申请范围内。自查后仍无法解决的，通过星澜工作台提交支持请求，并附上账号、发生时间与具体报错信息，便于快速定位。

## 解决步骤

按以下顺序逐项排查，每一步都给出预期结果与失败时的检查点。前一步未通过时，不要跳过直接执行下一步。

1. **确认所处网络环境。** 优先使用办公区有线网络或员工专用 SSID 接入，不要使用访客网络。预期结果：可以正常打开星澜工作台登录页，页面加载无超时。失败时的检查点：若页面长时间转圈或频繁断流，先确认是否连接了访客网络；WiFi 访客网络带宽限制为 10Mbps，带宽不足会表现为连接成功但操作卡顿，请切换到员工网络后重试。

2. **核对账号的 VPN 访问权限。** 登录星澜工作台，在权限页面查看本人是否已具备 VPN 访问权限。预期结果：权限状态显示为已开通，且归属部门与实际一致。失败时的检查点：若显示未开通或已停用，需提交权限申请；账号权限申请审批人为直属主管与系统 Owner 双审批，两项审批全部通过后权限才会生效，请勿在审批未完成前反复尝试登录。

3. **安装或更新 VPN 客户端。** 从星澜工作台软件中心获取客户端安装包，不使用来源不明的安装文件。预期结果：安装过程无报错，客户端可正常启动并显示登录界面。失败时的检查点：确认本机操作系统版本受支持、具备管理员安装权限，并关闭与客户端冲突的其他网络代理工具后重新安装。

4. **完成登录认证。** 在客户端中填写工作台公布的连接地址，使用星澜工作台账号登录并按提示完成二次认证。预期结果：客户端提示连接成功，并分配到内网地址。失败时的检查点：核对账号密码是否正确、账号是否因多次输错被锁定、二次认证设备时间是否准确；认证失败请勿连续重试，先排除上述原因。

5. **验证内网资源可达性。** 连接成功后访问内部文档库与星澜云控制台等日常所需资源。预期结果：页面可正常打开，操作无明显延迟。失败时的检查点：若仅个别系统不可访问，多为该系统自身权限未开通，而非 VPN 故障；若全部内网地址均不可达，检查客户端分流设置与本地 DNS 配置。

6. **提交工单并附证据。** 完成上述步骤仍无法连接时，在星澜工作台提交运维工单，说明发生时间、所处网络环境、已执行的步骤序号、客户端错误提示，并附客户端日志。预期结果：工单受理后由运维人员给出处理结论或临时替代访问方案。失败时的检查点：信息不全的工单会被退回补充，提交前请确认日志已成功上传、联系方式可接通。

## 无法解决时

当自助排查与常见问题处理均无法恢复 VPN 连接时，请按以下升级路径处理，并留意相应的响应时效承诺。

**升级路径**

1. 在星澜工作台提交 IT 服务台工单，问题分类选择「网络与 VPN」，并在描述中写明：星澜云控制台或 VPN 客户端显示的完整错误提示、故障首次发生的时间、所在网络环境（办公区有线、办公区无线、家庭宽带或访客网络）、已经尝试过的排查步骤，以及无法接入时的路由跟踪结果。
2. 一线服务台受理后，如果判断需要进一步定位，会将工单转派至网络与信息安全组。如果工单长时间没有得到有效处置，可以直接在原工单中回复「申请升级」并说明理由，由服务台执行升级，无需另开工单，也不要重复提交相同问题。
3. 涉及账号、权限或角色变更的问题，升级后仍需走权限流程：账号权限申请审批人为直属主管与系统 Owner 双审批，两项审批齐全后由系统 Owner 安排处理，服务台不能代为跳过审批环节。
4. 如需在访客网络环境下复现问题，请注意 WiFi 访客网络带宽限制为 10Mbps，据此判断的「速度慢」不作为 VPN 故障升级的依据；此类情况请在工单中单独说明实际使用场景。
5. 确认为区域性故障或影响多人的接入异常时，由网络与信息安全组统一发布进展说明，员工可在星澜工作台公告区查看，无需重复提交工单。

**响应时效承诺**

服务台在受理工单时，会在工单回执中给出本次工单的承诺响应时间，实际时限以回执记录为准；升级至网络与信息安全组后，响应时间同样在工单中更新并可见。对大面积无法接入、已影响业务开展的紧急情况，优先受理并优先升级。请以星澜工作台中的工单状态与回执信息为准，不要通过私人渠道催办，以免遗漏处理记录。

## 问题描述

This section records the typical wording of the user's original question and the scenarios in which it occurs, so that support staff can identify the issue consistently before troubleshooting.

Users usually ask: “Why can’t I connect to the VPN?”, “The VPN client shows a connection failure—what should I do?”, or “The VPN keeps disconnecting while I am working.” Some users also ask whether their account has the required VPN permission, especially after submitting an access request. For account and permission requests, the approval flow requires dual approval by the direct supervisor and the system Owner.

The issue is typically reported when the user is working outside the office, such as from home, during business travel, or while using the guest Wi-Fi network, where the bandwidth is limited to 10Mbps. It may also be raised when a user first attempts to use VPN access on a new device or after a permission request has been submitted and the user is waiting for approval. In these cases, the user expects to access internal resources through the VPN and reports a failure, intermittent disconnection, or inability to complete authentication. Support staff should confirm the user’s actual wording and the scenario above before proceeding to the next section.
