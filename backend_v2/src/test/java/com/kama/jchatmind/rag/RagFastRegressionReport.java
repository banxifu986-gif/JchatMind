package com.kama.jchatmind.rag;

import java.util.Map;

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
        double abstentionAccuracy,
        long p95LatencyMs,
        RagAsReport ragas
) {
}

record RagAsReport(
        String status,
        int sampleSize,
        int evaluated,
        int skipped,
        Map<String, Integer> skipReasons,
        double contextPrecisionAt5,
        double contextRecallAt5,
        Double faithfulness,
        Double answerRelevancy
) {

    static RagAsReport disabled(double contextPrecisionAt5, double contextRecallAt5) {
        return new RagAsReport(
                "disabled",
                0,
                0,
                0,
                Map.of(),
                contextPrecisionAt5,
                contextRecallAt5,
                null,
                null
        );
    }
}
