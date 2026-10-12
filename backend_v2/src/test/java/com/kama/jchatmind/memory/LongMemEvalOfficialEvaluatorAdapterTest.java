package com.kama.jchatmind.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LongMemEvalOfficialEvaluatorAdapterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesReferencesAndHypothesesSeparatelyInReferenceOrder() throws Exception {
        List<LongMemEvalOfficialEvaluatorAdapter.Reference> references = List.of(
                new LongMemEvalOfficialEvaluatorAdapter.Reference(
                        "case-1", "multi-session", "Question one", "Reference one"
                ),
                new LongMemEvalOfficialEvaluatorAdapter.Reference(
                        "case-2", "temporal-reasoning", "Question two", "Reference two"
                )
        );
        List<LongMemEvalOfficialEvaluatorAdapter.Prediction> predictions = List.of(
                new LongMemEvalOfficialEvaluatorAdapter.Prediction("case-2", "Answer two"),
                new LongMemEvalOfficialEvaluatorAdapter.Prediction("case-1", "Answer one")
        );

        LongMemEvalOfficialEvaluatorAdapter.EvaluatorInputs inputs =
                LongMemEvalOfficialEvaluatorAdapter.adapt(predictions, references);

        JsonNode referencesJson = objectMapper.readTree(inputs.referencesJson());
        assertThat(referencesJson.size()).isEqualTo(2);
        assertThat(referencesJson.get(0).size()).isEqualTo(4);
        assertThat(referencesJson.get(0).path("question_id").asText()).isEqualTo("case-1");
        assertThat(referencesJson.get(0).path("question_type").asText()).isEqualTo("multi-session");
        assertThat(referencesJson.get(0).path("question").asText()).isEqualTo("Question one");
        assertThat(referencesJson.get(0).path("answer").asText()).isEqualTo("Reference one");

        List<String> hypothesisLines = inputs.hypothesesJsonl().lines().toList();
        assertThat(hypothesisLines).hasSize(2);
        JsonNode firstHypothesis = objectMapper.readTree(hypothesisLines.get(0));
        assertThat(firstHypothesis.size()).isEqualTo(2);
        assertThat(firstHypothesis.path("question_id").asText()).isEqualTo("case-1");
        assertThat(firstHypothesis.path("hypothesis").asText()).isEqualTo("Answer one");
        assertThat(firstHypothesis.has("answer")).isFalse();
        assertThat(firstHypothesis.has("question")).isFalse();
    }

    @Test
    void rejectsMissingOrUnexpectedPredictionCaseIds() {
        List<LongMemEvalOfficialEvaluatorAdapter.Reference> references = List.of(
                new LongMemEvalOfficialEvaluatorAdapter.Reference(
                        "case-1", "multi-session", "Question one", "Reference one"
                ),
                new LongMemEvalOfficialEvaluatorAdapter.Reference(
                        "case-2", "temporal-reasoning", "Question two", "Reference two"
                )
        );

        assertThatThrownBy(() -> LongMemEvalOfficialEvaluatorAdapter.adapt(
                List.of(new LongMemEvalOfficialEvaluatorAdapter.Prediction("case-1", "Answer one")),
                references
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("case coverage");

        assertThatThrownBy(() -> LongMemEvalOfficialEvaluatorAdapter.adapt(
                List.of(
                        new LongMemEvalOfficialEvaluatorAdapter.Prediction("case-1", "Answer one"),
                        new LongMemEvalOfficialEvaluatorAdapter.Prediction("case-3", "Answer three")
                ),
                references
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("case coverage");
    }

    @Test
    void rejectsDuplicatePredictionIds() {
        List<LongMemEvalOfficialEvaluatorAdapter.Prediction> predictions = List.of(
                new LongMemEvalOfficialEvaluatorAdapter.Prediction("case-1", "First answer"),
                new LongMemEvalOfficialEvaluatorAdapter.Prediction("case-1", "Duplicate answer")
        );
        List<LongMemEvalOfficialEvaluatorAdapter.Reference> references = List.of(
                new LongMemEvalOfficialEvaluatorAdapter.Reference(
                        "case-1", "multi-session", "Question one", "Reference one"
                )
        );

        assertThatThrownBy(() -> LongMemEvalOfficialEvaluatorAdapter.adapt(predictions, references))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }
}
