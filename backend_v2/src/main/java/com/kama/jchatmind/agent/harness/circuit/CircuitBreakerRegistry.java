package com.kama.jchatmind.agent.harness.circuit;

public interface CircuitBreakerRegistry {
    CircuitBreaker get(String toolName);
}
