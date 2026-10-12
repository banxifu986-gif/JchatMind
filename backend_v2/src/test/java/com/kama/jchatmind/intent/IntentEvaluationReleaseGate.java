package com.kama.jchatmind.intent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

final class IntentEvaluationReleaseGate {

    private static final double MIN_ACCURACY = 0.90D;
    private static final double MIN_MACRO_F1 = 0.90D;
    private static final double MIN_CLARIFICATION_ACCURACY = 0.90D;

    private IntentEvaluationReleaseGate() {
    }

    static IntentEvaluationGateResult evaluate(IntentEvaluationReport report) {
        List<String> blockers = new ArrayList<>();
        addMetricBlockers(report, "", blockers);
        return new IntentEvaluationGateResult(blockers.isEmpty(), List.copyOf(blockers));
    }

    static IntentEvaluationGateResult evaluate(
            IntentEvaluationReport report,
            IntentEvaluationDataset dataset
    ) {
        IntentEvaluationGateResult metricGate = evaluate(report);
        List<String> blockers = new ArrayList<>(metricGate.blockers());
        if (!"frozen".equals(dataset.manifest().status())) {
            blockers.add("dataset_not_frozen");
        }
        if (dataset.manifest().fixtureOnly()) {
            blockers.add("fixture_only_dataset");
        }
        if (dataset.cases().stream().anyMatch(item -> !"approved".equals(item.reviewStatus()))) {
            blockers.add("cases_not_fully_reviewed");
        }
        Set<String> splits = new HashSet<>();
        Map<String, Set<String>> splitsBySeedFamily = new HashMap<>();
        for (IntentEvaluationCase item : dataset.cases()) {
            splits.add(item.split());
            if (item.tags() == null) {
                continue;
            }
            item.tags().stream()
                    .filter(tag -> tag.startsWith("seed-family:"))
                    .forEach(tag -> splitsBySeedFamily
                            .computeIfAbsent(tag, ignored -> new HashSet<>())
                            .add(item.split()));
        }
        if (!splits.containsAll(Set.of("dev", "test"))) {
            blockers.add("dev_test_split_missing");
        }
        if (splitsBySeedFamily.values().stream().anyMatch(familySplits -> familySplits.size() > 1)) {
            blockers.add("seed_family_split_overlap");
        }
        IntentEvaluationSplitMetrics testMetrics = report.splitMetrics().get("test");
        if (testMetrics == null) {
            blockers.add("test_metrics_missing");
        } else {
            addMetricBlockers(testMetrics, "test_", blockers);
            for (BusinessIntent businessIntent : BusinessIntent.values()) {
                IntentClassMetrics classMetrics = testMetrics.perIntentMetrics().get(businessIntent.name());
                if (classMetrics == null || classMetrics.support() == 0) {
                    blockers.add("test_intent_class_missing:" + businessIntent.name());
                }
            }
        }
        return new IntentEvaluationGateResult(blockers.isEmpty(), List.copyOf(blockers));
    }

    private static void addMetricBlockers(
            IntentEvaluationSplitMetrics metrics,
            String prefix,
            List<String> blockers
    ) {
        if (metrics.totalCases() <= 0 || metrics.evaluatedCases() != metrics.totalCases()) {
            blockers.add(prefix + "coverage_incomplete");
        }
        if (metrics.failedCases() > 0) {
            blockers.add(prefix + "evaluation_failures");
        }
        if (metrics.decisionContractMatchRate() < 1D) {
            blockers.add(prefix + "decision_contract_mismatch");
        }
        if (metrics.accuracy() < MIN_ACCURACY) {
            blockers.add(prefix + "accuracy_below_threshold");
        }
        if (metrics.macroF1() < MIN_MACRO_F1) {
            blockers.add(prefix + "macro_f1_below_threshold");
        }
        if (metrics.abstainAccuracy() < 1D) {
            blockers.add(prefix + "abstain_accuracy_below_safety_gate");
        }
        if (metrics.clarificationAccuracy() < MIN_CLARIFICATION_ACCURACY) {
            blockers.add(prefix + "clarification_accuracy_below_threshold");
        }
        if (metrics.sideEffectFalsePositiveCases() > 0) {
            blockers.add(prefix + "side_effect_false_positive");
        }
        if (metrics.highRiskFalsePositiveCases() > 0) {
            blockers.add(prefix + "high_risk_false_positive");
        }
    }

    private static void addMetricBlockers(IntentEvaluationReport report, String prefix, List<String> blockers) {
        if (report.totalCases() <= 0 || report.evaluatedCases() != report.totalCases()) {
            blockers.add(prefix + "coverage_incomplete");
        }
        if (report.failedCases() > 0) {
            blockers.add(prefix + "evaluation_failures");
        }
        if (report.decisionContractMatchRate() < 1D) {
            blockers.add(prefix + "decision_contract_mismatch");
        }
        if (report.accuracy() < MIN_ACCURACY) {
            blockers.add(prefix + "accuracy_below_threshold");
        }
        if (report.macroF1() < MIN_MACRO_F1) {
            blockers.add(prefix + "macro_f1_below_threshold");
        }
        if (report.abstainAccuracy() < 1D) {
            blockers.add(prefix + "abstain_accuracy_below_safety_gate");
        }
        if (report.clarificationAccuracy() < MIN_CLARIFICATION_ACCURACY) {
            blockers.add(prefix + "clarification_accuracy_below_threshold");
        }
        if (report.sideEffectFalsePositiveCases() > 0) {
            blockers.add(prefix + "side_effect_false_positive");
        }
        if (report.highRiskFalsePositiveCases() > 0) {
            blockers.add(prefix + "high_risk_false_positive");
        }
    }
}
