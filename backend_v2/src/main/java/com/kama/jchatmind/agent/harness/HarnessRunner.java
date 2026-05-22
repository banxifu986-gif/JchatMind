package com.kama.jchatmind.agent.harness;

import com.kama.jchatmind.agent.harness.approval.ApprovalRequest;
import com.kama.jchatmind.agent.harness.approval.ApprovalStatus;
import com.kama.jchatmind.agent.harness.approval.ApprovalStore;
import com.kama.jchatmind.agent.harness.audit.AuditStore;
import com.kama.jchatmind.agent.harness.audit.ToolCallRecord;
import com.kama.jchatmind.agent.harness.interceptor.HarnessInterceptorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Component
public class HarnessRunner {

    private final HarnessProperties harnessProperties;
    private final HarnessInterceptorChain interceptorChain;
    private final ApprovalStore approvalStore;
    private final AuditStore auditStore;

    public HarnessRunner(
            HarnessProperties harnessProperties,
            HarnessInterceptorChain interceptorChain,
            ApprovalStore approvalStore,
            AuditStore auditStore
    ) {
        this.harnessProperties = harnessProperties;
        this.interceptorChain = interceptorChain;
        this.approvalStore = approvalStore;
        this.auditStore = auditStore;
    }

    public HarnessResult beforeExecution(
            String userId,
            String agentId,
            String sessionId,
            int stepNumber,
            List<AssistantMessage.ToolCall> toolCalls
    ) {
        HarnessResult result = new HarnessResult();
        for (AssistantMessage.ToolCall toolCall : toolCalls) {
            HarnessContext context = HarnessContext.builder()
                    .sessionId(sessionId)
                    .agentId(agentId)
                    .userId(userId)
                    .toolCallId(toolCall.id())
                    .toolName(toolCall.name())
                    .toolInput(toolCall.arguments())
                    .stepNumber(stepNumber)
                    .build();
            result.addContext(context);
        }

        for (List<HarnessContext> contexts : result.getGroupedContexts().values()) {
            for (HarnessContext context : contexts) {
                interceptorChain.executeBefore(context, result);
            }
        }
        return result;
    }

    public HarnessResult awaitApprovals(HarnessResult result) {
        if (!result.hasPendingApprovals()) {
            return result;
        }

        for (HarnessContext context : result.getPendingContexts()) {
            HarnessDecision decision = result.getDecision(context.getToolCallId());
            if (decision == null || decision.getStatus() != HarnessDecision.Status.PENDING_APPROVAL) {
                continue;
            }
            String requestId = decision.getApprovalRequestId();
            ApprovalStatus status = approvalStore.awaitDecision(
                    requestId,
                    harnessProperties.getHumanApproval().getTimeoutSeconds()
            );
            applyApprovalDecision(result, context, requestId, status);
        }
        return result;
    }

    public List<ApprovalRequest> getPendingApprovals(String sessionId) {
        return approvalStore.getPendingBySession(sessionId);
    }

    public ApprovalStatus approve(String requestId) {
        return approvalStore.approve(requestId);
    }

    public ApprovalStatus reject(String requestId) {
        return approvalStore.reject(requestId);
    }

    public ApprovalRequest getApprovalRequest(String requestId) {
        return approvalStore.getRequest(requestId).orElse(null);
    }

    public void recordSyntheticOutcome(HarnessContext context, HarnessDecision decision) {
        if (!harnessProperties.getAudit().isEnabled()) {
            return;
        }
        ToolCallRecord.Outcome outcome = switch (decision.getStatus()) {
            case REJECTED -> ToolCallRecord.Outcome.REJECTED;
            case EXPIRED -> ToolCallRecord.Outcome.EXPIRED;
            case CIRCUIT_OPEN -> ToolCallRecord.Outcome.CIRCUIT_OPEN;
            default -> ToolCallRecord.Outcome.ERROR;
        };
        auditStore.record(ToolCallRecord.builder()
                .id(UUID.randomUUID().toString())
                .sessionId(context.getSessionId())
                .agentId(context.getAgentId())
                .toolCallId(context.getToolCallId())
                .toolName(context.getToolName())
                .toolInput(context.getToolInput())
                .toolResult(decision.getMessage())
                .success(false)
                .outcome(outcome)
                .errorMessage(decision.getMessage())
                .durationMs(0)
                .timestamp(Instant.now())
                .stepNumber(context.getStepNumber())
                .build());
    }

    private void applyApprovalDecision(
            HarnessResult result,
            HarnessContext context,
            String requestId,
            ApprovalStatus status
    ) {
        List<HarnessContext> groupedContexts = result.getContextsByToolName(context.getToolName());
        for (HarnessContext groupedContext : groupedContexts) {
            Object groupedRequestId = groupedContext.getAttributes().get(HarnessConstants.ATTRIBUTE_APPROVAL_REQUEST_ID);
            if (!(groupedRequestId instanceof String value) || !value.equals(requestId)) {
                continue;
            }
            switch (status) {
                case APPROVED -> result.setDecision(groupedContext.getToolCallId(), HarnessDecision.allow());
                case REJECTED -> result.setDecision(
                        groupedContext.getToolCallId(),
                        HarnessDecision.rejected(
                                "[APPROVAL_REJECTED] 工具 %s 执行被用户拒绝".formatted(groupedContext.getToolName())
                        )
                );
                case EXPIRED -> result.setDecision(
                        groupedContext.getToolCallId(),
                        HarnessDecision.expired(
                                "[APPROVAL_EXPIRED] 工具 %s 审批超时，未执行".formatted(groupedContext.getToolName())
                        )
                );
                default -> {
                }
            }
        }
    }
}
