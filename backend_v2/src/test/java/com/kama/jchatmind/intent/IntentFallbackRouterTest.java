package com.kama.jchatmind.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntentFallbackRouterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldNotCallModelForHighConfidenceRuleDecision() {
        AtomicInteger calls = new AtomicInteger();
        IntentFallbackRouter router = router(query -> {
            calls.incrementAndGet();
            return knowledgeResponse();
        });

        IntentDecision decision = router.decide("请查询项目的发布规范");

        assertThat(calls).hasValue(0);
        assertThat(decision.decisionSource()).isEqualTo(IntentDecisionSource.RULE);
        assertThat(decision.businessIntent()).isEqualTo(BusinessIntent.KNOWLEDGE_QA);
    }

    @Test
    void shouldKeepLowInformationRequestInClarificationWithoutCallingModel() {
        AtomicInteger calls = new AtomicInteger();
        IntentFallbackRouter router = router(query -> {
            calls.incrementAndGet();
            return knowledgeResponse();
        });

        IntentDecision decision = router.decide("帮我处理一下这个");

        assertThat(calls).hasValue(0);
        assertThat(decision.businessIntent()).isEqualTo(BusinessIntent.CLARIFY);
        assertThat(decision.candidateCapabilities()).isEmpty();
    }

    @Test
    void shouldParseStructuredModelOutputAndIgnoreModelCapabilities() {
        IntentFallbackRouter router = router(query -> """
                {
                  "businessIntent": "KNOWLEDGE_QA",
                  "queryIntent": "FACTOID",
                  "confidence": 0.91,
                  "margin": 0.6,
                  "candidateCapabilities": ["sendEmail"],
                  "authorized": true,
                  "kbIds": ["other-user-kb"]
                }
                """);

        IntentDecision decision = router.decide(fallbackEligibleQuery());

        assertThat(decision.decisionSource()).isEqualTo(IntentDecisionSource.LLM_FALLBACK);
        assertThat(decision.businessIntent()).isEqualTo(BusinessIntent.KNOWLEDGE_QA);
        assertThat(decision.confidenceMargin()).isEqualTo(0.6D);
        assertThat(decision.candidateCapabilities()).containsExactly("KnowledgeTool");
        assertThat(decision.candidateCapabilities()).doesNotContain("sendEmail");
        assertThat(decision.riskLevel()).isEqualTo(IntentRiskLevel.READ_ONLY);
    }

    @Test
    void shouldFailClosedForInvalidJson() {
        IntentFallbackRouter router = router(query -> "not-json");

        IntentDecision decision = router.decide(fallbackEligibleQuery());

        assertThat(decision.businessIntent()).isEqualTo(BusinessIntent.CLARIFY);
        assertThat(decision.route()).isEqualTo(IntentRoute.CLARIFY);
        assertThat(decision.candidateCapabilities()).isEmpty();
        assertThat(decision.decisionSource()).isEqualTo(IntentDecisionSource.LLM_FALLBACK);
    }

    @Test
    void shouldFailClosedForUnknownIntentEnum() {
        IntentFallbackRouter router = router(query -> """
                {"businessIntent":"UNKNOWN_INTENT","confidence":0.99,"margin":0.8}
                """);

        IntentDecision decision = router.decide(fallbackEligibleQuery());

        assertThat(decision.businessIntent()).isEqualTo(BusinessIntent.CLARIFY);
        assertThat(decision.candidateCapabilities()).isEmpty();
    }

    @Test
    void shouldNeverAutoExposeSideEffectFromModelOnlyClassification() {
        IntentFallbackRouter router = router(query -> """
                {"businessIntent":"SEND_EMAIL","confidence":0.99,"margin":0.8}
                """);

        IntentDecision decision = router.decide(fallbackEligibleQuery());

        assertThat(decision.businessIntent()).isEqualTo(BusinessIntent.CLARIFY);
        assertThat(decision.riskLevel()).isEqualTo(IntentRiskLevel.BLOCKED);
        assertThat(decision.confidence()).isEqualTo(0.99D);
        assertThat(decision.confidenceMargin()).isEqualTo(0.8D);
        assertThat(decision.candidateCapabilities()).isEmpty();
    }

    @Test
    void shouldPreserveModelAbstainAsBlockedDecision() {
        IntentFallbackRouter router = router(query -> """
                {"businessIntent":"ABSTAIN","confidence":0.99,"margin":0.8}
                """);

        IntentDecision decision = router.decide(fallbackEligibleQuery());

        assertThat(decision.businessIntent()).isEqualTo(BusinessIntent.ABSTAIN);
        assertThat(decision.route()).isEqualTo(IntentRoute.ABSTAIN);
        assertThat(decision.riskLevel()).isEqualTo(IntentRiskLevel.BLOCKED);
        assertThat(decision.candidateCapabilities()).isEmpty();
    }

    @Test
    void shouldFailClosedWhenModelThrows() {
        IntentFallbackRouter router = router(query -> {
            throw new IllegalStateException("model unavailable");
        });

        IntentDecision decision = router.decide(fallbackEligibleQuery());

        assertThat(decision.businessIntent()).isEqualTo(BusinessIntent.CLARIFY);
        assertThat(decision.candidateCapabilities()).isEmpty();
    }

    @Test
    void shouldFailClosedWhenModelTimesOut() throws Exception {
        CountDownLatch modelStarted = new CountDownLatch(1);
        CountDownLatch releaseModel = new CountDownLatch(1);
        IntentRoutingProperties properties = new IntentRoutingProperties();
        properties.setTimeoutMillis(10L);
        IntentFallbackRouter router = router(query -> {
            modelStarted.countDown();
            try {
                if (!releaseModel.await(2L, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test model release timed out");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("test model interrupted", e);
            }
            return knowledgeResponse();
        }, properties);

        IntentDecision decision;
        try {
            decision = router.decide(fallbackEligibleQuery());
            assertThat(modelStarted.await(1L, TimeUnit.SECONDS)).isTrue();
        } finally {
            releaseModel.countDown();
        }

        assertThat(decision.businessIntent()).isEqualTo(BusinessIntent.CLARIFY);
        assertThat(decision.reasonCode()).isEqualTo("LLM_FALLBACK_FAILED");
        assertThat(decision.candidateCapabilities()).isEmpty();
    }

    @Test
    void shouldNotCallModelWhenInputExceedsConfiguredLimit() {
        AtomicInteger calls = new AtomicInteger();
        IntentRoutingProperties properties = new IntentRoutingProperties();
        properties.setMaxInputCharacters(4);
        IntentFallbackRouter router = router(query -> {
            calls.incrementAndGet();
            return knowledgeResponse();
        }, properties);

        IntentDecision decision = router.decide(fallbackEligibleQuery());

        assertThat(calls).hasValue(0);
        assertThat(decision.businessIntent()).isEqualTo(BusinessIntent.CLARIFY);
        assertThat(decision.reasonCode()).isEqualTo("LLM_INPUT_TOO_LONG");
        assertThat(decision.candidateCapabilities()).isEmpty();
    }

    @Test
    void shouldRetryOnlyWithinConfiguredLimitAndAcceptBoundedResponse() {
        AtomicInteger calls = new AtomicInteger();
        IntentRoutingProperties properties = new IntentRoutingProperties();
        properties.setMaxOutputCharacters(128);
        properties.setMaxRetries(1);
        IntentFallbackRouter router = router(query -> calls.incrementAndGet() == 1
                ? "x".repeat(129)
                : knowledgeResponse(), properties);

        IntentDecision decision = router.decide(fallbackEligibleQuery());

        assertThat(calls).hasValue(2);
        assertThat(decision.businessIntent()).isEqualTo(BusinessIntent.KNOWLEDGE_QA);
        assertThat(decision.candidateCapabilities()).containsExactly("KnowledgeTool");
    }

    @Test
    void shouldClarifyWhenModelDecisionMarginIsBelowConfiguredThreshold() {
        IntentRoutingProperties properties = new IntentRoutingProperties();
        properties.setMinMargin(0.20D);
        IntentFallbackRouter router = router(query ->
                "{\"businessIntent\":\"KNOWLEDGE_QA\",\"confidence\":0.91,\"margin\":0.19}",
                properties
        );

        IntentDecision decision = router.decide(fallbackEligibleQuery());

        assertThat(decision.businessIntent()).isEqualTo(BusinessIntent.CLARIFY);
        assertThat(decision.reasonCode()).isEqualTo("LLM_LOW_MARGIN");
        assertThat(decision.confidence()).isEqualTo(0.91D);
        assertThat(decision.confidenceMargin()).isEqualTo(0.19D);
        assertThat(decision.candidateCapabilities()).isEmpty();
    }

    @Test
    void shouldRecordModelConfidenceAndMarginWhenShadowCandidateIsClarified() {
        List<IntentDecisionEvent> events = new ArrayList<>();
        IntentRoutingProperties properties = new IntentRoutingProperties();
        properties.setMinMargin(0.20D);
        IntentFallbackRouter router = new IntentFallbackRouter(
                new IntentRouter(),
                objectMapper,
                query -> "{\"businessIntent\":\"KNOWLEDGE_QA\",\"confidence\":0.91,\"margin\":0.19}",
                properties,
                true,
                events::add
        );

        IntentDecision decision = router.decide("帮我处理一下这个", null, "trace-low-margin", "decision-low-margin");

        assertThat(decision.businessIntent()).isEqualTo(BusinessIntent.CLARIFY);
        assertThat(events).singleElement().satisfies(event -> {
            assertThat(event.candidateConfidence()).isEqualTo(0.91D);
            assertThat(event.candidateMargin()).isEqualTo(0.19D);
            assertThat(event.minMargin()).isEqualTo(0.20D);
        });
    }

    @Test
    void shouldAcceptDecisionAtConfiguredMarginBoundary() {
        IntentRoutingProperties properties = new IntentRoutingProperties();
        properties.setMinMargin(0.20D);
        IntentFallbackRouter router = router(query ->
                "{\"businessIntent\":\"KNOWLEDGE_QA\",\"confidence\":0.91,\"margin\":0.20}",
                properties
        );

        IntentDecision decision = router.decide(fallbackEligibleQuery());

        assertThat(decision.businessIntent()).isEqualTo(BusinessIntent.KNOWLEDGE_QA);
        assertThat(decision.confidenceMargin()).isEqualTo(0.20D);
    }

    @Test
    void shouldFailClosedWhenModelOmitsDecisionMargin() {
        IntentFallbackRouter router = router(query ->
                "{\"businessIntent\":\"KNOWLEDGE_QA\",\"confidence\":0.91}"
        );

        IntentDecision decision = router.decide(fallbackEligibleQuery());

        assertThat(decision.businessIntent()).isEqualTo(BusinessIntent.CLARIFY);
        assertThat(decision.reasonCode()).isEqualTo("LLM_OUTPUT_INVALID");
        assertThat(decision.candidateCapabilities()).isEmpty();
    }

    @Test
    void shouldRejectMarginGreaterThanTopOneConfidence() {
        IntentFallbackRouter router = router(query ->
                "{\"businessIntent\":\"KNOWLEDGE_QA\",\"confidence\":0.91,\"margin\":0.95}"
        );

        IntentDecision decision = router.decide(fallbackEligibleQuery());

        assertThat(decision.businessIntent()).isEqualTo(BusinessIntent.CLARIFY);
        assertThat(decision.reasonCode()).isEqualTo("LLM_OUTPUT_INVALID");
        assertThat(decision.candidateCapabilities()).isEmpty();
    }

    @Test
    void shouldReturnSafeClarificationAfterConfiguredRetriesAreExhausted() {
        AtomicInteger calls = new AtomicInteger();
        IntentRoutingProperties properties = new IntentRoutingProperties();
        properties.setMaxOutputCharacters(8);
        properties.setMaxRetries(1);
        IntentFallbackRouter router = router(query -> {
            calls.incrementAndGet();
            return "x".repeat(9);
        }, properties);

        IntentDecision decision = router.decide(fallbackEligibleQuery());

        assertThat(calls).hasValue(2);
        assertThat(decision.businessIntent()).isEqualTo(BusinessIntent.CLARIFY);
        assertThat(decision.reasonCode()).isEqualTo("LLM_OUTPUT_TOO_LONG");
        assertThat(decision.candidateCapabilities()).isEmpty();
    }

    @Test
    void shouldRejectRetryLimitAboveHardMaximum() {
        IntentRoutingProperties properties = new IntentRoutingProperties();
        properties.setMaxRetries(3);

        assertThatThrownBy(() -> router(query -> knowledgeResponse(), properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxRetries");
    }

    @Test
    void shouldRejectMarginThresholdOutsideClosedUnitInterval() {
        IntentRoutingProperties properties = new IntentRoutingProperties();
        properties.setMinMargin(1.01D);

        assertThatThrownBy(() -> router(query -> knowledgeResponse(), properties))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("minMargin");
    }

    @Test
    void shouldReturnRuleDecisionAndRecordCandidateOnlyInShadowMode() {
        List<IntentDecisionEvent> events = new ArrayList<>();
        IntentRoutingProperties properties = new IntentRoutingProperties();
        IntentFallbackRouter router = new IntentFallbackRouter(
                new IntentRouter(),
                objectMapper,
                query -> "{\"businessIntent\":\"KNOWLEDGE_QA\",\"confidence\":0.91,\"margin\":0.6}",
                properties,
                true,
                events::add
        );

        IntentDecision decision = router.decide("帮我处理一下这个", null, "trace-shadow-1", "decision-shadow-1");

        assertThat(decision.businessIntent()).isEqualTo(BusinessIntent.CLARIFY);
        assertThat(events).hasSize(1);
        assertThat(events.get(0).traceId()).isEqualTo("trace-shadow-1");
        assertThat(events.get(0).correlationId()).isEqualTo("decision-shadow-1");
        assertThat(events.get(0).decisionVersion()).isEqualTo(IntentRouter.DECISION_VERSION);
        assertThat(events.get(0).policyVersion()).isEqualTo(IntentRouter.POLICY_VERSION);
        assertThat(events.get(0).candidateSource()).isEqualTo(IntentDecisionSource.LLM_FALLBACK);
        assertThat(events.get(0).shadow()).isTrue();
        assertThat(events.get(0).ruleIntent()).isEqualTo(BusinessIntent.CLARIFY);
        assertThat(events.get(0).candidateIntent()).isEqualTo(BusinessIntent.KNOWLEDGE_QA);
        assertThat(events.get(0).inputHash()).doesNotContain("帮我处理一下这个");
    }

    private IntentFallbackRouter router(IntentModelClient client) {
        return router(client, new IntentRoutingProperties());
    }

    private IntentFallbackRouter router(IntentModelClient client, IntentRoutingProperties properties) {
        return new IntentFallbackRouter(
                new IntentRouter(),
                objectMapper,
                client,
                properties,
                false,
                IntentDecisionTelemetry.noop()
        );
    }

    private String knowledgeResponse() {
        return "{\"businessIntent\":\"KNOWLEDGE_QA\",\"queryIntent\":\"FACTOID\",\"confidence\":0.91,\"margin\":0.6}";
    }

    private String fallbackEligibleQuery() {
        return "我想了解 Redis 事务";
    }
}
