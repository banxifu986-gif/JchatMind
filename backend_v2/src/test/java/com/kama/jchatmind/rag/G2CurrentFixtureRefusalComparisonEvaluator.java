package com.kama.jchatmind.rag;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class G2CurrentFixtureRefusalComparisonEvaluator {

    Report compare(
            G2CurrentFixtureRefusalEvaluator.Report fixed,
            G2CurrentFixtureRefusalEvaluator.Report router
    ) {
        validatePair(fixed, router);
        Map<String, G2CurrentFixtureRefusalEvaluator.CaseScore> routerCases = index(router);
        List<CaseDelta> pairedCases = new ArrayList<>();
        List<String> unpairedCaseIds = new ArrayList<>();
        int jointlySkippedCaseCount = 0;
        int fixedCorrectCount = 0;
        int routerCorrectCount = 0;

        for (G2CurrentFixtureRefusalEvaluator.CaseScore fixedCase : fixed.cases()) {
            G2CurrentFixtureRefusalEvaluator.CaseScore routerCase = routerCases.get(fixedCase.caseId());
            if (fixedCase.evaluated() && routerCase.evaluated()) {
                boolean fixedCorrect = fixedCase.correctAbstention();
                boolean routerCorrect = routerCase.correctAbstention();
                pairedCases.add(new CaseDelta(
                        fixedCase.caseId(), fixedCorrect, routerCorrect,
                        (routerCorrect ? 1 : 0) - (fixedCorrect ? 1 : 0)
                ));
                if (fixedCorrect) {
                    fixedCorrectCount++;
                }
                if (routerCorrect) {
                    routerCorrectCount++;
                }
            } else if (fixedCase.evaluated() || routerCase.evaluated()) {
                unpairedCaseIds.add(fixedCase.caseId());
            } else {
                jointlySkippedCaseCount++;
            }
        }

        int pairedCaseCount = pairedCases.size();
        String status = pairedCaseCount == 0 ? "skipped"
                : unpairedCaseIds.isEmpty() ? "complete" : "partial";
        return new Report(
                fixed.datasetId(),
                fixed.judgeModel(),
                status,
                fixed.noAnswerCaseCount(),
                pairedCaseCount,
                unpairedCaseIds.size(),
                jointlySkippedCaseCount,
                fixedCorrectCount,
                routerCorrectCount,
                pairedCaseCount == 0 ? 0D : fixedCorrectCount / (double) pairedCaseCount,
                pairedCaseCount == 0 ? 0D : routerCorrectCount / (double) pairedCaseCount,
                pairedCaseCount == 0 ? 0D : (routerCorrectCount - fixedCorrectCount) / (double) pairedCaseCount,
                fixed.judgeP95LatencyMs(),
                router.judgeP95LatencyMs(),
                unpairedCaseIds,
                pairedCases,
                "not_configured"
        );
    }

    private void validatePair(
            G2CurrentFixtureRefusalEvaluator.Report fixed,
            G2CurrentFixtureRefusalEvaluator.Report router
    ) {
        if (fixed == null || router == null
                || !"fixture-fast-v1".equals(fixed.datasetId())
                || !fixed.datasetId().equals(router.datasetId())
                || !java.util.Objects.equals(fixed.judgeModel(), router.judgeModel())
                || !StringUtils.hasText(fixed.judgeModel())
                || fixed.noAnswerCaseCount() != router.noAnswerCaseCount()
                || fixed.cases() == null || router.cases() == null
                || fixed.cases().size() != fixed.noAnswerCaseCount()
                || router.cases().size() != router.noAnswerCaseCount()
                || !java.util.Objects.equals(fixed.qualityGateStatus(), router.qualityGateStatus())) {
            throw new IllegalArgumentException("current fixture refusal arms are not comparable");
        }
        Set<String> fixedIds = validateReport(fixed);
        Set<String> routerIds = validateReport(router);
        if (!fixedIds.equals(routerIds)) {
            throw new IllegalArgumentException("current fixture refusal arms must have identical case IDs");
        }
    }

    private Set<String> validateReport(G2CurrentFixtureRefusalEvaluator.Report report) {
        Set<String> caseIds = new HashSet<>();
        Map<String, Integer> actualSkipReasons = new HashMap<>();
        int evaluatedCount = 0;
        int correctCount = 0;
        for (G2CurrentFixtureRefusalEvaluator.CaseScore score : report.cases()) {
            if (score == null || !StringUtils.hasText(score.caseId()) || !caseIds.add(score.caseId())) {
                throw new IllegalArgumentException("refusal report case ID is missing or duplicated");
            }
            if (score.evaluated()) {
                if (score.correctAbstention() == null || StringUtils.hasText(score.skipReason())) {
                    throw new IllegalArgumentException("evaluated refusal case has inconsistent fields");
                }
                evaluatedCount++;
                if (score.correctAbstention()) {
                    correctCount++;
                }
            } else {
                if (score.correctAbstention() != null || !StringUtils.hasText(score.skipReason())) {
                    throw new IllegalArgumentException("skipped refusal case has inconsistent fields");
                }
                actualSkipReasons.merge(score.skipReason(), 1, Integer::sum);
            }
        }
        double expectedAccuracy = evaluatedCount == 0 ? 0D : correctCount / (double) evaluatedCount;
        if (report.evaluatedCaseCount() != evaluatedCount
                || report.skippedCaseCount() != report.noAnswerCaseCount() - evaluatedCount
                || report.correctAbstentionCount() != correctCount
                || Double.compare(report.abstentionAccuracy(), expectedAccuracy) != 0
                || !report.skipReasons().equals(actualSkipReasons)) {
            throw new IllegalArgumentException("refusal report counts or skip reasons do not match case scores");
        }
        return caseIds;
    }

    private Map<String, G2CurrentFixtureRefusalEvaluator.CaseScore> index(
            G2CurrentFixtureRefusalEvaluator.Report report
    ) {
        Map<String, G2CurrentFixtureRefusalEvaluator.CaseScore> byId = new HashMap<>();
        for (G2CurrentFixtureRefusalEvaluator.CaseScore score : report.cases()) {
            byId.put(score.caseId(), score);
        }
        return byId;
    }

    record CaseDelta(String caseId, boolean fixedCorrect, boolean routerCorrect, int delta) {
    }

    record Report(
            String datasetId,
            String judgeModel,
            String status,
            int caseCount,
            int pairedCaseCount,
            int unpairedCaseCount,
            int jointlySkippedCaseCount,
            int fixedCorrectAbstentionCount,
            int routerCorrectAbstentionCount,
            double fixedAbstentionAccuracy,
            double routerAbstentionAccuracy,
            double abstentionAccuracyDelta,
            long fixedJudgeP95LatencyMs,
            long routerJudgeP95LatencyMs,
            List<String> unpairedCaseIds,
            List<CaseDelta> caseDeltas,
            String gateStatus
    ) {
    }
}
