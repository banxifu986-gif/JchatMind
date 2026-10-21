package com.kama.jchatmind.workflow;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Supplier;

@Component
public class WorkflowFallbackCoordinator {

    private final WorkflowPlanner planner;
    private final WorkflowPlanExecutor executor;

    public WorkflowFallbackCoordinator(WorkflowPlanner planner, WorkflowPlanExecutor executor) {
        this.planner = planner;
        this.executor = executor;
    }

    public WorkflowOrchestrationResult execute(
            String userId,
            String agentId,
            String sessionId,
            String modelKey,
            String userRequest,
            List<String> allowedTools,
            List<WorkflowEvidence> availableEvidence,
            WorkflowToolExecutor workflowToolExecutor,
            Supplier<String> singleAgentFallback
    ) {
        WorkflowPlanPlanningResult planningResult;
        try {
            planningResult = planner.plan(modelKey, userRequest, allowedTools, availableEvidence);
        } catch (RuntimeException exception) {
            return fallback(singleAgentFallback, List.of("工作流规划失败"));
        }
        if (planningResult == null || !planningResult.accepted() || planningResult.plan() == null) {
            List<String> reasons = planningResult == null || planningResult.violations().isEmpty()
                    ? List.of("工作流计划不可用")
                    : planningResult.violations();
            return fallback(singleAgentFallback, reasons);
        }

        WorkflowExecutionResult executionResult = executor.execute(
                userId,
                agentId,
                sessionId,
                planningResult.plan(),
                allowedTools,
                workflowToolExecutor
        );
        if (executionResult != null
                && !executionResult.completed()
                && executionResult.steps().isEmpty()
                && !executionResult.violations().isEmpty()) {
            return fallback(singleAgentFallback, executionResult.violations());
        }
        return WorkflowOrchestrationResult.workflow(executionResult);
    }

    private WorkflowOrchestrationResult fallback(Supplier<String> singleAgentFallback, List<String> reasons) {
        if (singleAgentFallback == null) {
            return WorkflowOrchestrationResult.workflow(WorkflowExecutionResult.rejected(reasons));
        }
        return WorkflowOrchestrationResult.fallback(singleAgentFallback.get(), reasons);
    }
}

enum WorkflowOrchestrationMode {
    WORKFLOW,
    SINGLE_AGENT_FALLBACK
}

record WorkflowOrchestrationResult(
        WorkflowOrchestrationMode mode,
        WorkflowExecutionResult workflowExecution,
        String assistantResponse,
        List<String> fallbackReasons
) {

    static WorkflowOrchestrationResult workflow(WorkflowExecutionResult execution) {
        return new WorkflowOrchestrationResult(WorkflowOrchestrationMode.WORKFLOW, execution, null, List.of());
    }

    static WorkflowOrchestrationResult fallback(String response, List<String> reasons) {
        return new WorkflowOrchestrationResult(
                WorkflowOrchestrationMode.SINGLE_AGENT_FALLBACK,
                null,
                response,
                List.copyOf(reasons)
        );
    }
}
