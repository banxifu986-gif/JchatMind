package com.kama.jchatmind.agent.harness.interceptor;

import com.kama.jchatmind.agent.harness.HarnessContext;
import com.kama.jchatmind.agent.harness.HarnessDecision;
import com.kama.jchatmind.agent.harness.HarnessProperties;
import com.kama.jchatmind.agent.harness.circuit.InMemoryCircuitBreakerRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class CircuitBreakerInterceptorTest {

    @Test
    void shouldReturnCircuitOpenDecisionWhenOpen() {
        HarnessProperties properties = new HarnessProperties();
        properties.getCircuitBreaker().setEnabled(true);
        properties.getCircuitBreaker().setTools(List.of("databaseQuery"));

        InMemoryCircuitBreakerRegistry registry = new InMemoryCircuitBreakerRegistry(properties);
        registry.get("databaseQuery").recordFailure();
        registry.get("databaseQuery").recordFailure();
        registry.get("databaseQuery").recordFailure();

        CircuitBreakerInterceptor interceptor = new CircuitBreakerInterceptor(properties, registry);
        HarnessContext context = HarnessContext.builder()
                .toolCallId("call-1")
                .toolName("databaseQuery")
                .toolInput("{\"sql\":\"SELECT 1\"}")
                .build();

        HarnessDecision decision = interceptor.beforeExecution(context);

        assertEquals(HarnessDecision.Status.CIRCUIT_OPEN, decision.getStatus());
    }

    @Test
    void shouldReturnNullWhenClosedOrNotConfigured() {
        HarnessProperties properties = new HarnessProperties();
        properties.getCircuitBreaker().setEnabled(true);
        properties.getCircuitBreaker().setTools(List.of("databaseQuery"));

        InMemoryCircuitBreakerRegistry registry = new InMemoryCircuitBreakerRegistry(properties);
        CircuitBreakerInterceptor interceptor = new CircuitBreakerInterceptor(properties, registry);

        HarnessContext allowedContext = HarnessContext.builder()
                .toolCallId("call-1")
                .toolName("databaseQuery")
                .toolInput("{}")
                .build();
        HarnessContext unlistedContext = HarnessContext.builder()
                .toolCallId("call-2")
                .toolName("KnowledgeTool")
                .toolInput("{}")
                .build();

        assertNull(interceptor.beforeExecution(allowedContext));
        assertNull(interceptor.beforeExecution(unlistedContext));
    }
}
