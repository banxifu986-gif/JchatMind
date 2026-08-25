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
import java.util.UUID;

@Component
public class WorkflowPlanExecutor {

    private final WorkflowPlanVerifier workflowPlanVerifier;
    private final HarnessRunner harnessRunner;
    private final HarnessInterceptorChain harnessInterceptorChain;
    private final ObjectMapper objectMapper;

    public WorkflowPlanExecutor(
            WorkflowPlanVerifier workflowPlanVerifier,
            HarnessRunner harnessRunner,
            HarnessInterceptorChain harnessInterceptorChain,
            ObjectMapper objectMapper
    ) {
        this.workflowPlanVerifier = workflowPlanVerifier;
        this.harnessRunner = harnessRunner;
        this.harnessInterceptorChain = harnessInterceptorChain;
        this.objectMapper = objectMapper;
    }

    public WorkflowExecutionResult execute(
            String userId,
            String agentId,
            String sessionId,
            WorkflowPlan plan,
            List<String> allowedTools,
            WorkflowToolExecutor workflowToolExecutor
    ) {
        WorkflowVerificationResult verification = workflowPlanVerifier.verify(plan, allowedTools);
        if (!verification.accepted()) {
            return WorkflowExecutionResult.rejected(verification.violations());
        }

        List<WorkflowStepExecution> executions = new ArrayList<>();
        List<WorkflowStep> steps = plan.steps() == null ? List.of() : plan.steps();
        for (int index = 0; index < steps.size(); index++) {
            WorkflowStep step = steps.get(index);
            WorkflowStepExecution execution = executeStep(
                    userId,
                    agentId,
                    sessionId,
                    index + 1,
                    step,
                    workflowToolExecutor
            );
            executions.add(execution);
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
            int stepNumber,
            WorkflowStep step,
            WorkflowToolExecutor workflowToolExecutor
    ) {
        String toolInput = serializeInput(step);
        String toolCallId = UUID.randomUUID().toString();
        HarnessResult harnessResult = harnessRunner.beforeExecution(
                userId,
                agentId,
                sessionId,
                stepNumber,
                List.of(new AssistantMessage.ToolCall(toolCallId, "function", step.toolName(), toolInput))
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
            output = workflowToolExecutor.execute(step);
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

    static WorkflowExecutionResult rejected(List<String> violations) {
        return new WorkflowExecutionResult(false, List.of(), List.copyOf(violations));
    }
}

record WorkflowStepExecution(String stepId, WorkflowStepExecutionStatus status, String output) {
}

enum WorkflowStepExecutionStatus {
    SUCCEEDED,
    BLOCKED,
    FAILED
}
