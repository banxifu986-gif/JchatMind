package com.kama.jchatmind.rag;

import java.util.List;
import java.util.Map;

record RagRegressionCandidateReadinessReport(
        String datasetId,
        int totalCases,
        int eligibleCases,
        int runtimeEligibleCases,
        int uniqueRetrievalGoldChunks,
        int runtimeUniqueRetrievalGoldChunks,
        int abstentionCases,
        int multiTurnCases,
        int crossDocumentCases,
        int candidateCases,
        int approvedCases,
        int rejectedCases,
        Map<String, Integer> queryTypeCounts,
        List<String> freezeBlockers
) {
}
