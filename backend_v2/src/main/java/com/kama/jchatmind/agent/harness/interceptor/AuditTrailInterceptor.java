package com.kama.jchatmind.agent.harness.interceptor;

import com.kama.jchatmind.agent.harness.HarnessContext;
import com.kama.jchatmind.agent.harness.HarnessDecision;
import com.kama.jchatmind.agent.harness.HarnessProperties;
import com.kama.jchatmind.agent.harness.audit.AuditStore;
import com.kama.jchatmind.agent.harness.audit.ToolCallRecord;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
public class AuditTrailInterceptor implements HarnessInterceptor {

    private static final String ATTRIBUTE_START_NANOS = "auditStartNanos";

    private final HarnessProperties harnessProperties;
    private final AuditStore auditStore;

    public AuditTrailInterceptor(HarnessProperties harnessProperties, AuditStore auditStore) {
        this.harnessProperties = harnessProperties;
        this.auditStore = auditStore;
    }

    @Override
    public HarnessDecision beforeExecution(HarnessContext context) {
        if (!harnessProperties.getAudit().isEnabled()) {
            return null;
        }
        context.getAttributes().put(ATTRIBUTE_START_NANOS, System.nanoTime());
        return null;
    }

    @Override
    public String afterExecution(HarnessContext context, String toolResult) {
        if (!harnessProperties.getAudit().isEnabled()) {
            return null;
        }
        auditStore.record(ToolCallRecord.builder()
                .id(UUID.randomUUID().toString())
                .sessionId(context.getSessionId())
                .agentId(context.getAgentId())
                .toolCallId(context.getToolCallId())
                .toolName(context.getToolName())
                .toolInput(context.getToolInput())
                .toolResult(toolResult)
                .success(true)
                .outcome(ToolCallRecord.Outcome.SUCCESS)
                .durationMs(durationMs(context))
                .timestamp(Instant.now())
                .stepNumber(context.getStepNumber())
                .intentDecisionMetadata(context.getIntentDecisionMetadata())
                .build());
        return null;
    }

    @Override
    public void onError(HarnessContext context, Exception exception) {
        if (!harnessProperties.getAudit().isEnabled()) {
            return;
        }
        auditStore.record(ToolCallRecord.builder()
                .id(UUID.randomUUID().toString())
                .sessionId(context.getSessionId())
                .agentId(context.getAgentId())
                .toolCallId(context.getToolCallId())
                .toolName(context.getToolName())
                .toolInput(context.getToolInput())
                .success(false)
                .outcome(ToolCallRecord.Outcome.ERROR)
                .errorMessage(exception.getMessage())
                .durationMs(durationMs(context))
                .timestamp(Instant.now())
                .stepNumber(context.getStepNumber())
                .intentDecisionMetadata(context.getIntentDecisionMetadata())
                .build());
    }

    @Override
    public void onBlocked(HarnessContext context, HarnessDecision decision) {
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
                .durationMs(0L)
                .timestamp(Instant.now())
                .stepNumber(context.getStepNumber())
                .intentDecisionMetadata(context.getIntentDecisionMetadata())
                .build());
    }

    /**
     * order 取链尾：after 阶段后于一切改写器，审计所见即模型最终收到的结果。
     */
    @Override
    public int getOrder() {
        return 9000;
    }

    private long durationMs(HarnessContext context) {
        Object startNanos = context.getAttributes().get(ATTRIBUTE_START_NANOS);
        if (!(startNanos instanceof Long nanos)) {
            return 0L;
        }
        return (System.nanoTime() - nanos) / 1_000_000;
    }
}
