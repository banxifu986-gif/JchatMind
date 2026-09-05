package com.kama.jchatmind.rag;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagRegressionReleaseV1FreezeTest {

    private static final String MANIFEST_RESOURCE =
            "rag-eval/datasets/releases/regression-v1.json";
    private static final String CANDIDATE_RESOURCE =
            "rag-eval/datasets/candidates/regression-v1-candidate.json";

    @Test
    void freezesOnlyApprovedCasesAgainstAnImmutableUntunedCandidateSnapshot() throws Exception {
        RagRegressionReleaseDataset release = RagRegressionReleaseDatasetLoader.load(
                MANIFEST_RESOURCE, CANDIDATE_RESOURCE
        );

        assertEquals("regression-v1", release.manifest().datasetId());
        assertEquals("untouched_test", release.manifest().status());
        assertEquals("not_used_for_tuning", release.manifest().tuningStatus());
        assertEquals(57, release.cases().size());
        assertTrue(release.cases().stream().allMatch(item -> "approved".equals(item.reviewStatus())));
        assertEquals(57, release.manifest().includedCaseIds().size());
        assertEquals(4, release.manifest().excludedDevelopmentCaseIds().size());
        assertTrue(release.manifest().excludedDevelopmentCaseIds().containsAll(List.of(
                "g2-pre-bm25-v1-007-abstention",
                "g2-pre-bm25-v1-008-abstention",
                "g2-pre-bm25-v1-002-rank-regression",
                "g2-pre-bm25-v1-009-citation-rank-regression"
        )));
    }

    @Test
    void rejectsAReleaseManifestWhenTheCandidateSnapshotHashChanges() throws Exception {
        RagRegressionCandidateDataset candidate = RagRegressionCandidateDatasetLoader.load(CANDIDATE_RESOURCE);
        RagRegressionReleaseManifest manifest = new RagRegressionReleaseManifest(
                "regression-v1", "untouched_test", "regression-v1-candidate", CANDIDATE_RESOURCE,
                "0".repeat(64), "not_used_for_tuning", List.of("reg-candidate-001"),
                List.of("development-case"), "2026-09-01"
        );

        assertThrows(IllegalStateException.class, () -> RagRegressionReleaseDatasetLoader.validate(
                manifest, candidate, "a".repeat(64)
        ));
    }
}
