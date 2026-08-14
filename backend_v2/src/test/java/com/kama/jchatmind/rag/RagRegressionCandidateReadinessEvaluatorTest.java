package com.kama.jchatmind.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagRegressionCandidateReadinessEvaluatorTest {

    @Test
    void reportsCoverageAndBlocksFreezingWhenCandidateDataIsIncomplete() throws Exception {
        RagRegressionCandidateDataset dataset = RagRegressionCandidateDatasetLoader.load(
                "rag-eval/datasets/candidates/regression-v1-candidate.json"
        );

        RagRegressionCandidateReadinessReport report = new RagRegressionCandidateReadinessEvaluator().evaluate(dataset);

        assertEquals(58, report.totalCases());
        assertEquals(57, report.eligibleCases());
        assertEquals(40, report.runtimeEligibleCases());
        assertEquals(38, report.runtimeUniqueRetrievalGoldChunks());
        assertEquals(48, report.uniqueRetrievalGoldChunks());
        assertEquals(2, report.abstentionCases());
        assertEquals(1, report.multiTurnCases());
        assertEquals(1, report.crossDocumentCases());
        assertEquals(0, report.candidateCases());
        assertEquals(57, report.approvedCases());
        assertEquals(1, report.rejectedCases());
        assertTrue(!report.freezeBlockers().contains("insufficient_approved_case_count"));
        assertTrue(!report.freezeBlockers().contains("insufficient_runtime_eligible_case_count"));
        assertTrue(!report.freezeBlockers().contains("pending_human_review"));
        assertTrue(report.freezeBlockers().contains("runtime_uuid_mapping_not_completed"));
    }

    @Test
    void doesNotBlockOnCandidateStatusWhenAllRequiredEvidenceExists() {
        RagRegressionCandidateCase approved = new RagRegressionCandidateCase(
                "approved-001", "问题", "user_like_question", "easy", "doc#章节#0", "章节",
                "doc", "a".repeat(64), List.of(), List.of(), List.of("doc#章节#0"), List.of("事实"),
                false, null, "approved", "manual", "reviewer", "2026-08-13T10:00:00+08:00", List.of("test")
        );
        RagRegressionCandidateDataset dataset = new RagRegressionCandidateDataset(
                "ready-candidate", "candidate", "kb", List.of(approved)
        );

        RagRegressionCandidateReadinessReport report = new RagRegressionCandidateReadinessEvaluator().evaluate(
                dataset, new RagRegressionCandidateReadinessEvaluator.Thresholds(1, 0, 0, false)
        );

        assertTrue(!report.freezeBlockers().contains("pending_human_review"));
    }

    @Test
    void excludesRejectedCasesFromFreezeCoverage() {
        RagRegressionCandidateCase rejected = new RagRegressionCandidateCase(
                "rejected-001", "问题", "user_like_question", "easy", "doc#章节#0", "章节",
                "doc", "a".repeat(64), List.of(), List.of(), List.of("doc#章节#0"), List.of("事实"),
                false, null, "rejected", "manual", "reviewer", "2026-08-13T10:00:00+08:00", List.of("test")
        );
        RagRegressionCandidateDataset dataset = new RagRegressionCandidateDataset(
                "ready-candidate", "candidate", "kb", List.of(rejected)
        );

        RagRegressionCandidateReadinessReport report = new RagRegressionCandidateReadinessEvaluator().evaluate(
                dataset, new RagRegressionCandidateReadinessEvaluator.Thresholds(1, 0, 0, false)
        );

        assertEquals(1, report.totalCases());
        assertEquals(0, report.eligibleCases());
        assertEquals(0, report.uniqueRetrievalGoldChunks());
        assertTrue(report.freezeBlockers().contains("insufficient_approved_case_count"));
    }
}
