package com.kama.jchatmind.agent.harness.interceptor;

import com.kama.jchatmind.agent.harness.HarnessContext;
import com.kama.jchatmind.agent.harness.HarnessProperties;
import com.kama.jchatmind.agent.harness.HarnessResult;
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
    public void beforeExecution(HarnessContext context, HarnessResult result) {
        if (!harnessProperties.getAudit().isEnabled()) {
            return;
        }
        context.getAttributes().put(ATTRIBUTE_START_NANOS, System.nanoTime());
    }

    @Override
    public void afterExecution(HarnessContext context, String toolResult) {
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
                .toolResult(toolResult)
                .success(true)
                .outcome(ToolCallRecord.Outcome.SUCCESS)
                .durationMs(durationMs(context))
                .timestamp(Instant.now())
                .stepNumber(context.getStepNumber())
                .build());
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
                .build());
    }

    @Override
    public int getOrder() {
        return 200;
    }

    private long durationMs(HarnessContext context) {
        Object startNanos = context.getAttributes().get(ATTRIBUTE_START_NANOS);
        if (!(startNanos instanceof Long nanos)) {
            return 0L;
        }
        return (System.nanoTime() - nanos) / 1_000_000;
    }
}
