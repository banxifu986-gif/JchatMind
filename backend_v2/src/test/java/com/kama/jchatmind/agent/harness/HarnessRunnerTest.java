package com.kama.jchatmind.agent.harness;

import com.kama.jchatmind.agent.harness.approval.InMemoryApprovalStore;
import com.kama.jchatmind.agent.harness.audit.InMemoryAuditStore;
import com.kama.jchatmind.agent.harness.circuit.InMemoryCircuitBreakerRegistry;
import com.kama.jchatmind.agent.harness.interceptor.AuditTrailInterceptor;
import com.kama.jchatmind.agent.harness.interceptor.CircuitBreakerInterceptor;
import com.kama.jchatmind.agent.harness.interceptor.HarnessInterceptor;
import com.kama.jchatmind.agent.harness.interceptor.HarnessInterceptorChain;
import com.kama.jchatmind.agent.harness.interceptor.HumanApprovalInterceptor;
import com.kama.jchatmind.webhook.WebhookApprovalOutboxService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class HarnessRunnerTest {

    @Test
    void shouldGroupSameToolIntoSingleApprovalRequest() {
        HarnessRunner runner = newHarnessRunner();
        List<AssistantMessage.ToolCall> toolCalls = List.of(
                new AssistantMessage.ToolCall("call-1", "function", "sendEmail", "{\"to\":\"a\"}"),
                new AssistantMessage.ToolCall("call-2", "function", "sendEmail", "{\"to\":\"b\"}")
        );

        HarnessResult result = runner.beforeExecution(
                "user-1", "agent-1", "session-1", 1, toolCalls, null, null, null, null);

        assertTrue(result.hasPendingApprovals());
        String firstRequestId = result.getDecision("call-1").getApprovalRequestId();
        String secondRequestId = result.getDecision("call-2").getApprovalRequestId();
        assertNotNull(firstRequestId);
        assertEquals(firstRequestId, secondRequestId);
        assertEquals(1, runner.getPendingApprovals("session-1").size());
    }

    @Test
    void shouldRejectToolOutsideCurrentTurnAllowlistBeforeApproval() {
        HarnessRunner runner = newHarnessRunner();

        HarnessResult result = runner.beforeExecution(
                "user-1",
                "agent-1",
                "session-1",
                1,
                List.of(new AssistantMessage.ToolCall("call-1", "function", "sendEmail", "{}")),
                Set.of("KnowledgeTool"),
                null,
                null,
                null
        );

        assertEquals(HarnessDecision.Status.REJECTED, result.getDecision("call-1").getStatus());
        assertTrue(result.getDecision("call-1").getMessage().contains("TOOL_NOT_ALLOWED"));
        assertTrue(runner.getPendingApprovals("session-1").isEmpty());
    }

    @Test
    void shouldRejectDuplicateToolCallBeforeApproval() {
        HarnessRunner runner = newHarnessRunner();

        HarnessResult result = runner.beforeExecution(
                "user-1",
                "agent-1",
                "session-1",
                1,
                List.of(
                        new AssistantMessage.ToolCall("call-1", "function", "sendEmail", "{}"),
                        new AssistantMessage.ToolCall("call-2", "function", "sendEmail", "{}")
                ),
                Set.of("sendEmail"),
                Set.of("call-2"),
                null,
                null
        );

        assertEquals(HarnessDecision.Status.PENDING_APPROVAL, result.getDecision("call-1").getStatus());
        assertEquals(HarnessDecision.Status.REJECTED, result.getDecision("call-2").getStatus());
        assertTrue(result.getDecision("call-2").getMessage().contains("TOOL_DUPLICATE"));
    }

    @Test
    void shouldConvertRejectedApprovalAfterAwait() {
        HarnessRunner runner = newHarnessRunner();
        List<AssistantMessage.ToolCall> toolCalls = List.of(
                new AssistantMessage.ToolCall("call-1", "function", "sendEmail", "{\"to\":\"a\"}")
        );

        HarnessResult result = runner.beforeExecution(
                "user-1", "agent-1", "session-1", 1, toolCalls, null, null, null, null);
        String requestId = result.getDecision("call-1").getApprovalRequestId();
        runner.reject(requestId);

        runner.awaitApprovals(result);

        assertEquals(HarnessDecision.Status.REJECTED, result.getDecision("call-1").getStatus());
    }

    @Test
    void shouldExpirePendingApprovalAfterTimeout() {
        HarnessRunner runner = newHarnessRunner(0);
        HarnessResult result = runner.beforeExecution(
                "user-1",
                "agent-1",
                "session-1",
                1,
                List.of(new AssistantMessage.ToolCall("call-1", "function", "sendEmail", "{\"to\":\"a\"}")),
                null,
                null,
                null,
                null
        );

        runner.awaitApprovals(result);

        assertEquals(HarnessDecision.Status.EXPIRED, result.getDecision("call-1").getStatus());
        assertTrue(runner.getPendingApprovals("session-1").isEmpty());
    }

    private HarnessRunner newHarnessRunner() {
        return newHarnessRunner(30);
    }

    private HarnessRunner newHarnessRunner(int timeoutSeconds) {
        HarnessProperties properties = defaultProperties();
        properties.getHumanApproval().setTimeoutSeconds(timeoutSeconds);
        InMemoryApprovalStore approvalStore = new InMemoryApprovalStore();
        InMemoryAuditStore auditStore = new InMemoryAuditStore(properties);
        InMemoryCircuitBreakerRegistry circuitRegistry = new InMemoryCircuitBreakerRegistry(properties);
        HumanApprovalInterceptor approvalInterceptor = new HumanApprovalInterceptor(
                properties, approvalStore, mock(WebhookApprovalOutboxService.class));
        return new HarnessRunner(properties, approvalInterceptor, approvalStore, auditStore);
    }

    private HarnessProperties defaultProperties() {
        HarnessProperties properties = new HarnessProperties();
        properties.getHumanApproval().setEnabled(true);
        properties.getHumanApproval().setTools(List.of("sendEmail", "databaseQuery"));
        properties.getHumanApproval().setTimeoutSeconds(30);
        properties.getCircuitBreaker().setEnabled(true);
        properties.getCircuitBreaker().setTools(List.of("sendEmail", "databaseQuery"));
        properties.getCircuitBreaker().setFailureThreshold(3);
        properties.getCircuitBreaker().setRecoveryTimeoutSeconds(30);
        properties.getAudit().setEnabled(true);
        properties.getAudit().setMaxRecordsPerSession(100);
        return properties;
    }
}
