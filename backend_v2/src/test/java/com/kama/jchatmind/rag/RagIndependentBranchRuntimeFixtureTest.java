package com.kama.jchatmind.rag;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class RagIndependentBranchRuntimeFixtureTest {

    @Test
    void usesTheExistingIsolatedDatabaseNamespaceRatherThanIntroducingAnotherSchemaValue() {
        assertThat(RagIndependentBranchRuntimeImporter.evaluationNamespace()).isEqualTo("rag-eval");
    }

    @Test
    void derivesStableRuntimeChunkUuidsForEveryFrozenAnswerableGold() throws Exception {
        RagEvaluationDataset dataset = RagEvaluationDatasetLoader.load(
                "rag-eval/datasets/manifests/g2-pre-bm25-v1.json"
        );

        RagIndependentBranchRuntimeFixture.Fixture fixture = RagIndependentBranchRuntimeFixture.load(dataset);

        assertThat(fixture.candidates()).hasSize(7);
        assertThat(fixture.logicalChunkIdByRuntimeUuid()).hasSize(7);
        assertThat(fixture.candidates())
                .extracting(RagIndependentBranchRuntimeFixture.Candidate::logicalChunkId)
                .contains(
                        "g2-architecture#PostgreSQL 原生 BM25 迁移#0",
                        "g2-architecture#API 路径与标题通道#0",
                        "architecture.pdf#第 2 页#0"
                );
        Set<String> answerableGold = dataset.cases().stream()
                .filter(item -> !item.shouldAbstain())
                .flatMap(item -> item.goldChunkIds().stream())
                .collect(java.util.stream.Collectors.toSet());
        assertThat(fixture.logicalChunkIdByRuntimeUuid().values()).containsAll(answerableGold);
        assertThat(fixture.fixtureSha256()).matches("[0-9a-f]{64}");
    }
}
