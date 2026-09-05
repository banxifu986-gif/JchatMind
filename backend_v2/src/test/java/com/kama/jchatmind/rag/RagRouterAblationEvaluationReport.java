package com.kama.jchatmind.rag;

import java.util.List;

record RagRouterAblationEvaluationReport(
        String datasetVersion,
        VariantSummary fixed,
        VariantSummary router,
        Gate gate
) {
    record VariantSummary(
            String variant,
            int caseCount,
            int answerableCases,
            double recallAt5,
            double mrrAt10,
            double citationAccuracy,
            double abstentionAccuracy,
            long p95LatencyMs,
            double averageInputTokens,
            int permissionViolations,
            int unauthorizedExternalCalls,
            int routeViolations
    ) {
    }

    record Gate(String verdict, List<String> reasons) {
    }
}
