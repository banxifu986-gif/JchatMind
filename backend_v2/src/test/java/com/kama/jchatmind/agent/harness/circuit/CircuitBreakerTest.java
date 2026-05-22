package com.kama.jchatmind.agent.harness.circuit;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CircuitBreakerTest {

    @Test
    void shouldOpenAfterThresholdFailures() {
        CircuitBreaker circuitBreaker = new CircuitBreaker(3, 60);

        circuitBreaker.recordFailure();
        circuitBreaker.recordFailure();
        circuitBreaker.recordFailure();

        assertEquals(CircuitBreakerState.OPEN, circuitBreaker.getState());
        assertFalse(circuitBreaker.allowRequest());
    }

    @Test
    void shouldRecoverToHalfOpenAfterTimeout() throws Exception {
        CircuitBreaker circuitBreaker = new CircuitBreaker(1, 1);
        circuitBreaker.recordFailure();

        Thread.sleep(1100L);

        assertTrue(circuitBreaker.allowRequest());
        assertEquals(CircuitBreakerState.HALF_OPEN, circuitBreaker.getState());
    }

    @Test
    void shouldCloseOnHalfOpenSuccess() throws Exception {
        CircuitBreaker circuitBreaker = new CircuitBreaker(1, 1);
        circuitBreaker.recordFailure();

        Thread.sleep(1100L);
        assertTrue(circuitBreaker.allowRequest());

        circuitBreaker.recordSuccess();

        assertEquals(CircuitBreakerState.CLOSED, circuitBreaker.getState());
        assertEquals(0, circuitBreaker.getFailureCount());
    }

    @Test
    void shouldReopenOnHalfOpenFailure() throws Exception {
        CircuitBreaker circuitBreaker = new CircuitBreaker(1, 1);
        circuitBreaker.recordFailure();

        Thread.sleep(1100L);
        assertTrue(circuitBreaker.allowRequest());

        circuitBreaker.recordFailure();

        assertEquals(CircuitBreakerState.OPEN, circuitBreaker.getState());
    }

    @Test
    void shouldNotResetRecoveryWindowWhenAlreadyOpen() throws Exception {
        CircuitBreaker circuitBreaker = new CircuitBreaker(1, 1);
        circuitBreaker.recordFailure();
        Thread.sleep(500L);
        circuitBreaker.recordFailure();
        Thread.sleep(600L);

        assertTrue(circuitBreaker.allowRequest());
        assertEquals(CircuitBreakerState.HALF_OPEN, circuitBreaker.getState());
    }
}
