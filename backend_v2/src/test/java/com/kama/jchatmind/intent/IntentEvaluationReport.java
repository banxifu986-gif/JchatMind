package com.kama.jchatmind.intent;

import java.util.Map;

record IntentEvaluationReport(
        String datasetId,
        String datasetSourceSha256,
        String datasetCaseSha256,
        String decisionVersion,
        String policyVersion,
        IntentEvaluationRunConfiguration runConfiguration,
        int totalCases,
        int evaluatedCases,
        int skippedCases,
        int failedCases,
        int correctCases,
        int decisionContractMatchCases,
        double decisionContractMatchRate,
        double accuracy,
        double macroF1,
        double abstainAccuracy,
        double clarificationAccuracy,
        int sideEffectFalsePositiveCases,
        double sideEffectFalsePositiveRate,
        int highRiskFalsePositiveCases,
        Map<String, IntentClassMetrics> perIntentMetrics,
        Map<String, Integer> confusionMatrix,
        Map<String, Integer> skipReasons,
        Map<String, Integer> failureReasons,
        Map<String, IntentEvaluationSplitMetrics> splitMetrics
) {
}
