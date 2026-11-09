package com.kama.jchatmind.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IntentVisualEvidenceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldPassVisualSummaryToModelAndParseVisualEvidenceFields() {
        List<String> receivedSummaries = new ArrayList<>();
        IntentFallbackRouter router = new IntentFallbackRouter(
                new IntentRouter(),
                objectMapper,
                new IntentModelClient() {
                    @Override
                    public String classify(String query) {
                        throw new UnsupportedOperationException();
                    }

                    @Override
                    public String classify(String query, String visualAssetSummary) {
                        receivedSummaries.add(visualAssetSummary);
                        return """
                                {
                                  "businessIntent": "KNOWLEDGE_QA",
                                  "confidence": 0.91,
                                  "margin": 0.6,
                                  "requiresVisualEvidence": true,
                                  "visualTask": "EXPLAIN_ARCHITECTURE_DIAGRAM"
                                }
                                """;
                    }
                },
                new IntentRoutingProperties(),
                false,
                IntentDecisionTelemetry.noop()
        );

        IntentDecision decision = router.decide(
                "我想了解 Redis 事务",
                null,
                "trace-1",
                "decision-1",
                "- DIAGRAM @部署架构：服务调用关系"
        );

        assertThat(receivedSummaries).containsExactly("- DIAGRAM @部署架构：服务调用关系");
        assertThat(decision.requiresVisualEvidence()).isTrue();
        assertThat(decision.visualTask()).isEqualTo("EXPLAIN_ARCHITECTURE_DIAGRAM");
        assertThat(decision.businessIntent()).isEqualTo(BusinessIntent.KNOWLEDGE_QA);
    }

    @Test
    void shouldDefaultVisualFieldsToNullWithoutModelOutput() {
        IntentFallbackRouter router = new IntentFallbackRouter(
                new IntentRouter(),
                objectMapper,
                query -> "{\"businessIntent\":\"KNOWLEDGE_QA\",\"confidence\":0.91,\"margin\":0.6}",
                new IntentRoutingProperties(),
                false,
                IntentDecisionTelemetry.noop()
        );

        IntentDecision decision = router.decide("我想了解 Redis 事务", null, null, null, null);

        assertThat(decision.requiresVisualEvidence()).isNull();
        assertThat(decision.visualTask()).isNull();
    }

    @Test
    void shouldPropagateVisualFieldsIntoAuditMetadata() {
        IntentDecision decision = new IntentDecision(
                IntentRouter.DECISION_VERSION,
                BusinessIntent.KNOWLEDGE_QA,
                null,
                IntentRoute.KNOWLEDGE,
                IntentRiskLevel.READ_ONLY,
                0.9D,
                null,
                Map.of(),
                List.of(),
                false,
                IntentDecisionSource.RULE,
                "RULE_KNOWLEDGE",
                IntentRouter.POLICY_VERSION,
                true,
                "EXPLAIN_ARCHITECTURE_DIAGRAM"
        );

        IntentDecisionMetadata metadata =
                IntentDecisionMetadata.from("trace-1", "decision-1", decision, List.of("KnowledgeTool"));

        assertThat(metadata.requiresVisualEvidence()).isTrue();
        assertThat(metadata.visualTask()).isEqualTo("EXPLAIN_ARCHITECTURE_DIAGRAM");
    }

    @Test
    void shouldKeepLegacyMetadataConstructorCompatible() {
        IntentDecisionMetadata metadata = new IntentDecisionMetadata(
                "trace-1",
                "decision-1",
                IntentRouter.DECISION_VERSION,
                IntentRouter.POLICY_VERSION,
                BusinessIntent.KNOWLEDGE_QA,
                null,
                IntentRoute.KNOWLEDGE,
                IntentRiskLevel.READ_ONLY,
                0.9D,
                null,
                List.of(),
                List.of("KnowledgeTool"),
                false,
                IntentDecisionSource.RULE,
                "RULE_KNOWLEDGE"
        );

        assertThat(metadata.requiresVisualEvidence()).isNull();
        assertThat(metadata.visualTask()).isNull();
    }
}