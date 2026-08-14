package com.kama.jchatmind.rag;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagRegressionCandidateDatasetTest {

    @Test
    void loadsContentReviewedCandidateDatasetWithoutTreatingItAsFrozenRegressionBaseline() throws Exception {
        RagRegressionCandidateDataset dataset = RagRegressionCandidateDatasetLoader.load(
                "rag-eval/datasets/candidates/regression-v1-candidate.json"
        );

        assertEquals("regression-v1-candidate", dataset.datasetId());
        assertEquals("candidate", dataset.status());
        assertEquals("34d6eabb-9823-434a-9966-bc9eaa103739", dataset.sourceKnowledgeBaseId());
        assertEquals(58, dataset.cases().size());
        assertEquals(57, dataset.cases().stream().filter(item -> "approved".equals(item.reviewStatus())).count());
        assertEquals(1, dataset.cases().stream().filter(item -> "rejected".equals(item.reviewStatus())).count());
        assertTrue(dataset.cases().stream().anyMatch(item -> "reg-candidate-022".equals(item.caseId())
                && "rejected".equals(item.reviewStatus())));
        assertTrue(dataset.cases().stream().allMatch(item -> "manual".equals(item.createdBy())
                && "codex_content_review".equals(item.reviewedBy())
                && item.reviewedAt() != null));
        assertTrue(dataset.cases().stream().allMatch(item -> item.sourceDocumentSha256().matches("[0-9a-f]{64}")));
        assertTrue(dataset.cases().stream().allMatch(item -> item.shouldAbstain() != null));
        assertTrue(dataset.cases().stream().allMatch(item -> item.conversation() != null));
        assertTrue(dataset.cases().stream().allMatch(item -> item.additionalGoldLogicalChunkIds() != null));
        assertTrue(dataset.cases().stream().allMatch(item -> item.retrievalGoldLogicalChunkIds() != null));
        assertEquals(2, dataset.cases().stream().filter(item -> Boolean.TRUE.equals(item.shouldAbstain())).count());
        assertEquals(1, dataset.cases().stream().filter(item -> !item.conversation().isEmpty()).count());
        assertEquals(1, dataset.cases().stream().filter(item -> !item.additionalGoldLogicalChunkIds().isEmpty()).count());
        assertTrue(dataset.cases().stream()
                .filter(item -> Boolean.TRUE.equals(item.shouldAbstain()))
                .allMatch(item -> item.abstentionReason() != null && item.goldFacts().isEmpty()
                        && item.retrievalGoldLogicalChunkIds().isEmpty()));
        assertTrue(dataset.cases().stream()
                .filter(item -> Boolean.FALSE.equals(item.shouldAbstain()))
                .allMatch(item -> new LinkedHashSet<>(item.retrievalGoldLogicalChunkIds()).equals(expectedRetrievalGold(item))
                        && item.retrievalGoldLogicalChunkIds().size() == expectedRetrievalGold(item).size()));
    }

    private LinkedHashSet<String> expectedRetrievalGold(RagRegressionCandidateCase item) {
        LinkedHashSet<String> expected = new LinkedHashSet<>();
        expected.add(item.logicalChunkId());
        expected.addAll(item.additionalGoldLogicalChunkIds());
        return expected;
    }

    @Test
    void rejectsCrossDocumentCandidateWhenAdditionalGoldIsAbsentFromRetrievalGold() {
        RagRegressionCandidateCase item = new RagRegressionCandidateCase(
                "candidate-cross", "跨文档问题", "cross_document", "hard",
                "interview#主#0", "主章节", "interview", "a".repeat(64),
                List.of(), List.of("sql#辅助#0"), List.of("interview#主#0"), List.of("需要两份证据"),
                false, null, "candidate", null, null, null, List.of("cross-document")
        );
        RagRegressionCandidateDataset dataset = new RagRegressionCandidateDataset(
                "regression-v1-candidate", "candidate", "kb-id", List.of(item)
        );

        assertThrows(IllegalStateException.class, () -> RagRegressionCandidateDatasetLoader.validate(dataset));
    }

    @Test
    void rejectsReviewedCandidateWithoutReviewerAndTimestamp() {
        RagRegressionCandidateCase item = new RagRegressionCandidateCase(
                "candidate-reviewed", "问题", "user_like_question", "easy",
                "doc#章节#0", "章节", "doc", "a".repeat(64),
                List.of(), List.of(), List.of("doc#章节#0"), List.of("事实"),
                false, null, "approved", "manual", null, null, List.of("test")
        );
        RagRegressionCandidateDataset dataset = new RagRegressionCandidateDataset(
                "regression-v1-candidate", "candidate", "kb-id", List.of(item)
        );

        assertThrows(IllegalStateException.class, () -> RagRegressionCandidateDatasetLoader.validate(dataset));
    }

    @Test
    void rejectsCandidateWithoutCreator() {
        RagRegressionCandidateCase item = new RagRegressionCandidateCase(
                "candidate-unattributed", "问题", "user_like_question", "easy",
                "doc#章节#0", "章节", "doc", "a".repeat(64),
                List.of(), List.of(), List.of("doc#章节#0"), List.of("事实"),
                false, null, "candidate", null, null, null, List.of("test")
        );
        RagRegressionCandidateDataset dataset = new RagRegressionCandidateDataset(
                "regression-v1-candidate", "candidate", "kb-id", List.of(item)
        );

        assertThrows(IllegalStateException.class, () -> RagRegressionCandidateDatasetLoader.validate(dataset));
    }
}
