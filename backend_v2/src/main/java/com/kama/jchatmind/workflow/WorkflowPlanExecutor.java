package com.kama.jchatmind.workflow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.agent.harness.HarnessContext;
import com.kama.jchatmind.agent.harness.HarnessDecision;
import com.kama.jchatmind.agent.harness.HarnessResult;
import com.kama.jchatmind.agent.harness.HarnessRunner;
import com.kama.jchatmind.agent.harness.interceptor.HarnessInterceptorChain;
import com.kama.jchatmind.agent.harness.proxy.HarnessExecutionContextHolder;
import com.kama.jchatmind.agent.harness.proxy.HarnessToolCallbackProxy;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.DefaultToolMetadata;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class WorkflowPlanExecutor {

    private final WorkflowPlanVerifier workflowPlanVerifier;
    private final HarnessRunner harnessRunner;
    private final HarnessInterceptorChain harnessInterceptorChain;
    private final ObjectMapper objectMapper;
    private final Executor workflowTaskExecutor;

    public WorkflowPlanExecutor(
            WorkflowPlanVerifier workflowPlanVerifier,
            HarnessRunner harnessRunner,
            HarnessInterceptorChain harnessInterceptorChain,
            ObjectMapper objectMapper,
            @Qualifier("workflowTaskExecutor") Executor workflowTaskExecutor
    ) {
        this.workflowPlanVerifier = workflowPlanVerifier;
        this.harnessRunner = harnessRunner;
        this.harnessInterceptorChain = harnessInterceptorChain;
        this.objectMapper = objectMapper;
        this.workflowTaskExecutor = workflowTaskExecutor;
    }

    public WorkflowExecutionResult execute(
            String userId,
            String agentId,
            String sessionId,
            WorkflowPlan plan,
            List<String> allowedTools,
            WorkflowToolExecutor workflowToolExecutor
    ) {
        return executeInternal(
                userId,
                agentId,
                sessionId,
                null,
                plan,
                allowedTools,
                workflowToolExecutor
        );
    }

    public WorkflowExecutionResult executeForWebhookSource(
            String userId,
            String agentId,
            String sessionId,
            String webhookSourceId,
            WorkflowPlan plan,
            List<String> allowedTools,
            WorkflowToolExecutor workflowToolExecutor
    ) {
        return executeInternal(
                userId,
                agentId,
                sessionId,
                webhookSourceId,
                plan,
                allowedTools,
                workflowToolExecutor
        );
    }

    private WorkflowExecutionResult executeInternal(
            String userId,
            String agentId,
            String sessionId,
            String webhookSourceId,
            WorkflowPlan plan,
            List<String> allowedTools,
            WorkflowToolExecutor workflowToolExecutor
    ) {
        WorkflowVerificationResult verification = workflowPlanVerifier.verify(plan, allowedTools);
        if (!verification.accepted()) {
            return WorkflowExecutionResult.rejected(verification.violations());
        }

        List<WorkflowStepExecution> executions = new CopyOnWriteArrayList<>();
        AtomicReference<String> runningStepId = new AtomicReference<>();
        FutureTask<WorkflowExecutionResult> execution = new FutureTask<>(() -> executeVerifiedPlan(
                userId,
                agentId,
                sessionId,
                webhookSourceId,
                plan,
                allowedTools,
                workflowToolExecutor,
                executions,
                runningStepId
        ));
        try {
            workflowTaskExecutor.execute(execution);
            return execution.get(plan.timeoutSeconds(), TimeUnit.SECONDS);
        } catch (RejectedExecutionException exception) {
            return WorkflowExecutionResult.rejected(List.of("工作流执行线程池繁忙"));
        } catch (TimeoutException exception) {
            if (!execution.cancel(true)) {
                return completedResult(execution);
            }
            return WorkflowExecutionResult.timedOut(executions, runningStepId.get());
        } catch (InterruptedException exception) {
            execution.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("工作流执行被中断", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("工作流执行失败", cause);
        }
    }

    private WorkflowExecutionResult completedResult(FutureTask<WorkflowExecutionResult> execution) {
        try {
            return execution.get();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("工作流执行被中断", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("工作流执行失败", cause);
        }
    }

    private WorkflowExecutionResult executeVerifiedPlan(
            String userId,
            String agentId,
            String sessionId,
            String webhookSourceId,
            WorkflowPlan plan,
            List<String> allowedTools,
            WorkflowToolExecutor workflowToolExecutor,
            List<WorkflowStepExecution> executions,
            AtomicReference<String> runningStepId
    ) {
        List<WorkflowStep> steps = plan.steps() == null ? List.of() : plan.steps();
        for (int index = 0; index < steps.size(); index++) {
            WorkflowStep step = steps.get(index);
            runningStepId.set(step.id());
            WorkflowStepExecution execution = executeStep(
                    userId,
                    agentId,
                    sessionId,
                    webhookSourceId,
                    index + 1,
                    step,
                    allowedTools,
                    workflowToolExecutor
            );
            executions.add(execution);
            runningStepId.compareAndSet(step.id(), null);
            if (execution.status() != WorkflowStepExecutionStatus.SUCCEEDED) {
                return WorkflowExecutionResult.halted(executions);
            }
        }
        return WorkflowExecutionResult.completed(executions);
    }

    private WorkflowStepExecution executeStep(
            String userId,
            String agentId,
            String sessionId,
            String webhookSourceId,
            int stepNumber,
            WorkflowStep step,
            List<String> allowedTools,
            WorkflowToolExecutor workflowToolExecutor
    ) {
        String toolInput = serializeInput(step);
        String toolCallId = UUID.randomUUID().toString();
        HarnessResult harnessResult = webhookSourceId == null
                ? harnessRunner.beforeExecution(
                        userId,
                        agentId,
                        sessionId,
                        stepNumber,
                        List.of(new AssistantMessage.ToolCall(toolCallId, "function", step.toolName(), toolInput))
                )
                : harnessRunner.beforeExecutionForWebhookSource(
                        userId,
                        agentId,
                        sessionId,
                        stepNumber,
                        List.of(new AssistantMessage.ToolCall(toolCallId, "function", step.toolName(), toolInput)),
                        Set.copyOf(allowedTools),
                        webhookSourceId
                );
        harnessRunner.awaitApprovals(harnessResult);
        HarnessDecision decision = harnessResult.getDecision(toolCallId);
        HarnessContext context = harnessResult.getContext(toolCallId);
        if (decision == null || decision.getStatus() != HarnessDecision.Status.ALLOW) {
            if (context != null && decision != null) {
                harnessRunner.recordSyntheticOutcome(context, decision);
            }
            return new WorkflowStepExecution(
                    step.id(),
                    WorkflowStepExecutionStatus.BLOCKED,
                    decision == null ? "工作流工具未获准执行" : decision.getMessage()
            );
        }
        if (context == null) {
            return new WorkflowStepExecution(step.id(), WorkflowStepExecutionStatus.FAILED, "工作流工具执行上下文缺失");
        }

        WorkflowToolCallback callback = new WorkflowToolCallback(step, workflowToolExecutor);
        HarnessToolCallbackProxy proxy = new HarnessToolCallbackProxy(callback, harnessInterceptorChain);
        HarnessExecutionContextHolder.bind(
                List.of(context),
                new HarnessExecutionContextHolder.BatchMetadata(sessionId, agentId, userId, stepNumber)
        );
        try {
            proxy.call(toolInput);
            return new WorkflowStepExecution(step.id(), WorkflowStepExecutionStatus.SUCCEEDED, callback.output());
        } catch (RuntimeException exception) {
            return new WorkflowStepExecution(step.id(), WorkflowStepExecutionStatus.FAILED, "工作流工具执行失败");
        } finally {
            HarnessExecutionContextHolder.clear();
        }
    }

    private String serializeInput(WorkflowStep step) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("stepId", step.id());
        input.put("factKey", step.factKey());
        input.put("claim", step.claim());
        input.put("evidence", step.evidence());
        try {
            return objectMapper.writeValueAsString(input);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法生成工作流工具输入", exception);
        }
    }

    private static class WorkflowToolCallback implements ToolCallback {

        private final WorkflowStep step;
        private final WorkflowToolExecutor workflowToolExecutor;
        private String output;

        private WorkflowToolCallback(WorkflowStep step, WorkflowToolExecutor workflowToolExecutor) {
            this.step = step;
            this.workflowToolExecutor = workflowToolExecutor;
            this.output = "";
        }

        @Override
        public ToolDefinition getToolDefinition() {
            return DefaultToolDefinition.builder()
                    .name(step.toolName())
                    .description("受限工作流步骤工具")
                    .inputSchema("{}")
                    .build();
        }

        @Override
        public ToolMetadata getToolMetadata() {
            return DefaultToolMetadata.builder().returnDirect(false).build();
        }

        @Override
        public String call(String toolInput) {
            String result = workflowToolExecutor.execute(step);
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("工作流步骤执行线程已中断");
            }
            output = result;
            return output;
        }

        private String output() {
            return output;
        }
    }
}

@FunctionalInterface
interface WorkflowToolExecutor {
    String execute(WorkflowStep step);
}

record WorkflowExecutionResult(
        boolean completed,
        List<WorkflowStepExecution> steps,
        List<String> violations
) {

    static WorkflowExecutionResult completed(List<WorkflowStepExecution> steps) {
        return new WorkflowExecutionResult(true, List.copyOf(steps), List.of());
    }

    static WorkflowExecutionResult halted(List<WorkflowStepExecution> steps) {
        return new WorkflowExecutionResult(false, List.copyOf(steps), List.of());
    }

    static WorkflowExecutionResult timedOut(List<WorkflowStepExecution> steps, String runningStepId) {
        List<WorkflowStepExecution> timedOutSteps = new ArrayList<>(steps);
        if (runningStepId != null && timedOutSteps.stream().noneMatch(step -> step.stepId().equals(runningStepId))) {
            timedOutSteps.add(new WorkflowStepExecution(
                    runningStepId,
                    WorkflowStepExecutionStatus.TIMED_OUT,
                    "工作流执行超时"
            ));
        }
        return new WorkflowExecutionResult(false, List.copyOf(timedOutSteps), List.of("工作流执行超时"));
    }

    static WorkflowExecutionResult rejected(List<String> violations) {
        return new WorkflowExecutionResult(false, List.of(), List.copyOf(violations));
    }
}

record WorkflowStepExecution(String stepId, WorkflowStepExecutionStatus status, String output) {
}

enum WorkflowStepExecutionStatus {
    SUCCEEDED,
    BLOCKED,
    FAILED,
    TIMED_OUT
}
