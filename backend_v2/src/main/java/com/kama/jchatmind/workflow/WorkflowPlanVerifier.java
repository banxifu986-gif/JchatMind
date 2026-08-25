package com.kama.jchatmind.workflow;

import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class WorkflowPlanVerifier {

    private static final int MAX_AGENT_STEPS = 20;
    private static final int MAX_TOOL_TIMEOUT_SECONDS = 30;

    public WorkflowVerificationResult verify(WorkflowPlan plan, List<String> allowedTools) {
        if (plan == null) {
            return WorkflowVerificationResult.rejected("工作流计划不能为空");
        }

        List<String> violations = new ArrayList<>();
        validateBudget(plan, violations);
        Set<String> authorizedTools = normalizeTools(allowedTools);
        Set<String> stepIds = new HashSet<>();
        Map<String, String> claimsByFactKey = new HashMap<>();
        List<WorkflowStep> steps = plan.steps() == null ? List.of() : plan.steps();
        for (WorkflowStep step : steps) {
            validateStep(step, authorizedTools, stepIds, claimsByFactKey, violations);
        }
        return violations.isEmpty()
                ? WorkflowVerificationResult.approved()
                : new WorkflowVerificationResult(false, List.copyOf(violations));
    }

    private void validateBudget(WorkflowPlan plan, List<String> violations) {
        if (plan.maxSteps() < 1) {
            violations.add("工作流步骤预算必须至少为 1");
        } else if (plan.maxSteps() > MAX_AGENT_STEPS) {
            violations.add("工作流步骤预算不能超过 " + MAX_AGENT_STEPS);
        }
        int actualStepCount = plan.steps() == null ? 0 : plan.steps().size();
        if (actualStepCount > plan.maxSteps()) {
            violations.add("工作流实际步骤数超出预算");
        }
        if (plan.timeoutSeconds() < 1) {
            violations.add("工作流超时预算必须至少为 1 秒");
        } else if (plan.timeoutSeconds() > MAX_TOOL_TIMEOUT_SECONDS) {
            violations.add("工作流超时预算不能超过 " + MAX_TOOL_TIMEOUT_SECONDS + " 秒");
        }
    }

    private void validateStep(
            WorkflowStep step,
            Set<String> authorizedTools,
            Set<String> stepIds,
            Map<String, String> claimsByFactKey,
            List<String> violations
    ) {
        if (step == null) {
            violations.add("工作流不能包含空步骤");
            return;
        }
        String stepId = normalizedText(step.id());
        if (!StringUtils.hasText(stepId)) {
            violations.add("工作流步骤必须包含 id");
            return;
        }
        if (!stepIds.add(stepId)) {
            violations.add("工作流步骤 id 重复：" + stepId);
        }

        String rawToolName = step.toolName();
        String toolName = normalizedText(rawToolName);
        if (StringUtils.hasText(rawToolName) && !rawToolName.equals(toolName)) {
            violations.add("工作流步骤工具名称不能包含首尾空白：" + toolName);
        }
        if (!authorizedTools.contains(toolName)) {
            violations.add("步骤 " + stepId + " 请求了未授权工具：" + toolName);
        }

        String factKey = normalizedText(step.factKey());
        String claim = normalizedText(step.claim());
        if (!StringUtils.hasText(factKey) || !StringUtils.hasText(claim)) {
            violations.add("步骤 " + stepId + " 必须包含事实键和声明");
        } else {
            String existingClaim = claimsByFactKey.putIfAbsent(factKey, claim);
            if (existingClaim != null && !existingClaim.equals(claim)) {
                violations.add("事实 " + factKey + " 存在矛盾声明");
            }
        }

        List<WorkflowEvidence> evidence = step.evidence() == null ? List.of() : step.evidence();
        if (evidence.isEmpty()) {
            violations.add("步骤 " + stepId + " 缺少证据");
            return;
        }
        for (WorkflowEvidence item : evidence) {
            if (item == null
                    || !StringUtils.hasText(normalizedText(item.chunkId()))
                    || !StringUtils.hasText(normalizedText(item.statement()))
                    || !factKey.equals(normalizedText(item.factKey()))) {
                violations.add("步骤 " + stepId + " 包含无效证据");
            }
        }
    }

    private Set<String> normalizeTools(List<String> tools) {
        Set<String> normalized = new HashSet<>();
        if (tools == null) {
            return normalized;
        }
        for (String tool : tools) {
            String normalizedTool = normalizedText(tool);
            if (StringUtils.hasText(normalizedTool)) {
                normalized.add(normalizedTool);
            }
        }
        return normalized;
    }

    private String normalizedText(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }
}

record WorkflowPlan(
        int maxSteps,
        int timeoutSeconds,
        List<WorkflowStep> steps
) {
}

record WorkflowStep(
        String id,
        String toolName,
        String factKey,
        String claim,
        List<WorkflowEvidence> evidence
) {
}

record WorkflowEvidence(
        String chunkId,
        String factKey,
        String statement
) {
}

record WorkflowVerificationResult(
        boolean accepted,
        List<String> violations
) {
    static WorkflowVerificationResult approved() {
        return new WorkflowVerificationResult(true, List.of());
    }

    static WorkflowVerificationResult rejected(String violation) {
        return new WorkflowVerificationResult(false, List.of(violation));
    }
}
