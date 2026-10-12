package com.kama.jchatmind.intent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.LongSupplier;

final class IntentEvaluationPerformanceEvaluator {

    static final double MAX_P95_INCREASE = 0.15D;
    private static final String BASELINE_MODE = "stateless-rule-route";
    private static final String CANDIDATE_MODE = "previous-user-query-context-rule-route";

    private IntentEvaluationPerformanceEvaluator() {
    }

    static IntentEvaluationLatencyReport evaluate(
            IntentEvaluationDataset dataset,
            IntentRouter router,
            int warmupPasses,
            int measuredPasses
    ) {
        return evaluate(dataset, router, warmupPasses, measuredPasses, System::nanoTime);
    }

    static IntentEvaluationLatencyReport evaluate(
            IntentEvaluationDataset dataset,
            IntentRouter router,
            int warmupPasses,
            int measuredPasses,
            LongSupplier nanoTime
    ) {
        if (dataset == null || router == null || nanoTime == null || dataset.cases().isEmpty()) {
            throw new IllegalArgumentException("dataset, router, nanoTime and cases must be present");
        }
        if (warmupPasses < 0 || measuredPasses <= 0) {
            throw new IllegalArgumentException("warmupPasses must be non-negative and measuredPasses positive");
        }

        for (int pass = 0; pass < warmupPasses; pass++) {
            for (IntentEvaluationCase item : dataset.cases()) {
                router.decide(item.query());
                router.decide(item.query(), item.previousUserQuery());
            }
        }

        int sampleCount = dataset.cases().size() * measuredPasses;
        List<Long> baselineSamples = new ArrayList<>(sampleCount);
        List<Long> contextualSamples = new ArrayList<>(sampleCount);
        for (int pass = 0; pass < measuredPasses; pass++) {
            for (IntentEvaluationCase item : dataset.cases()) {
                baselineSamples.add(measure(() -> router.decide(item.query()), nanoTime));
                contextualSamples.add(measure(
                        () -> router.decide(item.query(), item.previousUserQuery()),
                        nanoTime
                ));
            }
        }

        long baselineP95 = percentile95(baselineSamples);
        long contextualP95 = percentile95(contextualSamples);
        if (baselineP95 <= 0L) {
            throw new IllegalStateException("baseline p95 must be positive to calculate relative increase");
        }
        double relativeP95Increase = (double) (contextualP95 - baselineP95) / baselineP95;
        return new IntentEvaluationLatencyReport(
                dataset.manifest().datasetId(),
                dataset.effectiveCaseSha256(),
                IntentRouter.DECISION_VERSION,
                IntentRouter.POLICY_VERSION,
                BASELINE_MODE,
                CANDIDATE_MODE,
                "Java " + System.getProperty("java.version") + " / "
                        + System.getProperty("os.name") + " " + System.getProperty("os.arch"),
                dataset.cases().size(),
                warmupPasses,
                measuredPasses,
                sampleCount,
                baselineP95,
                contextualP95,
                relativeP95Increase,
                relativeP95Increase <= MAX_P95_INCREASE
        );
    }

    private static long measure(Runnable operation, LongSupplier nanoTime) {
        long startNanos = nanoTime.getAsLong();
        operation.run();
        return nanoTime.getAsLong() - startNanos;
    }

    private static long percentile95(List<Long> samples) {
        if (samples.isEmpty()) {
            throw new IllegalArgumentException("latency samples must not be empty");
        }
        List<Long> ordered = new ArrayList<>(samples);
        Collections.sort(ordered);
        int index = (int) Math.ceil(ordered.size() * 0.95D) - 1;
        return ordered.get(Math.max(0, index));
    }
}
