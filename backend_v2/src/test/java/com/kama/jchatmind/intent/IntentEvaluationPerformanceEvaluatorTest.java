package com.kama.jchatmind.intent;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.assertj.core.api.Assertions.assertThat;

class IntentEvaluationPerformanceEvaluatorTest {

    @Test
    void shouldComputeP95AndRelativeIncreaseFromPairedMeasurements() throws Exception {
        IntentEvaluationDataset dataset = IntentEvaluationDatasetLoader.load(
                "intent-eval/datasets/manifests/intent-candidate-v1.json"
        );

        IntentEvaluationLatencyReport report = IntentEvaluationPerformanceEvaluator.evaluate(
                dataset,
                new IntentRouter(),
                0,
                1,
                deterministicClock(110L)
        );

        assertThat(report.caseCount()).isEqualTo(300);
        assertThat(report.sampleCountPerMode()).isEqualTo(300);
        assertThat(report.baselineMode()).isEqualTo("stateless-rule-route");
        assertThat(report.candidateMode()).isEqualTo("previous-user-query-context-rule-route");
        assertThat(report.baselineP95Nanos()).isEqualTo(100L);
        assertThat(report.contextualP95Nanos()).isEqualTo(110L);
        assertThat(report.relativeP95Increase()).isEqualTo(0.10D);
        assertThat(report.withinBudget()).isTrue();
    }

    @Test
    void shouldFailPerformanceGateWhenP95IncreaseExceedsFifteenPercent() throws Exception {
        IntentEvaluationDataset dataset = IntentEvaluationDatasetLoader.load(
                "intent-eval/datasets/manifests/intent-candidate-v1.json"
        );

        IntentEvaluationLatencyReport report = IntentEvaluationPerformanceEvaluator.evaluate(
                dataset,
                new IntentRouter(),
                0,
                1,
                deterministicClock(120L)
        );

        assertThat(report.relativeP95Increase()).isEqualTo(0.20D);
        assertThat(report.withinBudget()).isFalse();
    }

    private LongSupplier deterministicClock(long contextualDurationNanos) {
        AtomicLong calls = new AtomicLong();
        return () -> {
            long call = calls.getAndIncrement();
            long pairStart = (call / 4L) * 1000L;
            return pairStart + switch ((int) (call % 4L)) {
                case 0 -> 0L;
                case 1 -> 100L;
                case 2 -> 200L;
                default -> 200L + contextualDurationNanos;
            };
        };
    }
}
