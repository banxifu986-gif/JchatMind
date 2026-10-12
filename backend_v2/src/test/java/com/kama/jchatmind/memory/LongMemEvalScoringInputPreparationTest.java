package com.kama.jchatmind.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LongMemEvalScoringInputPreparationTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void doesNotPrepareEvaluatorInputsWhenM2ReviewIsIncomplete() {
        LongMemEvalReplayPreflightGate.Evidence evidence =
                new LongMemEvalReplayPreflightGate.Evidence(
                        true, true, false, false, true, true, true
                );
        List<LongMemEvalOfficialEvaluatorAdapter.Prediction> predictions = List.of(
                new LongMemEvalOfficialEvaluatorAdapter.Prediction("case-1", "A project answer")
        );
        List<LongMemEvalOfficialEvaluatorAdapter.Reference> references = List.of(
                new LongMemEvalOfficialEvaluatorAdapter.Reference(
                        "case-1", "multi-session", "A synthetic question", "A synthetic reference"
                )
        );

        LongMemEvalScoringInputPreparation.Result result =
                LongMemEvalScoringInputPreparation.prepare(evidence, predictions, references);

        assertThat(result.ready()).isFalse();
        assertThat(result.blockers()).contains("m2_review_incomplete", "m2_decisions_not_applied");
        assertThat(result.inputs()).isNull();
    }

    @Test
    void preparesSeparatedOfficialInputsOnlyWhenAllPreflightEvidenceIsReady() throws Exception {
        LongMemEvalReplayPreflightGate.Evidence evidence =
                new LongMemEvalReplayPreflightGate.Evidence(
                        true, true, true, true, true, true, true
                );
        List<LongMemEvalOfficialEvaluatorAdapter.Prediction> predictions = List.of(
                new LongMemEvalOfficialEvaluatorAdapter.Prediction("case-1", "A project answer")
        );
        List<LongMemEvalOfficialEvaluatorAdapter.Reference> references = List.of(
                new LongMemEvalOfficialEvaluatorAdapter.Reference(
                        "case-1", "multi-session", "A synthetic question", "A synthetic reference"
                )
        );

        LongMemEvalScoringInputPreparation.Result result =
                LongMemEvalScoringInputPreparation.prepare(evidence, predictions, references);

        assertThat(result.ready()).isTrue();
        assertThat(result.blockers()).isEmpty();
        assertThat(objectMapper.readTree(result.inputs().referencesJson()).get(0).path("answer").asText())
                .isEqualTo("A synthetic reference");
        assertThat(objectMapper.readTree(result.inputs().hypothesesJsonl()).path("hypothesis").asText())
                .isEqualTo("A project answer");
    }
}
