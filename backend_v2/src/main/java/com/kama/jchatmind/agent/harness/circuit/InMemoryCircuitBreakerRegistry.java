package com.kama.jchatmind.agent.harness.circuit;

import com.kama.jchatmind.agent.harness.HarnessProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Component
public class InMemoryCircuitBreakerRegistry implements CircuitBreakerRegistry {

    private final ConcurrentMap<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();
    private final HarnessProperties harnessProperties;

    public InMemoryCircuitBreakerRegistry(HarnessProperties harnessProperties) {
        this.harnessProperties = harnessProperties;
    }

    @Override
    public CircuitBreaker get(String toolName) {
        return circuitBreakers.computeIfAbsent(
                toolName,
                key -> new CircuitBreaker(
                        harnessProperties.getCircuitBreaker().getFailureThreshold(),
                        harnessProperties.getCircuitBreaker().getRecoveryTimeoutSeconds()
                )
        );
    }
}
