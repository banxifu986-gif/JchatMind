package com.kama.jchatmind.agent.harness;

import com.kama.jchatmind.agent.harness.approval.InMemoryApprovalStore;
import com.kama.jchatmind.agent.harness.audit.InMemoryAuditStore;
import com.kama.jchatmind.agent.harness.circuit.InMemoryCircuitBreakerRegistry;
import com.kama.jchatmind.agent.harness.interceptor.AuditTrailInterceptor;
import com.kama.jchatmind.agent.harness.interceptor.CircuitBreakerInterceptor;
import com.kama.jchatmind.agent.harness.interceptor.HarnessInterceptor;
import com.kama.jchatmind.agent.harness.interceptor.HarnessInterceptorChain;
import com.kama.jchatmind.agent.harness.interceptor.HumanApprovalInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarnessRunnerTest {

    @Test
    void shouldGroupSameToolIntoSingleApprovalRequest() {
        HarnessRunner runner = newHarnessRunner();
        List<AssistantMessage.ToolCall> toolCalls = List.of(
                new AssistantMessage.ToolCall("call-1", "function", "sendEmail", "{\"to\":\"a\"}"),
                new AssistantMessage.ToolCall("call-2", "function", "sendEmail", "{\"to\":\"b\"}")
        );

        HarnessResult result = runner.beforeExecution("user-1", "agent-1", "session-1", 1, toolCalls);

        assertTrue(result.hasPendingApprovals());
        String firstRequestId = result.getDecision("call-1").getApprovalRequestId();
        String secondRequestId = result.getDecision("call-2").getApprovalRequestId();
        assertNotNull(firstRequestId);
        assertEquals(firstRequestId, secondRequestId);
        assertEquals(1, runner.getPendingApprovals("session-1").size());
    }

    @Test
    void shouldBlockConfiguredToolWhenCircuitIsOpen() {
        HarnessProperties properties = defaultProperties();
        InMemoryApprovalStore approvalStore = new InMemoryApprovalStore();
        InMemoryAuditStore auditStore = new InMemoryAuditStore(properties);
        InMemoryCircuitBreakerRegistry circuitRegistry = new InMemoryCircuitBreakerRegistry(properties);
        circuitRegistry.get("databaseQuery").recordFailure();
        circuitRegistry.get("databaseQuery").recordFailure();
        circuitRegistry.get("databaseQuery").recordFailure();

        HarnessInterceptorChain chain = new HarnessInterceptorChain(List.of(
                new CircuitBreakerInterceptor(properties, circuitRegistry),
                new HumanApprovalInterceptor(properties, approvalStore),
                new AuditTrailInterceptor(properties, auditStore)
        ));
        HarnessRunner runner = new HarnessRunner(properties, chain, approvalStore, auditStore);

        HarnessResult result = runner.beforeExecution(
                "user-1",
                "agent-1",
                "session-1",
                1,
                List.of(new AssistantMessage.ToolCall("call-1", "function", "databaseQuery", "{\"sql\":\"SELECT 1\"}"))
        );

        assertEquals(HarnessDecision.Status.CIRCUIT_OPEN, result.getDecision("call-1").getStatus());
    }

    @Test
    void shouldConvertRejectedApprovalAfterAwait() {
        HarnessRunner runner = newHarnessRunner();
        List<AssistantMessage.ToolCall> toolCalls = List.of(
                new AssistantMessage.ToolCall("call-1", "function", "sendEmail", "{\"to\":\"a\"}")
        );

        HarnessResult result = runner.beforeExecution("user-1", "agent-1", "session-1", 1, toolCalls);
        String requestId = result.getDecision("call-1").getApprovalRequestId();
        runner.reject(requestId);

        runner.awaitApprovals(result);

        assertEquals(HarnessDecision.Status.REJECTED, result.getDecision("call-1").getStatus());
    }

    private HarnessRunner newHarnessRunner() {
        HarnessProperties properties = defaultProperties();
        InMemoryApprovalStore approvalStore = new InMemoryApprovalStore();
        InMemoryAuditStore auditStore = new InMemoryAuditStore(properties);
        InMemoryCircuitBreakerRegistry circuitRegistry = new InMemoryCircuitBreakerRegistry(properties);
        List<HarnessInterceptor> interceptors = List.of(
                new CircuitBreakerInterceptor(properties, circuitRegistry),
                new HumanApprovalInterceptor(properties, approvalStore),
                new AuditTrailInterceptor(properties, auditStore)
        );
        HarnessInterceptorChain chain = new HarnessInterceptorChain(interceptors);
        return new HarnessRunner(properties, chain, approvalStore, auditStore);
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
