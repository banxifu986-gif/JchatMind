package com.kama.jchatmind.intent;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IntentDecisionTelemetryTest {

    @Test
    void shouldKeepStructuredEventFreeOfRawQueryAndEntityValues() {
        IntentDecisionEvent event = new IntentDecisionEvent(
                Instant.parse("2026-09-22T00:00:00Z"),
                "trace-test",
                "decision-test",
                "a".repeat(64),
                IntentRouter.DECISION_VERSION,
                IntentRouter.POLICY_VERSION,
                true,
                BusinessIntent.CLARIFY,
                IntentRoute.CLARIFY,
                BusinessIntent.KNOWLEDGE_QA,
                IntentRoute.KNOWLEDGE,
                IntentRiskLevel.READ_ONLY,
                IntentDecisionSource.LLM_FALLBACK,
                0.91D,
                0.6D,
                0.15D,
                List.of("KnowledgeTool"),
                "SHADOW_COMPARISON"
        );

        assertThat(event.inputHash()).hasSize(64);
        assertThat(event.traceId()).isEqualTo("trace-test");
        assertThat(event.correlationId()).isEqualTo("decision-test");
        assertThat(event.candidateMargin()).isEqualTo(0.6D);
        assertThat(event.minMargin()).isEqualTo(0.15D);
        assertThat(event.toString()).doesNotContain("管理员密码");
        assertThat(event.candidateCapabilities()).containsExactly("KnowledgeTool");
    }

    @Test
    void shouldBoundInMemoryTelemetryRecords() {
        InMemoryIntentDecisionTelemetry telemetry = new InMemoryIntentDecisionTelemetry(1);
        telemetry.record(event("1"));
        telemetry.record(event("2"));

        assertThat(telemetry.getAll()).hasSize(1);
        assertThat(telemetry.getAll().get(0).inputHash()).isEqualTo("2".repeat(64));
    }

    private IntentDecisionEvent event(String value) {
        return new IntentDecisionEvent(
                Instant.now(),
                "trace-test",
                "decision-" + value,
                value.repeat(64),
                IntentRouter.DECISION_VERSION,
                IntentRouter.POLICY_VERSION,
                true,
                BusinessIntent.CLARIFY,
                IntentRoute.CLARIFY,
                BusinessIntent.KNOWLEDGE_QA,
                IntentRoute.KNOWLEDGE,
                IntentRiskLevel.READ_ONLY,
                IntentDecisionSource.LLM_FALLBACK,
                0.91D,
                0.6D,
                0.15D,
                List.of("KnowledgeTool"),
                "SHADOW_COMPARISON"
        );
    }
}
