package com.kama.jchatmind.rag;

record RagRegressionCandidateRuntimeEvaluationReport(
        String datasetId,
        String executionMode,
        int evaluatedCases,
        int answerableCases,
        int abstentionCases,
        double recallAt5,
        double mrrAt3,
        double contextPrecisionAt5,
        double contextRecallAt5,
        double abstentionAccuracy
) {
}
