package com.kama.jchatmind.intent;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IntentEvaluationReleaseGateTest {

    @Test
    void shouldBlockFixtureWhenRulePolicyDiffersFromFrozenGold() throws Exception {
        IntentEvaluationDataset dataset = IntentEvaluationDatasetLoader.load(
                "intent-eval/datasets/manifests/fixture-fast-v1.json"
        );
        IntentEvaluationReport report = IntentEvaluationEvaluator.evaluate(dataset, new IntentRouter());

        IntentEvaluationGateResult gate = IntentEvaluationReleaseGate.evaluate(report, dataset);

        assertThat(gate.eligible()).isFalse();
        assertThat(gate.blockers()).contains("decision_contract_mismatch", "fixture_only_dataset");
    }

    @Test
    void shouldApproveReportWhenAllMetricThresholdsPass() {
        IntentEvaluationReport perfect = new IntentEvaluationReport(
                "intent-fixture-fast-v1",
                "0".repeat(64),
                "0".repeat(64),
                IntentRouter.DECISION_VERSION,
                IntentRouter.POLICY_VERSION,
                IntentEvaluationRunConfiguration.ruleOnly(),
                20,
                20,
                0,
                0,
                20,
                20,
                1D,
                1D,
                1D,
                1D,
                1D,
                0,
                0D,
                0,
                Map.<String, IntentClassMetrics>of(),
                Map.<String, Integer>of(),
                Map.<String, Integer>of(),
                Map.<String, Integer>of(),
                Map.<String, IntentEvaluationSplitMetrics>of()
        );

        IntentEvaluationGateResult gate = IntentEvaluationReleaseGate.evaluate(perfect);

        assertThat(gate.eligible()).isTrue();
        assertThat(gate.blockers()).isEmpty();
    }

    @Test
    void shouldBlockReleaseForSideEffectFalsePositive() {
        IntentEvaluationReport unsafe = new IntentEvaluationReport(
                "intent-fixture-fast-v1",
                "0".repeat(64),
                "0".repeat(64),
                IntentRouter.DECISION_VERSION,
                IntentRouter.POLICY_VERSION,
                IntentEvaluationRunConfiguration.ruleOnly(),
                20,
                20,
                0,
                0,
                19,
                19,
                0.95D,
                0.95D,
                0.95D,
                1D,
                1D,
                1,
                0.05D,
                1,
                Map.<String, IntentClassMetrics>of(),
                Map.of("GENERAL_EXPLANATION->SEND_EMAIL", 1),
                Map.<String, Integer>of(),
                Map.<String, Integer>of(),
                Map.<String, IntentEvaluationSplitMetrics>of()
        );

        IntentEvaluationGateResult gate = IntentEvaluationReleaseGate.evaluate(unsafe);

        assertThat(gate.eligible()).isFalse();
        assertThat(gate.blockers()).contains("side_effect_false_positive");
        assertThat(gate.blockers()).contains("high_risk_false_positive");
    }

    @Test
    void shouldBlockFrozenReleaseWhenTestSplitFailsEvenIfAggregatePasses() throws Exception {
        IntentEvaluationDataset dataset = IntentEvaluationDatasetLoader.load(
                "intent-eval/datasets/manifests/intent-candidate-v1.json"
        );
        IntentEvaluationSplitMetrics dev = perfectSplit(200);
        IntentEvaluationSplitMetrics test = new IntentEvaluationSplitMetrics(
                100,
                100,
                0,
                0,
                99,
                99,
                0.99D,
                0.99D,
                0.98D,
                1D,
                1D,
                1,
                0.02D,
                1,
                Map.<String, IntentClassMetrics>of(),
                Map.of("GENERAL_EXPLANATION->SEND_EMAIL", 1),
                Map.<String, Integer>of(),
                Map.<String, Integer>of()
        );
        IntentEvaluationReport aggregatePass = new IntentEvaluationReport(
                "intent-candidate-v1",
                "0".repeat(64),
                "0".repeat(64),
                IntentRouter.DECISION_VERSION,
                IntentRouter.POLICY_VERSION,
                IntentEvaluationRunConfiguration.ruleOnly(),
                300,
                300,
                0,
                0,
                300,
                300,
                1D,
                1D,
                1D,
                1D,
                1D,
                0,
                0D,
                0,
                Map.<String, IntentClassMetrics>of(),
                Map.<String, Integer>of(),
                Map.<String, Integer>of(),
                Map.<String, Integer>of(),
                Map.of("dev", dev, "test", test)
        );

        IntentEvaluationGateResult gate = IntentEvaluationReleaseGate.evaluate(aggregatePass, dataset);

        assertThat(gate.eligible()).isFalse();
        assertThat(gate.blockers()).contains("test_side_effect_false_positive");
        assertThat(gate.blockers()).contains("test_high_risk_false_positive");
    }

    @Test
    void shouldBlockReportWhenEvaluationContainsFailures() {
        IntentEvaluationReport failed = new IntentEvaluationReport(
                "intent-fixture-fast-v1",
                "0".repeat(64),
                "0".repeat(64),
                IntentRouter.DECISION_VERSION,
                IntentRouter.POLICY_VERSION,
                IntentEvaluationRunConfiguration.ruleOnly(),
                20,
                19,
                0,
                1,
                19,
                19,
                1D,
                1D,
                1D,
                1D,
                1D,
                0,
                0D,
                0,
                Map.<String, IntentClassMetrics>of(),
                Map.<String, Integer>of(),
                Map.<String, Integer>of(),
                Map.of("router_exception", 1),
                Map.<String, IntentEvaluationSplitMetrics>of()
        );

        IntentEvaluationGateResult gate = IntentEvaluationReleaseGate.evaluate(failed);

        assertThat(gate.eligible()).isFalse();
        assertThat(gate.blockers()).contains("coverage_incomplete", "evaluation_failures");
    }

    private IntentEvaluationSplitMetrics perfectSplit(int count) {
        return new IntentEvaluationSplitMetrics(
                count,
                count,
                0,
                0,
                count,
                count,
                1D,
                1D,
                1D,
                1D,
                1D,
                0,
                0D,
                0,
                Map.<String, IntentClassMetrics>of(),
                Map.<String, Integer>of(),
                Map.<String, Integer>of(),
                Map.<String, Integer>of()
        );
    }
}
