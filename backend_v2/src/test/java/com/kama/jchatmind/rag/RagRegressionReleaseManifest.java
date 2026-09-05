package com.kama.jchatmind.rag;

import java.util.List;

record RagRegressionReleaseManifest(
        String datasetId,
        String status,
        String sourceCandidateDatasetId,
        String sourceCandidateResource,
        String sourceCandidateSha256,
        String tuningStatus,
        List<String> includedCaseIds,
        List<String> excludedDevelopmentCaseIds,
        String frozenAt
) {
}
