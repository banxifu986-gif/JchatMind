package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.mapper.ToolMetricsMapper;
import com.kama.jchatmind.service.ToolMetricsService;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@AllArgsConstructor
public class ToolMetricsServiceImpl implements ToolMetricsService {

    private final ToolMetricsMapper toolMetricsMapper;

    @Override
    public List<ToolFrequency> toolInvocationFrequency() {
        return toolMetricsMapper.toolInvocationFrequency().stream()
                .map(row -> new ToolFrequency(
                        (String) row.get("tool_name"),
                        ((Number) row.get("invocation_count")).longValue()
                ))
                .toList();
    }

    @Override
    public List<SessionSteps> stepsPerSession() {
        return toolMetricsMapper.stepsPerSession().stream()
                .map(row -> new SessionSteps(
                        (String) row.get("session_id"),
                        ((Number) row.get("tool_steps")).intValue()
                ))
                .toList();
    }

    @Override
    public TerminateRate terminateCallRate() {
        Map<String, Object> row = toolMetricsMapper.terminateCallRate();
        return new TerminateRate(
                ((Number) row.get("sessions_with_terminate")).longValue(),
                ((Number) row.get("total_sessions")).longValue(),
                ((Number) row.get("terminate_rate_percent")).doubleValue()
        );
    }

    @Override
    public SuccessRate toolCallSuccessRate() {
        Map<String, Object> row = toolMetricsMapper.toolCallSuccessRate();
        return new SuccessRate(
                ((Number) row.get("total_tool_calls")).longValue(),
                ((Number) row.get("successful_tool_responses")).longValue(),
                ((Number) row.get("success_rate_percent")).doubleValue()
        );
    }

    @Override
    public StepsStats averageStepsRecent() {
        Map<String, Object> row = toolMetricsMapper.averageStepsRecent();
        return new StepsStats(
                ((Number) row.get("avg_steps")).doubleValue(),
                ((Number) row.get("max_steps")).intValue(),
                ((Number) row.get("min_steps")).intValue(),
                ((Number) row.get("session_count")).intValue()
        );
    }
}
