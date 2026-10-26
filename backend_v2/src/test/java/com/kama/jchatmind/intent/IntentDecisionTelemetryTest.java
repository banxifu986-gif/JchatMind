package com.kama.jchatmind.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class IntentDecisionTelemetryTest {

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shouldKeepStructuredEventFreeOfRawQueryAndEntityValues(boolean shadowMode) throws Exception {
        String query = "我想了解 Redis 事务，附注 raw-body-fixture，测试口令 password-fixture";
        String previousQuery = "上一条备注 previous-body-fixture";
        Map<String, String> entities = Map.of(
                "token", "entity-token-fixture",
                "email", "entity-only@example.invalid",
                "path", "C:/fixture-private/telemetry.txt"
        );
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        String modelResponse = objectMapper.writeValueAsString(Map.of(
                "businessIntent", "KNOWLEDGE_QA",
                "confidence", 0.91D,
                "margin", 0.6D,
                "entities", entities
        ));
        AtomicReference<String> modelInput = new AtomicReference<>();
        InMemoryIntentDecisionTelemetry telemetry = new InMemoryIntentDecisionTelemetry(10);
        IntentFallbackRouter router = new IntentFallbackRouter(
                new IntentRouter(),
                objectMapper,
                input -> {
                    modelInput.set(input);
                    return modelResponse;
                },
                new IntentRoutingProperties(),
                shadowMode,
                telemetry::record
        );

        IntentDecision decision = router.decide(query, previousQuery, "trace-test", "decision-test");

        assertThat(modelInput).hasValue(query);
        assertThat(decision.businessIntent()).isEqualTo(
                shadowMode ? BusinessIntent.GENERAL_EXPLANATION : BusinessIntent.KNOWLEDGE_QA
        );
        if (!shadowMode) {
            assertThat(decision.entities()).containsExactlyInAnyOrderEntriesOf(entities);
        }
        assertThat(telemetry.getAll()).hasSize(1);
        IntentDecisionEvent event = telemetry.getAll().get(0);
        String expectedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((previousQuery + "\n" + query).getBytes(StandardCharsets.UTF_8)));
        assertThat(event.inputHash()).isEqualTo(expectedHash);
        assertThat(event.traceId()).isEqualTo("trace-test");
        assertThat(event.correlationId()).isEqualTo("decision-test");
        assertThat(event.decisionVersion()).isEqualTo(IntentRouter.DECISION_VERSION);
        assertThat(event.policyVersion()).isEqualTo(IntentRouter.POLICY_VERSION);
        assertThat(event.shadow()).isEqualTo(shadowMode);
        assertThat(event.candidateIntent()).isEqualTo(BusinessIntent.KNOWLEDGE_QA);
        assertThat(event.candidateConfidence()).isEqualTo(0.91D);
        assertThat(event.candidateMargin()).isEqualTo(0.6D);
        assertThat(event.minMargin()).isEqualTo(0.15D);
        assertThat(event.candidateCapabilities()).containsExactly("KnowledgeTool");
        assertThat(objectMapper.writeValueAsString(event))
                .doesNotContain(query, previousQuery, "raw-body-fixture", "previous-body-fixture", "password-fixture")
                .doesNotContain(entities.values().toArray(String[]::new));
        assertThat(event.toString())
                .doesNotContain(query, previousQuery, "raw-body-fixture", "previous-body-fixture", "password-fixture")
                .doesNotContain(entities.values().toArray(String[]::new));
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
