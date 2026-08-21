package com.kama.jchatmind.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagEvaluationDatasetLoaderTest {

    @Test
    void loadsAndValidatesFrozenFixtureFastDataset() throws Exception {
        RagEvaluationDataset dataset = RagEvaluationDatasetLoader.load("rag-eval/datasets/manifests/fixture-fast-v1.json");

        assertEquals("fixture-fast-v1", dataset.manifest().datasetId());
        assertEquals("frozen", dataset.manifest().status());
        assertEquals(20, dataset.cases().size());
        assertTrue(dataset.cases().stream().anyMatch(RagEvaluationCase::shouldAbstain));
        assertTrue(dataset.cases().stream().anyMatch(item -> item.conversation().size() > 1));
    }

    @Test
    void loadsFrozenG2PreBm25DatasetWithRequiredCoverage() throws Exception {
        RagEvaluationDataset dataset = RagEvaluationDatasetLoader.load("rag-eval/datasets/manifests/g2-pre-bm25-v1.json");

        assertEquals("g2-pre-bm25-v1", dataset.manifest().datasetId());
        assertEquals("frozen", dataset.manifest().status());
        assertEquals(9, dataset.cases().size());
        assertEquals(2, dataset.cases().stream().filter(RagEvaluationCase::shouldAbstain).count());
        assertTrue(List.of(
                "chinese-technical-term",
                "code-identifier",
                "title-exact",
                "content-exact",
                "multi-turn",
                "topic-switch",
                "no-answer",
                "permission-boundary",
                "pdf-page"
        ).stream().allMatch(label -> dataset.cases().stream().anyMatch(item -> item.labels().contains(label))));
    }

    @Test
    void rejectsInvalidFrozenCasesBeforeTheyEnterRegressionDataset() {
        RagEvaluationCase abstentionCaseWithGold = new RagEvaluationCase(
                "invalid-1", "fixture-fast-v1", "无答案", "no_answer", "easy", List.of(),
                List.of("fixture-ecommerce"), List.of("orders-refund#订单退款#0"), List.of(), true,
                "missing_evidence", List.of(), List.of("no-answer"), "manual", "approved"
        );

        assertThrows(IllegalStateException.class, () -> RagEvaluationDatasetLoader.validateCases(
                List.of(abstentionCaseWithGold), "fixture-fast-v1"));
    }

    @Test
    void rejectsDuplicateCaseIdsBeforeTheyEnterRegressionDataset() {
        RagEvaluationCase first = new RagEvaluationCase(
                "duplicate-1", "fixture-fast-v1", "退款规则", "content_rewrite", "easy", List.of(),
                List.of("fixture-ecommerce"), List.of("orders-refund#订单退款#0"), List.of("退款规则"), false,
                null, List.of("orders-refund"), List.of(), "manual", "approved"
        );

        assertThrows(IllegalStateException.class, () -> RagEvaluationDatasetLoader.validateCases(
                List.of(first, first), "fixture-fast-v1"));
    }
}
