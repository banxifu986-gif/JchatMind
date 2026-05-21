package com.kama.jchatmind.service;

import java.util.List;
import java.util.Map;

public interface ToolMetricsService {

    record ToolFrequency(String toolName, long invocationCount) {}

    record SessionSteps(String sessionId, int toolSteps) {}

    record TerminateRate(long sessionsWithTerminate, long totalSessions, double terminateRatePercent) {}

    record SuccessRate(long totalCalls, long successfulResponses, double successRatePercent) {}

    record StepsStats(double avgSteps, int maxSteps, int minSteps, int sessionCount) {}

    List<ToolFrequency> toolInvocationFrequency();

    List<SessionSteps> stepsPerSession();

    TerminateRate terminateCallRate();

    SuccessRate toolCallSuccessRate();

    StepsStats averageStepsRecent();
}
