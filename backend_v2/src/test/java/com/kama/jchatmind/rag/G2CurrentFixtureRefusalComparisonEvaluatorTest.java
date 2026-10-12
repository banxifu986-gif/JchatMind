package com.kama.jchatmind.rag;

import com.kama.jchatmind.rag.G2CurrentFixtureRefusalEvaluator.CaseScore;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.data.Offset.offset;

class G2CurrentFixtureRefusalComparisonEvaluatorTest {

    @Test
    void comparesOnlyCasesEvaluatedByBothArms() {
        G2CurrentFixtureRefusalEvaluator.Report fixed = report("judge-v1", List.of(
                score("abstain-1", true),
                score("abstain-2", false)
        ));
        G2CurrentFixtureRefusalEvaluator.Report router = report("judge-v1", List.of(
                score("abstain-1", true),
                score("abstain-2", true)
        ));

        G2CurrentFixtureRefusalComparisonEvaluator.Report comparison =
                new G2CurrentFixtureRefusalComparisonEvaluator().compare(fixed, router);

        assertThat(comparison.status()).isEqualTo("complete");
        assertThat(comparison.pairedCaseCount()).isEqualTo(2);
        assertThat(comparison.unpairedCaseCount()).isZero();
        assertThat(comparison.fixedAbstentionAccuracy()).isEqualTo(0.5D);
        assertThat(comparison.routerAbstentionAccuracy()).isEqualTo(1D);
        assertThat(comparison.abstentionAccuracyDelta()).isCloseTo(0.5D, offset(1.0e-12D));
        assertThat(comparison.caseDeltas()).extracting(
                G2CurrentFixtureRefusalComparisonEvaluator.CaseDelta::delta
        ).containsExactly(0, 1);
        assertThat(comparison.gateStatus()).isEqualTo("not_configured");
    }

    @Test
    void excludesSingleArmSkipsFromThePairedDenominator() {
        G2CurrentFixtureRefusalEvaluator.Report fixed = report("judge-v1", List.of(
                score("abstain-1", false),
                score("abstain-2", true)
        ));
        G2CurrentFixtureRefusalEvaluator.Report router = report("judge-v1", List.of(
                score("abstain-1", false),
                skipped("abstain-2", "judge_error")
        ));

        G2CurrentFixtureRefusalComparisonEvaluator.Report comparison =
                new G2CurrentFixtureRefusalComparisonEvaluator().compare(fixed, router);

        assertThat(comparison.status()).isEqualTo("partial");
        assertThat(comparison.pairedCaseCount()).isEqualTo(1);
        assertThat(comparison.unpairedCaseCount()).isEqualTo(1);
        assertThat(comparison.unpairedCaseIds()).containsExactly("abstain-2");
        assertThat(comparison.fixedAbstentionAccuracy()).isZero();
        assertThat(comparison.routerAbstentionAccuracy()).isZero();
    }

    @Test
    void rejectsDifferentJudgeModelsOrInconsistentSummaryCounts() {
        G2CurrentFixtureRefusalEvaluator.Report fixed = report("judge-v1", List.of(
                score("abstain-1", true),
                skipped("abstain-2", "judge_error")
        ));
        G2CurrentFixtureRefusalEvaluator.Report wrongModel = report("other-judge", fixed.cases());
        G2CurrentFixtureRefusalEvaluator.Report inconsistent = withCorrectCount(
                fixed, fixed.correctAbstentionCount() + 1
        );
        G2CurrentFixtureRefusalComparisonEvaluator evaluator =
                new G2CurrentFixtureRefusalComparisonEvaluator();

        assertThatThrownBy(() -> evaluator.compare(fixed, wrongModel))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> evaluator.compare(fixed, inconsistent))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("counts");
    }

    private G2CurrentFixtureRefusalEvaluator.Report report(String judgeModel, List<CaseScore> cases) {
        int evaluated = (int) cases.stream().filter(CaseScore::evaluated).count();
        int correct = (int) cases.stream()
                .filter(CaseScore::evaluated)
                .filter(score -> Boolean.TRUE.equals(score.correctAbstention()))
                .count();
        Map<String, Integer> skipReasons = new LinkedHashMap<>();
        cases.stream()
                .filter(score -> !score.evaluated())
                .forEach(score -> skipReasons.merge(score.skipReason(), 1, Integer::sum));
        return new G2CurrentFixtureRefusalEvaluator.Report(
                "fixture-fast-v1",
                judgeModel,
                evaluated == cases.size() ? "complete" : "partial",
                cases.size(),
                evaluated,
                cases.size() - evaluated,
                correct,
                evaluated == 0 ? 0D : correct / (double) evaluated,
                evaluated,
                25L,
                skipReasons,
                cases,
                "not_configured"
        );
    }

    private G2CurrentFixtureRefusalEvaluator.Report withCorrectCount(
            G2CurrentFixtureRefusalEvaluator.Report report,
            int correctCount
    ) {
        return new G2CurrentFixtureRefusalEvaluator.Report(
                report.datasetId(),
                report.judgeModel(),
                report.status(),
                report.noAnswerCaseCount(),
                report.evaluatedCaseCount(),
                report.skippedCaseCount(),
                correctCount,
                report.abstentionAccuracy(),
                report.judgeCallCount(),
                report.judgeP95LatencyMs(),
                report.skipReasons(),
                report.cases(),
                report.qualityGateStatus()
        );
    }

    private CaseScore score(String caseId, boolean correct) {
        return new CaseScore(caseId, true, null, correct, false, 5L);
    }

    private CaseScore skipped(String caseId, String reason) {
        return new CaseScore(caseId, false, reason, null, false, 0L);
    }
}
