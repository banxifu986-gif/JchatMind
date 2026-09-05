package com.kama.jchatmind.rag;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class G2CurrentFixtureProfileTest {

    @Test
    void profilesTheExistingCurrentFixtureWithoutTreatingItAsTheRetiredRegressionDataset() throws Exception {
        G2CurrentFixtureProfile profile = G2CurrentFixtureProfileLoader.load(
                "rag-eval/datasets/manifests/fixture-fast-v1.json"
        );

        assertThat(profile.datasetId()).isEqualTo("fixture-fast-v1");
        assertThat(profile.caseCount()).isEqualTo(20);
        assertThat(profile.answerableCaseCount()).isEqualTo(18);
        assertThat(profile.abstentionCaseCount()).isEqualTo(2);
        assertThat(profile.followUpCaseCount()).isEqualTo(1);
        assertThat(profile.crossDocumentCaseCount()).isEqualTo(2);
        assertThat(profile.cases()).allSatisfy(item -> {
            assertThat(item.expectedRoute()).isEqualTo("PRIVATE_RAG");
            assertThat(item.citationRequired()).isEqualTo(!item.shouldAbstain());
            if (item.shouldAbstain()) {
                assertThat(item.goldChunkIds()).isEmpty();
                assertThat(item.sourceDocumentIds()).isEmpty();
            } else {
                assertThat(item.goldChunkIds()).isNotEmpty();
                assertThat(item.sourceDocumentIds()).isNotEmpty();
            }
        });
        assertThat(profile.routeCounts()).containsEntry("PRIVATE_RAG", 20);
        assertThat(profile.routeCounts()).doesNotContainKey("ABSTAIN");
        assertThat(profile.routeCounts()).doesNotContainKey("MULTIMODAL_RAG");
    }
}
