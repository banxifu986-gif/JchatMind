package com.kama.jchatmind.intent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntentDecisionContractTest {

    @Test
    void shouldKeepDecisionCollectionsImmutable() {
        IntentDecision decision = decision();

        assertThatThrownBy(() -> decision.candidateCapabilities().add("sendEmail"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> decision.entities().put("recipient", "other@example.com"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void shouldRejectConfidenceOutsideClosedUnitInterval() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new IntentDecision(
                        "intent-v2",
                        BusinessIntent.KNOWLEDGE_QA,
                        QueryIntent.FACTOID,
                        IntentRoute.KNOWLEDGE,
                        IntentRiskLevel.READ_ONLY,
                        1.01D,
                        null,
                        Map.of(),
                        List.of("KnowledgeTool"),
                        false,
                        IntentDecisionSource.RULE,
                        "KNOWLEDGE_SIGNAL",
                        "intent-policy-v5"
                ));
    }

    @Test
    void shouldRejectConfidenceMarginOutsideClosedUnitInterval() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new IntentDecision(
                        "intent-v2",
                        BusinessIntent.KNOWLEDGE_QA,
                        QueryIntent.FACTOID,
                        IntentRoute.KNOWLEDGE,
                        IntentRiskLevel.READ_ONLY,
                        0.91D,
                        1.01D,
                        Map.of(),
                        List.of("KnowledgeTool"),
                        false,
                        IntentDecisionSource.LLM_FALLBACK,
                        "LLM_CLASSIFIED_KNOWLEDGE",
                        "intent-policy-v5"
                ));
    }

    @Test
    void shouldCarryModelMarginIntoAuditMetadataWithoutAddingEntityValues() {
        IntentDecision decision = new IntentDecision(
                "intent-v1",
                BusinessIntent.KNOWLEDGE_QA,
                QueryIntent.FACTOID,
                IntentRoute.KNOWLEDGE,
                IntentRiskLevel.READ_ONLY,
                0.91D,
                0.6D,
                Map.of("recipient", "private@example.com"),
                List.of("KnowledgeTool"),
                false,
                IntentDecisionSource.LLM_FALLBACK,
                "LLM_CLASSIFIED_KNOWLEDGE",
                "intent-policy-v5"
        );

        IntentDecisionMetadata metadata = IntentDecisionMetadata.from(
                "trace-1",
                "decision-1",
                decision,
                List.of("KnowledgeTool")
        );

        assertThat(metadata.confidenceMargin()).isEqualTo(0.6D);
        assertThat(metadata.candidateCapabilities()).containsExactly("KnowledgeTool");
        assertThat(metadata.selectedToolNames()).containsExactly("KnowledgeTool");
        assertThat(metadata.toString()).doesNotContain("private@example.com");
    }

    @Test
    void shouldExposeVersionedStructuredDecision() {
        IntentDecision decision = decision();

        assertThat(decision.decisionVersion()).isEqualTo("intent-v2");
        assertThat(decision.businessIntent()).isEqualTo(BusinessIntent.KNOWLEDGE_QA);
        assertThat(decision.queryIntent()).isEqualTo(QueryIntent.FACTOID);
        assertThat(decision.route()).isEqualTo(IntentRoute.KNOWLEDGE);
        assertThat(decision.riskLevel()).isEqualTo(IntentRiskLevel.READ_ONLY);
        assertThat(decision.confidence()).isEqualTo(0.93D);
        assertThat(decision.confidenceMargin()).isNull();
        assertThat(decision.candidateCapabilities()).containsExactly("KnowledgeTool");
        assertThat(decision.requiresClarification()).isFalse();
        assertThat(decision.decisionSource()).isEqualTo(IntentDecisionSource.RULE);
        assertThat(decision.reasonCode()).isEqualTo("KNOWLEDGE_SIGNAL");
    }

    private IntentDecision decision() {
        return new IntentDecision(
                "intent-v2",
                BusinessIntent.KNOWLEDGE_QA,
                QueryIntent.FACTOID,
                IntentRoute.KNOWLEDGE,
                IntentRiskLevel.READ_ONLY,
                0.93D,
                null,
                Map.of("topic", "发布规范"),
                List.of("KnowledgeTool"),
                false,
                IntentDecisionSource.RULE,
                "KNOWLEDGE_SIGNAL",
                "intent-policy-v5"
        );
    }

    @Test
    void shouldVersionDecisionContractWithConfidenceMargin() {
        assertThat(new IntentRouter().decide("你好").decisionVersion()).isEqualTo("intent-v2");
    }
}
