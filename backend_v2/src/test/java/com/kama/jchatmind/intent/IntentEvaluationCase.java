package com.kama.jchatmind.intent;

import java.util.List;

record IntentEvaluationCase(
        String caseId,
        String datasetId,
        String split,
        String query,
        String previousUserQuery,
        String skipReason,
        BusinessIntent expectedBusinessIntent,
        IntentRoute expectedRoute,
        IntentRiskLevel expectedRiskLevel,
        List<String> expectedCapabilities,
        boolean shouldClarify,
        boolean shouldAbstain,
        List<String> tags,
        String reviewStatus
) {
}
