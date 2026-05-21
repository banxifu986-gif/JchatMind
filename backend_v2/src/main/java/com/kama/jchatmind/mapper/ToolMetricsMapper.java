package com.kama.jchatmind.mapper;

import org.apache.ibatis.annotations.Mapper;

import java.util.List;
import java.util.Map;

@Mapper
public interface ToolMetricsMapper {

    /** 各工具调用频率分布（按 tool 角色消息中的 metadata->>'toolResponse.name' 聚合） */
    List<Map<String, Object>> toolInvocationFrequency();

    /** 单次会话平均工具调用步数 */
    List<Map<String, Object>> stepsPerSession();

    /** terminate 工具调用率 */
    Map<String, Object> terminateCallRate();

    /** 工具调用成功率（有 assistant toolCalls 且对应的 tool response 存在） */
    Map<String, Object> toolCallSuccessRate();

    /** 最近 100 次工具调用的平均步数 */
    Map<String, Object> averageStepsRecent();
}
