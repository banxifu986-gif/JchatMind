package com.kama.jchatmind.rag;

import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class G2CurrentFixtureAnswerQualityComparisonEvaluator {

    Report compare(
            G2CurrentFixtureAnswerQualityReport fixed,
            G2CurrentFixtureAnswerQualityReport router
    ) {
        validatePair(fixed, router);
        Map<String, G2CurrentFixtureAnswerQualityReport.CaseScore> routerCases = index(router);
        List<CaseDelta> pairedCases = new ArrayList<>();
        List<String> unpairedCaseIds = new ArrayList<>();
        int jointlySkippedCaseCount = 0;
        double faithfulnessDeltaTotal = 0D;
        double answerRelevancyDeltaTotal = 0D;

        for (G2CurrentFixtureAnswerQualityReport.CaseScore fixedCase : fixed.cases()) {
            G2CurrentFixtureAnswerQualityReport.CaseScore routerCase = routerCases.get(fixedCase.caseId());
            if (fixedCase.evaluated() && routerCase.evaluated()) {
                double faithfulnessDelta = routerCase.faithfulness() - fixedCase.faithfulness();
                double answerRelevancyDelta = routerCase.answerRelevancy() - fixedCase.answerRelevancy();
                pairedCases.add(new CaseDelta(
                        fixedCase.caseId(),
                        fixedCase.faithfulness(),
                        routerCase.faithfulness(),
                        faithfulnessDelta,
                        fixedCase.answerRelevancy(),
                        routerCase.answerRelevancy(),
                        answerRelevancyDelta
                ));
                faithfulnessDeltaTotal += faithfulnessDelta;
                answerRelevancyDeltaTotal += answerRelevancyDelta;
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
                fixed.caseCount(),
                pairedCaseCount,
                unpairedCaseIds.size(),
                jointlySkippedCaseCount,
                pairedCaseCount == 0 ? 0D : faithfulnessDeltaTotal / pairedCaseCount,
                pairedCaseCount == 0 ? 0D : answerRelevancyDeltaTotal / pairedCaseCount,
                fixed.judgeP95LatencyMs(),
                router.judgeP95LatencyMs(),
                List.copyOf(unpairedCaseIds),
                List.copyOf(pairedCases),
                "not_configured"
        );
    }

    private void validatePair(
            G2CurrentFixtureAnswerQualityReport fixed,
            G2CurrentFixtureAnswerQualityReport router
    ) {
        if (fixed == null || router == null
                || !"fixture-fast-v1".equals(fixed.datasetId())
                || !fixed.datasetId().equals(router.datasetId())
                || !java.util.Objects.equals(fixed.judgeModel(), router.judgeModel())
                || fixed.caseCount() != router.caseCount()
                || fixed.answerableCaseCount() != router.answerableCaseCount()
                || fixed.noAnswerCaseCount() != router.noAnswerCaseCount()
                || fixed.cases() == null || router.cases() == null
                || fixed.cases().size() != fixed.caseCount()
                || router.cases().size() != router.caseCount()) {
            throw new IllegalArgumentException("current fixture answer-quality arms are not comparable");
        }
        Set<String> fixedIds = caseIds(fixed.cases());
        Set<String> routerIds = caseIds(router.cases());
        if (!fixedIds.equals(routerIds)) {
            throw new IllegalArgumentException("current fixture answer-quality arms must have identical case IDs");
        }
        validateDeclaredCounts(fixed);
        validateDeclaredCounts(router);
        if (!StringUtils.hasText(fixed.judgeModel())) {
            throw new IllegalArgumentException("answer-quality comparison requires the same named judge model");
        }
    }

    private void validateDeclaredCounts(G2CurrentFixtureAnswerQualityReport report) {
        long evaluatedCount = report.cases().stream().filter(G2CurrentFixtureAnswerQualityReport.CaseScore::evaluated)
                .count();
        long skippedCount = report.cases().size() - evaluatedCount;
        if (report.evaluatedCaseCount() != evaluatedCount || report.skippedCaseCount() != skippedCount) {
            throw new IllegalArgumentException("answer-quality report counts do not match case scores");
        }
        Map<String, Integer> actualSkipReasons = new HashMap<>();
        report.cases().stream()
                .filter(score -> !score.evaluated())
                .forEach(score -> actualSkipReasons.merge(score.skipReason(), 1, Integer::sum));
        if (!report.skipReasons().equals(actualSkipReasons)) {
            throw new IllegalArgumentException("answer-quality report skip reasons do not match case scores");
        }
    }

    private Set<String> caseIds(List<G2CurrentFixtureAnswerQualityReport.CaseScore> scores) {
        Set<String> caseIds = new HashSet<>();
        for (G2CurrentFixtureAnswerQualityReport.CaseScore score : scores) {
            if (score == null || !StringUtils.hasText(score.caseId()) || !caseIds.add(score.caseId())) {
                throw new IllegalArgumentException("answer-quality report case ID is missing or duplicated");
            }
            if (score.evaluated()
                    && (score.faithfulness() == null || score.answerRelevancy() == null)) {
                throw new IllegalArgumentException("evaluated answer-quality case is missing a score");
            }
            if (!score.evaluated() && !StringUtils.hasText(score.skipReason())) {
                throw new IllegalArgumentException("skipped answer-quality case is missing a skip reason");
            }
        }
        return caseIds;
    }

    private Map<String, G2CurrentFixtureAnswerQualityReport.CaseScore> index(
            G2CurrentFixtureAnswerQualityReport report
    ) {
        Map<String, G2CurrentFixtureAnswerQualityReport.CaseScore> byId = new HashMap<>();
        for (G2CurrentFixtureAnswerQualityReport.CaseScore score : report.cases()) {
            byId.put(score.caseId(), score);
        }
        return byId;
    }

    record CaseDelta(
            String caseId,
            double fixedFaithfulness,
            double routerFaithfulness,
            double faithfulnessDelta,
            double fixedAnswerRelevancy,
            double routerAnswerRelevancy,
            double answerRelevancyDelta
    ) {
    }

    record Report(
            String datasetId,
            String judgeModel,
            String status,
            int caseCount,
            int pairedCaseCount,
            int unpairedCaseCount,
            int jointlySkippedCaseCount,
            double meanFaithfulnessDelta,
            double meanAnswerRelevancyDelta,
            long fixedJudgeP95LatencyMs,
            long routerJudgeP95LatencyMs,
            List<String> unpairedCaseIds,
            List<CaseDelta> pairedCases,
            String gateStatus
    ) {
        Report {
            unpairedCaseIds = List.copyOf(unpairedCaseIds);
            pairedCases = List.copyOf(pairedCases);
        }
    }
}
