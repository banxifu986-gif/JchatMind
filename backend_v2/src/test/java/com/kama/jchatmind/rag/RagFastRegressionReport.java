package com.kama.jchatmind.rag;

record RagFastRegressionReport(
        String datasetId,
        String manifestSha256,
        String executionMode,
        int total,
        int answerable,
        int abstentionTotal,
        double recallAt5,
        double mrrAt3,
        double contextPrecisionAt5,
        double contextRecallAt5,
        double contextPrecisionAt10,
        double contextRecallAt10,
        double abstentionAccuracy
) {
}
