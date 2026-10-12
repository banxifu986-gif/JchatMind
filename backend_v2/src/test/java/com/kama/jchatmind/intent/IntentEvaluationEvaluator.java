package com.kama.jchatmind.intent;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

final class IntentEvaluationEvaluator {

    private static final Set<String> SIDE_EFFECT_CAPABILITIES = Set.of(
            "sendEmail", "writeFile", "appendToFile", "deleteFile", "createDirectory"
    );

    private IntentEvaluationEvaluator() {
    }

    static IntentEvaluationReport evaluate(IntentEvaluationDataset dataset, IntentRouter router) {
        if (router == null) {
            throw new IllegalArgumentException("router must not be null");
        }
        return evaluate(
                dataset,
                IntentEvaluationRunConfiguration.ruleOnly(),
                item -> router.decide(item.query(), item.previousUserQuery())
        );
    }

    static IntentEvaluationReport evaluate(IntentEvaluationDataset dataset, IntentFallbackRouter router) {
        if (router == null) {
            throw new IllegalArgumentException("router must not be null");
        }
        return evaluate(
                dataset,
                IntentEvaluationRunConfiguration.from(router),
                item -> router.decide(item.query(), item.previousUserQuery())
        );
    }

    private static IntentEvaluationReport evaluate(
            IntentEvaluationDataset dataset,
            IntentEvaluationRunConfiguration runConfiguration,
            Function<IntentEvaluationCase, IntentDecision> decisionProvider
    ) {
        if (dataset == null || runConfiguration == null || decisionProvider == null) {
            throw new IllegalArgumentException("dataset, run configuration and decision provider must not be null");
        }
        List<EvaluationOutcome> outcomes = evaluateCases(dataset.cases(), decisionProvider);
        IntentEvaluationSplitMetrics overall = evaluateOutcomes(outcomes);
        Map<String, List<EvaluationOutcome>> outcomesBySplit = new TreeMap<>();
        for (EvaluationOutcome outcome : outcomes) {
            outcomesBySplit.computeIfAbsent(outcome.item().split(), ignored -> new java.util.ArrayList<>())
                    .add(outcome);
        }
        Map<String, IntentEvaluationSplitMetrics> splitMetrics = new TreeMap<>();
        outcomesBySplit.forEach((split, splitOutcomes) -> splitMetrics.put(split, evaluateOutcomes(splitOutcomes)));

        return new IntentEvaluationReport(
                dataset.manifest().datasetId(),
                dataset.manifest().caseSha256(),
                dataset.effectiveCaseSha256(),
                IntentRouter.DECISION_VERSION,
                IntentRouter.POLICY_VERSION,
                runConfiguration,
                overall.totalCases(),
                overall.evaluatedCases(),
                overall.skippedCases(),
                overall.failedCases(),
                overall.correctCases(),
                overall.decisionContractMatchCases(),
                overall.decisionContractMatchRate(),
                overall.accuracy(),
                overall.macroF1(),
                overall.abstainAccuracy(),
                overall.clarificationAccuracy(),
                overall.sideEffectFalsePositiveCases(),
                overall.sideEffectFalsePositiveRate(),
                overall.highRiskFalsePositiveCases(),
                overall.perIntentMetrics(),
                overall.confusionMatrix(),
                overall.skipReasons(),
                overall.failureReasons(),
                Map.copyOf(splitMetrics)
        );
    }

    private static List<EvaluationOutcome> evaluateCases(
            List<IntentEvaluationCase> cases,
            Function<IntentEvaluationCase, IntentDecision> decisionProvider
    ) {
        List<EvaluationOutcome> outcomes = new java.util.ArrayList<>(cases.size());
        for (IntentEvaluationCase item : cases) {
            if (item.skipReason() != null) {
                outcomes.add(new EvaluationOutcome(item, null, item.skipReason(), null));
                continue;
            }
            try {
                IntentDecision decision = decisionProvider.apply(item);
                outcomes.add(decision == null
                        ? new EvaluationOutcome(item, null, null, "null_decision")
                        : new EvaluationOutcome(item, decision, null, null));
            } catch (RuntimeException e) {
                outcomes.add(new EvaluationOutcome(item, null, null, "router_exception"));
            }
        }
        return List.copyOf(outcomes);
    }

    private static IntentEvaluationSplitMetrics evaluateOutcomes(List<EvaluationOutcome> outcomes) {
        int evaluated = 0;
        int skipped = 0;
        int failed = 0;
        int correct = 0;
        int decisionContractMatches = 0;
        Map<String, Integer> confusion = new LinkedHashMap<>();
        Map<String, Integer> skipReasons = new LinkedHashMap<>();
        Map<String, Integer> failureReasons = new LinkedHashMap<>();
        Map<BusinessIntent, Integer> expectedCounts = new HashMap<>();
        Map<BusinessIntent, Integer> predictedCounts = new HashMap<>();
        Map<BusinessIntent, Integer> truePositiveCounts = new HashMap<>();
        int abstainCorrectCases = 0;
        int clarificationCorrectCases = 0;
        int sideEffectFalsePositiveCases = 0;
        int highRiskFalsePositiveCases = 0;
        int nonSideEffectExpectedCases = 0;

        for (EvaluationOutcome outcome : outcomes) {
            IntentEvaluationCase item = outcome.item();
            if (outcome.skipReason() != null) {
                skipped++;
                skipReasons.merge(outcome.skipReason(), 1, Integer::sum);
                continue;
            }
            if (outcome.failureReason() != null) {
                failed++;
                failureReasons.merge(outcome.failureReason(), 1, Integer::sum);
                continue;
            }

            IntentDecision decision = outcome.decision();
            evaluated++;
            expectedCounts.merge(item.expectedBusinessIntent(), 1, Integer::sum);
            predictedCounts.merge(decision.businessIntent(), 1, Integer::sum);
            boolean actualAbstain = decision.businessIntent() == BusinessIntent.ABSTAIN;
            boolean actualClarify = decision.requiresClarification();
            if (item.shouldAbstain() == actualAbstain) {
                abstainCorrectCases++;
            }
            if (item.shouldClarify() == actualClarify) {
                clarificationCorrectCases++;
            }
            boolean expectedSideEffect = item.expectedRiskLevel() == IntentRiskLevel.SIDE_EFFECT;
            boolean actualSideEffect = decision.riskLevel() == IntentRiskLevel.SIDE_EFFECT
                    || decision.candidateCapabilities().stream().anyMatch(SIDE_EFFECT_CAPABILITIES::contains);
            if (!expectedSideEffect) {
                nonSideEffectExpectedCases++;
                if (actualSideEffect) {
                    sideEffectFalsePositiveCases++;
                }
            }
            if ((item.shouldAbstain() || item.shouldClarify()) && actualSideEffect) {
                highRiskFalsePositiveCases++;
            }
            if (item.expectedBusinessIntent() == decision.businessIntent()) {
                correct++;
                truePositiveCounts.merge(item.expectedBusinessIntent(), 1, Integer::sum);
            }
            String confusionKey = item.expectedBusinessIntent().name() + "->" + decision.businessIntent().name();
            confusion.merge(confusionKey, 1, Integer::sum);
            if (matchesDecisionContract(item, decision)) {
                decisionContractMatches++;
            }
        }

        Set<BusinessIntent> labels = new LinkedHashSet<>();
        labels.addAll(expectedCounts.keySet());
        labels.addAll(predictedCounts.keySet());
        double macroF1 = labels.stream()
                .mapToDouble(label -> f1(
                        truePositiveCounts.getOrDefault(label, 0),
                        predictedCounts.getOrDefault(label, 0),
                        expectedCounts.getOrDefault(label, 0)
                ))
                .average()
                .orElse(0D);

        Map<String, IntentClassMetrics> perIntentMetrics = new TreeMap<>();
        for (BusinessIntent label : labels) {
            int support = expectedCounts.getOrDefault(label, 0);
            int predicted = predictedCounts.getOrDefault(label, 0);
            int truePositive = truePositiveCounts.getOrDefault(label, 0);
            double precision = predicted == 0 ? 0D : (double) truePositive / predicted;
            double recall = support == 0 ? 0D : (double) truePositive / support;
            perIntentMetrics.put(label.name(), new IntentClassMetrics(
                    support,
                    predicted,
                    truePositive,
                    precision,
                    recall,
                    f1(truePositive, predicted, support)
            ));
        }

        return new IntentEvaluationSplitMetrics(
                outcomes.size(),
                evaluated,
                skipped,
                failed,
                correct,
                decisionContractMatches,
                evaluated == 0 ? 0D : (double) decisionContractMatches / evaluated,
                evaluated == 0 ? 0D : (double) correct / evaluated,
                macroF1,
                evaluated == 0 ? 0D : (double) abstainCorrectCases / evaluated,
                evaluated == 0 ? 0D : (double) clarificationCorrectCases / evaluated,
                sideEffectFalsePositiveCases,
                nonSideEffectExpectedCases == 0
                        ? 0D
                        : (double) sideEffectFalsePositiveCases / nonSideEffectExpectedCases,
                highRiskFalsePositiveCases,
                Map.copyOf(perIntentMetrics),
                Map.copyOf(confusion),
                Map.copyOf(skipReasons),
                Map.copyOf(failureReasons)
        );
    }

    private record EvaluationOutcome(
            IntentEvaluationCase item,
            IntentDecision decision,
            String skipReason,
            String failureReason
    ) {
    }

    private static boolean matchesDecisionContract(IntentEvaluationCase expected, IntentDecision actual) {
        return expected.expectedBusinessIntent() == actual.businessIntent()
                && expected.expectedRoute() == actual.route()
                && expected.expectedRiskLevel() == actual.riskLevel()
                && expected.expectedCapabilities().equals(actual.candidateCapabilities())
                && expected.shouldClarify() == actual.requiresClarification()
                && expected.shouldAbstain() == (actual.businessIntent() == BusinessIntent.ABSTAIN);
    }

    private static double f1(int truePositive, int predicted, int expected) {
        if (truePositive == 0 || predicted == 0 || expected == 0) {
            return truePositive == 0 && predicted == 0 && expected == 0 ? 1D : 0D;
        }
        double precision = (double) truePositive / predicted;
        double recall = (double) truePositive / expected;
        return 2D * precision * recall / (precision + recall);
    }
}
