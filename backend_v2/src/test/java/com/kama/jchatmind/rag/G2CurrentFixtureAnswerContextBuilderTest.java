package com.kama.jchatmind.rag;

import com.kama.jchatmind.model.dto.RagRetrievalResult;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class G2CurrentFixtureAnswerContextBuilderTest {

    private static final String MANIFEST_PATH = "rag-eval/datasets/manifests/fixture-fast-v1.json";

    @Test
    void buildsAnswerContextsWithRuntimeChunkCitationsAndKeepsRetrievalIds() throws Exception {
        G2CurrentFixtureProfile profile = G2CurrentFixtureProfileLoader.load(MANIFEST_PATH);
        Map<String, List<RagRetrievalResult>> retrievedByCaseId = emptyResults(profile);
        G2CurrentFixtureCase answerable = profile.cases().stream()
                .filter(item -> !item.shouldAbstain())
                .findFirst()
                .orElseThrow();
        G2CurrentFixtureCase abstention = profile.cases().stream()
                .filter(G2CurrentFixtureCase::shouldAbstain)
                .findFirst()
                .orElseThrow();
        String citedRuntimeId = "11111111-1111-1111-1111-111111111111";
        String emptyContentRuntimeId = "22222222-2222-2222-2222-222222222222";
        retrievedByCaseId.put(answerable.caseId(), List.of(
                result(citedRuntimeId, "Retrieved evidence text"),
                result(emptyContentRuntimeId, "   ")
        ));

        G2CurrentFixtureAnswerContextBuilder.AnswerInputs inputs =
                new G2CurrentFixtureAnswerContextBuilder().build(profile, retrievedByCaseId);

        assertThat(inputs.contextsByCaseId()).hasSize(profile.caseCount());
        assertThat(inputs.contextsByCaseId().get(answerable.caseId()))
                .contains("引用: " + citedRuntimeId + "\nRetrieved evidence text")
                .doesNotContain(emptyContentRuntimeId);
        assertThat(inputs.retrievedRuntimeChunkIdsByCaseId().get(answerable.caseId()))
                .containsExactlyInAnyOrder(citedRuntimeId, emptyContentRuntimeId);
        assertThat(inputs.contextsByCaseId().get(abstention.caseId())).isEmpty();
        assertThat(inputs.retrievedRuntimeChunkIdsByCaseId().get(abstention.caseId())).isEmpty();
    }

    @Test
    void rejectsIncompleteCaseCoverageAndDuplicateRuntimeChunkIds() throws Exception {
        G2CurrentFixtureProfile profile = G2CurrentFixtureProfileLoader.load(MANIFEST_PATH);
        assertThatThrownBy(() -> new G2CurrentFixtureAnswerContextBuilder().build(profile, Map.of()))
                .isInstanceOf(IllegalStateException.class);

        Map<String, List<RagRetrievalResult>> retrievedByCaseId = emptyResults(profile);
        G2CurrentFixtureCase answerable = profile.cases().stream()
                .filter(item -> !item.shouldAbstain())
                .findFirst()
                .orElseThrow();
        String duplicateRuntimeId = "11111111-1111-1111-1111-111111111111";
        retrievedByCaseId.put(answerable.caseId(), List.of(
                result(duplicateRuntimeId, "First evidence"),
                result(duplicateRuntimeId, "Second evidence")
        ));

        assertThatThrownBy(() -> new G2CurrentFixtureAnswerContextBuilder().build(profile, retrievedByCaseId))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    private Map<String, List<RagRetrievalResult>> emptyResults(G2CurrentFixtureProfile profile) {
        Map<String, List<RagRetrievalResult>> results = new LinkedHashMap<>();
        profile.cases().forEach(item -> results.put(item.caseId(), List.of()));
        return results;
    }

    private RagRetrievalResult result(String chunkId, String content) {
        RagRetrievalResult result = new RagRetrievalResult();
        result.setChunkId(chunkId);
        result.setContent(content);
        return result;
    }
}
