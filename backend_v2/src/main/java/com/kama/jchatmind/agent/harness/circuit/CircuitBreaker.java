package com.kama.jchatmind.agent.harness.circuit;

import java.time.Instant;

public class CircuitBreaker {

    private final int failureThreshold;
    private final int recoveryTimeoutSeconds;

    private CircuitBreakerState state = CircuitBreakerState.CLOSED;
    private int consecutiveFailures = 0;
    private Instant openedAt;

    public CircuitBreaker(int failureThreshold, int recoveryTimeoutSeconds) {
        this.failureThreshold = failureThreshold;
        this.recoveryTimeoutSeconds = recoveryTimeoutSeconds;
    }

    public synchronized boolean allowRequest() {
        if (state == CircuitBreakerState.CLOSED) {
            return true;
        }
        if (state == CircuitBreakerState.HALF_OPEN) {
            return true;
        }
        if (openedAt != null && Instant.now().isAfter(openedAt.plusSeconds(recoveryTimeoutSeconds))) {
            state = CircuitBreakerState.HALF_OPEN;
            return true;
        }
        return false;
    }

    public synchronized void recordSuccess() {
        consecutiveFailures = 0;
        openedAt = null;
        state = CircuitBreakerState.CLOSED;
    }

    public synchronized void recordFailure() {
        consecutiveFailures++;
        if (state == CircuitBreakerState.HALF_OPEN || consecutiveFailures >= failureThreshold) {
            if (state != CircuitBreakerState.OPEN) {
                openedAt = Instant.now();
            }
            state = CircuitBreakerState.OPEN;
        }
    }

    public synchronized CircuitBreakerState getState() {
        return state;
    }

    public synchronized int getFailureCount() {
        return consecutiveFailures;
    }
}
