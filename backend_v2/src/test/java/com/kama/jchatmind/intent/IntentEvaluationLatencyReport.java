package com.kama.jchatmind.intent;

record IntentEvaluationLatencyReport(
        String datasetId,
        String datasetCaseSha256,
        String decisionVersion,
        String policyVersion,
        String baselineMode,
        String candidateMode,
        String measurementEnvironment,
        int caseCount,
        int warmupPasses,
        int measuredPasses,
        int sampleCountPerMode,
        long baselineP95Nanos,
        long contextualP95Nanos,
        double relativeP95Increase,
        boolean withinBudget
) {
}
