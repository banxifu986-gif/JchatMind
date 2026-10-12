package com.kama.jchatmind.intent;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

final class IntentEvaluationReportWriter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private IntentEvaluationReportWriter() {
    }

    static void write(
            Path output,
            IntentEvaluationDataset dataset,
            IntentEvaluationReport report
    ) throws IOException {
        if (output == null || dataset == null || report == null) {
            throw new IllegalArgumentException("report output, dataset and report are required");
        }
        if (!dataset.manifest().datasetId().equals(report.datasetId())) {
            throw new IllegalArgumentException("report datasetId does not match manifest");
        }
        if (!dataset.manifest().caseSha256().equals(report.datasetSourceSha256())) {
            throw new IllegalArgumentException("report datasetSourceSha256 does not match manifest");
        }
        if (!dataset.effectiveCaseSha256().equals(report.datasetCaseSha256())) {
            throw new IllegalArgumentException("report datasetCaseSha256 does not match evaluated cases");
        }
        if (report.decisionVersion() == null || report.decisionVersion().isBlank()
                || report.policyVersion() == null || report.policyVersion().isBlank()
                || report.runConfiguration() == null) {
            throw new IllegalArgumentException("report decision, policy and run configuration metadata are required");
        }

        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        IntentEvaluationGateResult metricGate = IntentEvaluationReleaseGate.evaluate(report, dataset);
        java.util.List<String> blockers = new ArrayList<>(metricGate.blockers());
        if (!"rule-only".equals(report.runConfiguration().mode())) {
            if ("unconfigured".equals(report.runConfiguration().modelName())) {
                blockers.add("model_name_unconfigured");
            }
            if ("unversioned".equals(report.runConfiguration().modelVersion())) {
                blockers.add("model_version_unversioned");
            }
            if ("none".equals(report.runConfiguration().promptVersion())) {
                blockers.add("prompt_version_missing");
            }
        }
        ReportDocument document = new ReportDocument(
                "3",
                dataset.manifest().datasetId(),
                dataset.manifest().status(),
                dataset.manifest().fixtureOnly(),
                dataset.manifest().caseSha256(),
                dataset.effectiveCaseSha256(),
                dataset.manifest().generatorVersion(),
                splitCounts(report.splitMetrics()),
                report.decisionVersion(),
                report.policyVersion(),
                report.runConfiguration(),
                blockers.isEmpty(),
                List.copyOf(blockers),
                new Metrics(
                        report.totalCases(),
                        report.evaluatedCases(),
                        report.skippedCases(),
                        report.failedCases(),
                        report.correctCases(),
                        report.decisionContractMatchCases(),
                        report.decisionContractMatchRate(),
                        report.accuracy(),
                        report.macroF1(),
                        report.abstainAccuracy(),
                        report.clarificationAccuracy(),
                        report.sideEffectFalsePositiveCases(),
                        report.sideEffectFalsePositiveRate(),
                        report.highRiskFalsePositiveCases(),
                        new TreeMap<>(report.perIntentMetrics()),
                        new TreeMap<>(report.confusionMatrix()),
                        new TreeMap<>(report.skipReasons()),
                        new TreeMap<>(report.failureReasons()),
                        orderedSplitMetrics(report.splitMetrics())
                )
        );
        String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(document) + "\n";
        Files.writeString(output, json, StandardCharsets.UTF_8);
    }

    private record ReportDocument(
            String reportSchemaVersion,
            String datasetId,
            String datasetStatus,
            boolean fixtureOnly,
            String datasetSourceSha256,
            String datasetCaseSha256,
            String generatorVersion,
            Map<String, Integer> splitCounts,
            String decisionVersion,
            String policyVersion,
            IntentEvaluationRunConfiguration runConfiguration,
            boolean eligibleForRelease,
            List<String> releaseGateBlockers,
            Metrics metrics
    ) {
    }

    private record Metrics(
            int totalCases,
            int evaluatedCases,
            int skippedCases,
            int failedCases,
            int correctCases,
            int decisionContractMatchCases,
            double decisionContractMatchRate,
            double accuracy,
            double macroF1,
            double abstainAccuracy,
            double clarificationAccuracy,
            int sideEffectFalsePositiveCases,
            double sideEffectFalsePositiveRate,
            int highRiskFalsePositiveCases,
            Map<String, IntentClassMetrics> perIntentMetrics,
            Map<String, Integer> confusionMatrix,
            Map<String, Integer> skipReasons,
            Map<String, Integer> failureReasons,
            Map<String, IntentEvaluationSplitMetrics> splitMetrics
    ) {
    }

    private static Map<String, IntentEvaluationSplitMetrics> orderedSplitMetrics(
            Map<String, IntentEvaluationSplitMetrics> source
    ) {
        Map<String, IntentEvaluationSplitMetrics> ordered = new TreeMap<>();
        source.forEach((split, metrics) -> ordered.put(split, new IntentEvaluationSplitMetrics(
                metrics.totalCases(),
                metrics.evaluatedCases(),
                metrics.skippedCases(),
                metrics.failedCases(),
                metrics.correctCases(),
                metrics.decisionContractMatchCases(),
                metrics.decisionContractMatchRate(),
                metrics.accuracy(),
                metrics.macroF1(),
                metrics.abstainAccuracy(),
                metrics.clarificationAccuracy(),
                metrics.sideEffectFalsePositiveCases(),
                metrics.sideEffectFalsePositiveRate(),
                metrics.highRiskFalsePositiveCases(),
                new TreeMap<>(metrics.perIntentMetrics()),
                new TreeMap<>(metrics.confusionMatrix()),
                new TreeMap<>(metrics.skipReasons()),
                new TreeMap<>(metrics.failureReasons())
        )));
        return ordered;
    }

    private static Map<String, Integer> splitCounts(Map<String, IntentEvaluationSplitMetrics> source) {
        Map<String, Integer> counts = new TreeMap<>();
        source.forEach((split, metrics) -> counts.put(split, metrics.totalCases()));
        return counts;
    }
}
