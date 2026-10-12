package com.kama.jchatmind.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LongMemEval30CaseSelectorTest {

    private static final String PIN_PATH = "memory-eval/longmemeval-source-pin-v1.json";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void selectsAStableDisjointThirtyCaseStratification() throws Exception {
        JsonNode projectSubset = loadPin().path("projectSubset");
        List<LongMemEvalOfficialDatasetLoader.SourceReference> references = sourceReferences();

        List<LongMemEval30CaseSelector.SelectedCaseReference> firstSelection =
                LongMemEval30CaseSelector.select(projectSubset, references);
        List<LongMemEval30CaseSelector.SelectedCaseReference> secondSelection =
                LongMemEval30CaseSelector.select(projectSubset, references);

        assertThat(secondSelection).containsExactlyElementsOf(firstSelection);
        assertThat(firstSelection).hasSize(30);
        assertThat(firstSelection.stream()
                .map(selected -> selected.sourceReference().questionId())
                .distinct()).hasSize(30);
        assertThat(firstSelection).allSatisfy(selected -> {
            assertThat(selected.selectionRank()).matches("[0-9a-f]{64}");
            assertThat(references).contains(selected.sourceReference());
        });

        Map<String, Long> casesByCategory = new HashMap<>();
        firstSelection.forEach(selected -> casesByCategory.merge(selected.categoryId(), 1L, Long::sum));
        assertThat(casesByCategory).containsExactlyInAnyOrderEntriesOf(Map.of(
                "information_extraction", 6L,
                "multi_session_reasoning", 6L,
                "knowledge_update", 6L,
                "temporal_reasoning", 6L,
                "abstention", 6L
        ));
    }

    @Test
    void rejectsUnknownQuestionTypesAndMismatchedAbstentionMarkers() throws Exception {
        JsonNode projectSubset = loadPin().path("projectSubset");
        List<LongMemEvalOfficialDatasetLoader.SourceReference> references = sourceReferences();
        List<LongMemEvalOfficialDatasetLoader.SourceReference> unknownType = new ArrayList<>(references);
        unknownType.add(new LongMemEvalOfficialDatasetLoader.SourceReference(
                "unknown-type", "unregistered-type", false, unknownType.size()
        ));
        List<LongMemEvalOfficialDatasetLoader.SourceReference> mismatchedAbstention =
                new ArrayList<>(references);
        mismatchedAbstention.add(new LongMemEvalOfficialDatasetLoader.SourceReference(
                "missing-abs-suffix", "multi-session", true, mismatchedAbstention.size()
        ));

        assertThatThrownBy(() -> LongMemEval30CaseSelector.select(projectSubset, unknownType))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LongMemEval30CaseSelector.select(projectSubset, mismatchedAbstention))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private List<LongMemEvalOfficialDatasetLoader.SourceReference> sourceReferences() {
        List<LongMemEvalOfficialDatasetLoader.SourceReference> references = new ArrayList<>();
        int sourceIndex = 0;
        for (String questionType : List.of(
                "single-session-user", "single-session-assistant", "single-session-preference"
        )) {
            for (int index = 0; index < 3; index++) {
                String questionId = questionType + "-" + index;
                references.add(new LongMemEvalOfficialDatasetLoader.SourceReference(
                        questionId, questionType, false, sourceIndex++
                ));
            }
        }
        for (String questionType : List.of("multi-session", "knowledge-update", "temporal-reasoning")) {
            for (int index = 0; index < 8; index++) {
                String questionId = questionType + "-" + index;
                references.add(new LongMemEvalOfficialDatasetLoader.SourceReference(
                        questionId, questionType, false, sourceIndex++
                ));
            }
        }
        for (int index = 0; index < 8; index++) {
            String questionId = "abstention-" + index + "_abs";
            references.add(new LongMemEvalOfficialDatasetLoader.SourceReference(
                    questionId, "multi-session", true, sourceIndex++
            ));
        }
        return List.copyOf(references);
    }

    private JsonNode loadPin() throws Exception {
        try (InputStream inputStream = new ClassPathResource(PIN_PATH).getInputStream()) {
            return OBJECT_MAPPER.readTree(inputStream);
        }
    }
}
