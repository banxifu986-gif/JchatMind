package com.kama.jchatmind.intent;

import java.util.Map;

record IntentEvaluationReportComparison(
        String datasetId,
        String datasetCaseSha256,
        String decisionVersion,
        String baselinePolicyVersion,
        String candidatePolicyVersion,
        IntentEvaluationRunConfiguration baselineRunConfiguration,
        IntentEvaluationRunConfiguration candidateRunConfiguration,
        double accuracyDelta,
        double macroF1Delta,
        double decisionContractMatchRateDelta,
        double testAccuracyDelta,
        double testMacroF1Delta,
        Map<String, Double> perIntentF1Delta
) {
}
