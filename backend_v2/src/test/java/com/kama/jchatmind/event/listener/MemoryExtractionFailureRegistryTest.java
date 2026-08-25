package com.kama.jchatmind.event.listener;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryExtractionFailureRegistryTest {

    @Test
    void shouldAccumulateSanitizedFailureDiagnosticsForSameUserAndSession() {
        MemoryExtractionFailureRegistry registry = new MemoryExtractionFailureRegistry();

        registry.recordFailure("7", "session-1", new IllegalStateException("internal memory content"));
        registry.recordFailure("7", "session-1", new IllegalStateException("internal memory content"));

        assertThat(registry.getFailure("7", "session-1"))
                .hasValueSatisfying(failure -> {
                    assertThat(failure.errorType()).isEqualTo(IllegalStateException.class.getName());
                    assertThat(failure.failureCount()).isEqualTo(2);
                    assertThat(failure.lastFailedAt()).isNotNull();
                    assertThat(failure.toString()).doesNotContain("internal memory content");
                });
    }
}
