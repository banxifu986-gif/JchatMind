package com.kama.jchatmind.agent.harness.interceptor;

import com.kama.jchatmind.agent.harness.HarnessContext;
import com.kama.jchatmind.agent.harness.HarnessDecision;
import com.kama.jchatmind.agent.harness.HarnessProperties;
import com.kama.jchatmind.agent.harness.HarnessResult;
import com.kama.jchatmind.agent.harness.circuit.InMemoryCircuitBreakerRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CircuitBreakerInterceptorTest {

    @Test
    void shouldNotOverrideExistingNonAllowDecision() {
        HarnessProperties properties = new HarnessProperties();
        properties.getCircuitBreaker().setEnabled(true);
        properties.getCircuitBreaker().setTools(List.of("databaseQuery"));

        InMemoryCircuitBreakerRegistry registry = new InMemoryCircuitBreakerRegistry(properties);
        registry.get("databaseQuery").recordFailure();
        registry.get("databaseQuery").recordFailure();
        registry.get("databaseQuery").recordFailure();

        CircuitBreakerInterceptor interceptor = new CircuitBreakerInterceptor(properties, registry);
        HarnessResult result = new HarnessResult();
        HarnessContext context = HarnessContext.builder()
                .toolCallId("call-1")
                .toolName("databaseQuery")
                .toolInput("{\"sql\":\"SELECT 1\"}")
                .build();
        result.addContext(context);
        result.setDecision("call-1", HarnessDecision.pending("approval-1"));

        interceptor.beforeExecution(context, result);

        assertEquals(HarnessDecision.Status.PENDING_APPROVAL, result.getDecision("call-1").getStatus());
        assertEquals("approval-1", result.getDecision("call-1").getApprovalRequestId());
    }
}
