package com.kama.jchatmind.agent.harness.interceptor;

import com.kama.jchatmind.agent.harness.HarnessContext;
import com.kama.jchatmind.agent.harness.HarnessConstants;
import com.kama.jchatmind.agent.harness.HarnessDecision;
import com.kama.jchatmind.agent.harness.HarnessProperties;
import com.kama.jchatmind.agent.harness.HarnessResult;
import com.kama.jchatmind.agent.harness.approval.ApprovalRequest;
import com.kama.jchatmind.agent.harness.approval.ApprovalStore;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class HumanApprovalInterceptor implements HarnessInterceptor {

    private final HarnessProperties harnessProperties;
    private final ApprovalStore approvalStore;

    public HumanApprovalInterceptor(HarnessProperties harnessProperties, ApprovalStore approvalStore) {
        this.harnessProperties = harnessProperties;
        this.approvalStore = approvalStore;
    }

    @Override
    public void beforeExecution(HarnessContext context, HarnessResult result) {
        if (!harnessProperties.getHumanApproval().isEnabled()) {
            return;
        }
        HarnessDecision currentDecision = result.getDecision(context.getToolCallId());
        if (currentDecision != null && currentDecision.getStatus() != HarnessDecision.Status.ALLOW) {
            return;
        }
        Set<String> tools = harnessProperties.getHumanApproval().getTools().stream()
                .collect(Collectors.toSet());
        if (!tools.contains(context.getToolName())) {
            return;
        }

        List<HarnessContext> groupedContexts = result.getContextsByToolName(context.getToolName());
        HarnessDecision existingDecision = groupedContexts.stream()
                .map(ctx -> result.getDecision(ctx.getToolCallId()))
                .filter(decision -> decision != null && decision.getStatus() == HarnessDecision.Status.PENDING_APPROVAL)
                .findFirst()
                .orElse(null);
        if (existingDecision != null) {
            context.getAttributes().put(HarnessConstants.ATTRIBUTE_APPROVAL_REQUEST_ID, existingDecision.getApprovalRequestId());
            result.setDecision(context.getToolCallId(), HarnessDecision.pending(existingDecision.getApprovalRequestId()));
            return;
        }

        ApprovalRequest request = approvalStore.createRequest(
                context.getSessionId(),
                context.getToolName(),
                context.getToolInput(),
                groupedContexts.size(),
                harnessProperties.getHumanApproval().getTimeoutSeconds()
        );
        context.getAttributes().put(HarnessConstants.ATTRIBUTE_APPROVAL_REQUEST_ID, request.getId());
        for (HarnessContext groupedContext : groupedContexts) {
            groupedContext.getAttributes().put(HarnessConstants.ATTRIBUTE_APPROVAL_REQUEST_ID, request.getId());
            result.setDecision(groupedContext.getToolCallId(), HarnessDecision.pending(request.getId()));
        }
    }

    @Override
    public void afterExecution(HarnessContext context, String toolResult) {
    }

    @Override
    public void onError(HarnessContext context, Exception exception) {
    }

    @Override
    public int getOrder() {
        return 100;
    }
}
