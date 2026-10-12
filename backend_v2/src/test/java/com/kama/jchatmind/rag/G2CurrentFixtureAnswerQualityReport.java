package com.kama.jchatmind.rag;

import java.util.List;
import java.util.Map;

record G2CurrentFixtureAnswerQualityReport(
        String datasetId,
        String judgeModel,
        String status,
        int caseCount,
        int answerableCaseCount,
        int noAnswerCaseCount,
        int evaluatedCaseCount,
        int skippedCaseCount,
        int judgeCallCount,
        long judgeP95LatencyMs,
        double meanFaithfulness,
        double meanAnswerRelevancy,
        Map<String, Integer> skipReasons,
        List<CaseScore> cases,
        boolean answerQualityMeasured,
        String qualityGateStatus
) {
    G2CurrentFixtureAnswerQualityReport {
        skipReasons = Map.copyOf(skipReasons);
        cases = List.copyOf(cases);
    }

    record CaseScore(
            String caseId,
            boolean evaluated,
            String skipReason,
            Double faithfulness,
            Double answerRelevancy,
            boolean contextTruncated,
            long judgeLatencyMs
    ) {
    }
}
