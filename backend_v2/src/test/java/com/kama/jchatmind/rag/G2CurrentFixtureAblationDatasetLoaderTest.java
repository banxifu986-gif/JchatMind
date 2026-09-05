package com.kama.jchatmind.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class G2CurrentFixtureAblationDatasetLoaderTest {

    @Test
    void adaptsCurrentFixtureToTheExistingFixedChainRouterEvaluator() throws Exception {
        RagRouterAblationDataset dataset = G2CurrentFixtureAblationDatasetLoader.load(
                "rag-eval/datasets/manifests/fixture-fast-v1.json"
        );

        assertThat(dataset.datasetVersion()).isEqualTo("fixture-fast-v1");
        assertThat(dataset.cases()).hasSize(20);
        assertThat(dataset.cases()).filteredOn(RagRouterAblationCase::shouldAbstain).hasSize(2);
        assertThat(dataset.cases()).filteredOn(item -> !item.shouldAbstain()).allSatisfy(item -> {
            assertThat(item.goldChunkIds()).isNotEmpty();
            assertThat(item.expectedRoute()).isEqualTo("PRIVATE_RAG");
        });

        RagRouterAblationEvaluationReport report = new RagRouterAblationEvaluator().evaluate(
                dataset,
                dataset.cases().stream()
                        .map(this::replayWithGoldAtTop)
                        .toList()
        );

        assertThat(report.gate().verdict()).isEqualTo("eligible_for_router_ab");
        assertThat(report.router().routeViolations()).isZero();
        assertThat(report.router().permissionViolations()).isZero();
    }

    private RagRouterAblationReplay replayWithGoldAtTop(RagRouterAblationCase item) {
        List<String> rankedChunkIds = item.goldChunkIds().stream().toList();
        boolean abstained = item.shouldAbstain();
        return new RagRouterAblationReplay(
                item.caseId(),
                rankedChunkIds,
                rankedChunkIds,
                item.expectedRoute(),
                item.expectedRoute(),
                abstained,
                abstained,
                false,
                false,
                false,
                100,
                100,
                100,
                100,
                !abstained,
                !abstained
        );
    }
}
