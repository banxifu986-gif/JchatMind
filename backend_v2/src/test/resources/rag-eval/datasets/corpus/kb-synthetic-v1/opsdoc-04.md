# 机房巡检手册

本文档为企业内部知识库文档，供员工与系统检索使用。

## 审核时效

机房巡检操作的审批时效与巡检类型绑定：日常基础巡检按既定清单执行，无需逐次审批，由运维主管按周确认执行记录即可；深度巡检与带电检测类操作须提前 1 个工作日提交变更申请，运维主管在 2 个工作日内完成审批；涉及上下电、线缆插拔等高风险动作的，还须运维经理会签。

应急免审批条件：出现温度、烟感或供电指标触发 P1 告警时，值班工程师可先执行应急处置动作，事后 4 小时内补录操作记录即可，但处置全程须同步在值班群通报；除 P1 告警场景外，任何巡检操作不得先执行后审批。所有审批与补录记录保留期限与变更管理平台一致。

审批驳回的申请由发起人按驳回意见修改后重新提交，重新提交的申请按新申请计算时效；连续两次驳回的，由运维主管组织评审后再定操作方案。审批通过的巡检工单有效期为 3 个工作日，超期未执行的工单自动作废并重新走审批。

## 常见问题

问：巡检中发现空调机组告警但制冷仍正常，是否需要回退当次巡检？
答：不需要回退巡检本身，但须按回滚触发条件登记异常：制冷冗余低于 50% 或机房温度超过 27 摄氏度时，立即升级为事件处理，由值班工程师按预案启动备用机组；回滚步骤为恢复巡检前状态、关闭临时打开的柜门与旁路开关；回滚后验证以温湿度读数回到阈值内为准。

问：巡检记录什么时候归档？
答：基础巡检记录当日归档，深度巡检报告在 3 个工作日内归档，保留策略与平台日志一致。

问：巡检时发现机柜内有未登记的设备怎么办？
答：不得擅自插拔或下电，先拍照登记资产信息，当日上报资产管理负责人核实；确认属于遗留测试设备的，按资产流程补登记或下架，期间该机柜列入重点观察清单。

## 适用范围

指挥者：机房巡检的指挥者为运维主管，负责巡检计划制定、审批签发与升级决策。执行者：日常巡检由值班工程师执行，深度巡检由运维工程师两人一组执行并交叉复核。通报对象：巡检异常的第一通报对象为运维主管；达到事件标准的，按告警升级链条通报——值班工程师 5 分钟内上报运维主管，15 分钟内上报至技术总监；影响业务系统运行的，同时通报受影响服务的负责人。

节假日巡检由值班表轮值执行，指挥者职责由当日值班长代行，升级路径不变。

外包驻场协助人员可参与基础巡检的记录与物料准备工作，但不得独立执行带电检测与上下电类操作；外包人员的巡检记录须经值班工程师复核签字后方可归档。巡检工具与测温仪由运维主管统一登记保管，借用与归还须在工单中留痕。

## 适用场景

触发条件：基础巡检每日 1 次，深度巡检每周 1 次；出现下列任一情形时加插临时巡检——机房温度或湿度越限告警、UPS 切换事件、供配电改造后 24 小时内、重大发布窗口（每周二与周四 20:00-22:00）开始前 2 小时。

影响范围：巡检操作本身不中断业务，但带电检测类动作须确认目标机柜冗余充足；巡检期间发现异常的，异常处置按事件流程单独管理，不与巡检合并执行。

前置检查：确认巡检工单已派发并携带门禁卡与测温仪；核对上一轮巡检遗留问题清单；检查值班群通道畅通，确保升级链条可达。

巡检时段避开发布窗口与批量作业时间，避免与变更操作并发；确需并发的，由运维主管在工单中标注互斥关系并调整执行顺序。巡检结束后当日在工单中回填各项读数与异常说明，不得事后凭记忆补录。

## 操作步骤

The routine inspection consists of numbered steps, each with a verification point. Step 1: check the environmental panel and record temperature and humidity readings; the verification point is that both values stay within the threshold band of 22 to 26 degrees Celsius and 40 to 60 percent relative humidity. Step 2: inspect the UPS units; verify that no alarm is active, the load stays below 70 percent of capacity, and the battery indicator shows a healthy status. Step 3: walk the rack rows; verify that every cabinet door is closed and locked, no unused rack openings are exposed, and the cold aisle blanking panels are in place. Step 4: check the precision air conditioning units; verify that both units are running or that the standby unit is ready, with no water leakage on the floor.

Step 5: review the fire suppression panel; verify that the system is in automatic mode and no zone fault is displayed. Step 6: confirm the CCTV cameras and access control logs are functioning, then sign off the inspection ticket with timestamps. Command outputs and photo evidence are attached to the ticket as placeholders for the record. If any verification point fails, the inspector stops the routine, reports to the on-call lead within 5 minutes, and the anomaly is handled as a separate incident; daily basic inspections are performed once per day and deep inspections once per week.
