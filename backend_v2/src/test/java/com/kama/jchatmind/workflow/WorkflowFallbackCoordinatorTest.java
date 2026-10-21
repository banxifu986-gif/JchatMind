package com.kama.jchatmind.workflow;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowFallbackCoordinatorTest {

    @Test
    void shouldRunSingleAgentFallbackWhenPlannerRejectsBeforeExecution() {
        WorkflowPlanner planner = mock(WorkflowPlanner.class);
        WorkflowPlanExecutor executor = mock(WorkflowPlanExecutor.class);
        when(planner.plan(anyString(), anyString(), anyList(), anyList()))
                .thenReturn(WorkflowPlanPlanningResult.rejected("工作流计划输出不符合 JSON schema"));
        AtomicInteger fallbackCalls = new AtomicInteger();
        WorkflowFallbackCoordinator coordinator = new WorkflowFallbackCoordinator(planner, executor);

        WorkflowOrchestrationResult result = execute(coordinator, fallbackCalls);

        assertThat(result.mode()).isEqualTo(WorkflowOrchestrationMode.SINGLE_AGENT_FALLBACK);
        assertThat(result.assistantResponse()).isEqualTo("single-agent answer");
        assertThat(fallbackCalls).hasValue(1);
        verify(executor, never()).execute(anyString(), anyString(), anyString(), any(), anyList(), any());
    }

    @Test
    void shouldNotRunFallbackAfterWorkflowStepsHaveStarted() {
        WorkflowPlanner planner = acceptedPlanner();
        WorkflowPlanExecutor executor = mock(WorkflowPlanExecutor.class);
        when(executor.execute(anyString(), anyString(), anyString(), any(), anyList(), any()))
                .thenReturn(WorkflowExecutionResult.halted(List.of(
                        new WorkflowStepExecution("step-1", WorkflowStepExecutionStatus.FAILED, "failed")
                )));
        AtomicInteger fallbackCalls = new AtomicInteger();
        WorkflowFallbackCoordinator coordinator = new WorkflowFallbackCoordinator(planner, executor);

        WorkflowOrchestrationResult result = execute(coordinator, fallbackCalls);

        assertThat(result.mode()).isEqualTo(WorkflowOrchestrationMode.WORKFLOW);
        assertThat(result.workflowExecution().steps()).hasSize(1);
        assertThat(fallbackCalls).hasValue(0);
    }

    @Test
    void shouldFallbackWhenWorkflowPoolRejectsBeforeAnyStepRuns() {
        WorkflowPlanner planner = acceptedPlanner();
        WorkflowPlanExecutor executor = mock(WorkflowPlanExecutor.class);
        when(executor.execute(anyString(), anyString(), anyString(), any(), anyList(), any()))
                .thenReturn(WorkflowExecutionResult.rejected(List.of("工作流执行线程池繁忙")));
        AtomicInteger fallbackCalls = new AtomicInteger();
        WorkflowFallbackCoordinator coordinator = new WorkflowFallbackCoordinator(planner, executor);

        WorkflowOrchestrationResult result = execute(coordinator, fallbackCalls);

        assertThat(result.mode()).isEqualTo(WorkflowOrchestrationMode.SINGLE_AGENT_FALLBACK);
        assertThat(result.assistantResponse()).isEqualTo("single-agent answer");
        assertThat(fallbackCalls).hasValue(1);
    }

    @Test
    void shouldNotRunFallbackAfterWorkflowTimeout() {
        WorkflowPlanner planner = acceptedPlanner();
        WorkflowPlanExecutor executor = mock(WorkflowPlanExecutor.class);
        when(executor.execute(anyString(), anyString(), anyString(), any(), anyList(), any()))
                .thenReturn(WorkflowExecutionResult.timedOut(List.of(), "step-1"));
        AtomicInteger fallbackCalls = new AtomicInteger();
        WorkflowFallbackCoordinator coordinator = new WorkflowFallbackCoordinator(planner, executor);

        WorkflowOrchestrationResult result = execute(coordinator, fallbackCalls);

        assertThat(result.mode()).isEqualTo(WorkflowOrchestrationMode.WORKFLOW);
        assertThat(result.workflowExecution().steps())
                .singleElement()
                .extracting(WorkflowStepExecution::status)
                .isEqualTo(WorkflowStepExecutionStatus.TIMED_OUT);
        assertThat(fallbackCalls).hasValue(0);
    }

    private WorkflowPlanner acceptedPlanner() {
        WorkflowPlanner planner = mock(WorkflowPlanner.class);
        WorkflowPlan plan = new WorkflowPlan(
                1,
                10,
                List.of(new WorkflowStep(
                        "step-1",
                        "KnowledgeTool",
                        "fact-1",
                        "claim-1",
                        List.of(new WorkflowEvidence("chunk-1", "fact-1", "evidence"))
                ))
        );
        when(planner.plan(anyString(), anyString(), anyList(), anyList()))
                .thenReturn(WorkflowPlanPlanningResult.accepted(plan));
        return planner;
    }

    private WorkflowOrchestrationResult execute(
            WorkflowFallbackCoordinator coordinator,
            AtomicInteger fallbackCalls
    ) {
        return coordinator.execute(
                "user-1",
                "agent-1",
                "session-1",
                "test-model",
                "query",
                List.of("KnowledgeTool"),
                List.of(new WorkflowEvidence("chunk-1", "fact-1", "evidence")),
                ignored -> "output",
                () -> {
                    fallbackCalls.incrementAndGet();
                    return "single-agent answer";
                }
        );
    }
}
