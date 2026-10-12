package com.kama.jchatmind.rag;

import com.kama.jchatmind.rag.G2CurrentFixtureAnswerQualityReport.CaseScore;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

class G2CurrentFixtureAnswerQualityComparisonEvaluatorTest {

    @Test
    void comparesOnlyPairedCasesAndKeepsNoAnswerSkipsOutOfAnswerQualityMeans() {
        G2CurrentFixtureAnswerQualityReport fixed = report(List.of(
                score("answer-1", 0.8D, 0.6D),
                score("answer-2", 0.6D, 0.8D),
                skipped("no-answer", "no_answer_case")
        ));
        G2CurrentFixtureAnswerQualityReport router = report(List.of(
                score("answer-1", 0.9D, 0.7D),
                score("answer-2", 0.5D, 0.7D),
                skipped("no-answer", "no_answer_case")
        ));

        G2CurrentFixtureAnswerQualityComparisonEvaluator.Report comparison =
                new G2CurrentFixtureAnswerQualityComparisonEvaluator().compare(fixed, router);

        assertThat(comparison.status()).isEqualTo("complete");
        assertThat(comparison.pairedCaseCount()).isEqualTo(2);
        assertThat(comparison.unpairedCaseCount()).isZero();
        assertThat(comparison.jointlySkippedCaseCount()).isEqualTo(1);
        assertThat(comparison.meanFaithfulnessDelta()).isCloseTo(0D, offset(1.0e-12D));
        assertThat(comparison.meanAnswerRelevancyDelta()).isCloseTo(0D, offset(1.0e-12D));
        assertThat(comparison.gateStatus()).isEqualTo("not_configured");
    }

    @Test
    void marksOneArmSkipsAsUnpairedInsteadOfChangingTheComparisonDenominator() {
        G2CurrentFixtureAnswerQualityReport fixed = report(List.of(
                score("answer-1", 0.8D, 0.6D),
                score("answer-2", 0.6D, 0.8D),
                skipped("no-answer", "no_answer_case")
        ));
        G2CurrentFixtureAnswerQualityReport router = report(List.of(
                score("answer-1", 0.9D, 0.7D),
                skipped("answer-2", "judge_error"),
                skipped("no-answer", "no_answer_case")
        ));

        G2CurrentFixtureAnswerQualityComparisonEvaluator.Report comparison =
                new G2CurrentFixtureAnswerQualityComparisonEvaluator().compare(fixed, router);

        assertThat(comparison.status()).isEqualTo("partial");
        assertThat(comparison.pairedCaseCount()).isEqualTo(1);
        assertThat(comparison.unpairedCaseCount()).isEqualTo(1);
        assertThat(comparison.unpairedCaseIds()).containsExactly("answer-2");
        assertThat(comparison.meanFaithfulnessDelta()).isCloseTo(0.1D, offset(1.0e-12D));
    }

    @Test
    void rejectsDifferentJudgeModelsOrCaseDenominators() {
        G2CurrentFixtureAnswerQualityReport fixed = report(List.of(
                score("answer-1", 0.8D, 0.6D),
                score("answer-2", 0.6D, 0.8D),
                skipped("no-answer", "no_answer_case")
        ));
        G2CurrentFixtureAnswerQualityReport wrongModel = report("other-judge", fixed.cases());
        G2CurrentFixtureAnswerQualityReport wrongCases = report(List.of(
                score("answer-1", 0.8D, 0.6D),
                skipped("answer-3", "judge_error"),
                skipped("no-answer", "no_answer_case")
        ));
        G2CurrentFixtureAnswerQualityComparisonEvaluator evaluator =
                new G2CurrentFixtureAnswerQualityComparisonEvaluator();

        assertThatThrownBy(() -> evaluator.compare(fixed, wrongModel))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> evaluator.compare(fixed, wrongCases))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsDeclaredEvaluationCountsThatDoNotMatchCaseScores() {
        G2CurrentFixtureAnswerQualityReport fixed = report(List.of(
                score("answer-1", 0.8D, 0.6D),
                score("answer-2", 0.6D, 0.8D),
                skipped("no-answer", "no_answer_case")
        ));
        G2CurrentFixtureAnswerQualityReport router = report(List.of(
                score("answer-1", 0.9D, 0.7D),
                score("answer-2", 0.5D, 0.7D),
                skipped("no-answer", "no_answer_case")
        ));
        G2CurrentFixtureAnswerQualityReport inconsistent = new G2CurrentFixtureAnswerQualityReport(
                router.datasetId(),
                router.judgeModel(),
                router.status(),
                router.caseCount(),
                router.answerableCaseCount(),
                router.noAnswerCaseCount(),
                router.evaluatedCaseCount() - 1,
                router.skippedCaseCount() + 1,
                router.judgeCallCount(),
                router.judgeP95LatencyMs(),
                router.meanFaithfulness(),
                router.meanAnswerRelevancy(),
                router.skipReasons(),
                router.cases(),
                router.answerQualityMeasured(),
                router.qualityGateStatus()
        );

        assertThatThrownBy(() -> new G2CurrentFixtureAnswerQualityComparisonEvaluator()
                .compare(fixed, inconsistent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("counts");
    }

    @Test
    void rejectsSkipReasonSummariesThatDoNotMatchSkippedCases() {
        G2CurrentFixtureAnswerQualityReport fixed = report(List.of(
                score("answer-1", 0.8D, 0.6D),
                score("answer-2", 0.6D, 0.8D),
                skipped("no-answer", "no_answer_case")
        ));
        G2CurrentFixtureAnswerQualityReport router = report(List.of(
                score("answer-1", 0.9D, 0.7D),
                score("answer-2", 0.5D, 0.7D),
                skipped("no-answer", "no_answer_case")
        ));

        assertThatThrownBy(() -> new G2CurrentFixtureAnswerQualityComparisonEvaluator()
                .compare(fixed, withSkipReasons(router, Map.of("no_answer_case", 2))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("skip reasons");
    }

    private G2CurrentFixtureAnswerQualityReport withSkipReasons(
            G2CurrentFixtureAnswerQualityReport report,
            Map<String, Integer> skipReasons
    ) {
        return new G2CurrentFixtureAnswerQualityReport(
                report.datasetId(),
                report.judgeModel(),
                report.status(),
                report.caseCount(),
                report.answerableCaseCount(),
                report.noAnswerCaseCount(),
                report.evaluatedCaseCount(),
                report.skippedCaseCount(),
                report.judgeCallCount(),
                report.judgeP95LatencyMs(),
                report.meanFaithfulness(),
                report.meanAnswerRelevancy(),
                skipReasons,
                report.cases(),
                report.answerQualityMeasured(),
                report.qualityGateStatus()
        );
    }

    private G2CurrentFixtureAnswerQualityReport report(List<CaseScore> cases) {
        return report("judge-v1", cases);
    }

    private G2CurrentFixtureAnswerQualityReport report(String judgeModel, List<CaseScore> cases) {
        int evaluated = (int) cases.stream().filter(CaseScore::evaluated).count();
        Map<String, Integer> skipReasons = new LinkedHashMap<>();
        cases.stream()
                .filter(score -> !score.evaluated())
                .forEach(score -> skipReasons.merge(score.skipReason(), 1, Integer::sum));
        return new G2CurrentFixtureAnswerQualityReport(
                "fixture-fast-v1",
                judgeModel,
                evaluated == 2 ? "complete" : "partial",
                3,
                2,
                1,
                evaluated,
                cases.size() - evaluated,
                evaluated,
                50,
                0.7D,
                0.7D,
                skipReasons,
                cases,
                evaluated > 0,
                "not_configured"
        );
    }

    private CaseScore score(String caseId, double faithfulness, double relevancy) {
        return new CaseScore(caseId, true, null, faithfulness, relevancy, false, 10);
    }

    private CaseScore skipped(String caseId, String reason) {
        return new CaseScore(caseId, false, reason, null, null, false, 0);
    }
}
