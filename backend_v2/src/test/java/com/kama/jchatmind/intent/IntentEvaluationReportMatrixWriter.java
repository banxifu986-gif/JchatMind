package com.kama.jchatmind.intent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

final class IntentEvaluationReportMatrixWriter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private IntentEvaluationReportMatrixWriter() {
    }

    static void write(Path output, String baselineName, Map<String, Path> reports) throws IOException {
        if (output == null || baselineName == null || baselineName.isBlank()
                || reports == null || reports.size() < 2) {
            throw new IllegalArgumentException("output, baseline and at least two reports are required");
        }
        if (reports.entrySet().stream().anyMatch(entry -> entry.getKey() == null || entry.getKey().isBlank()
                || entry.getValue() == null)) {
            throw new IllegalArgumentException("report names and paths must be valid");
        }
        Map<String, Path> orderedReports = new TreeMap<>(reports);
        if (orderedReports.containsValue(null)
                || !orderedReports.containsKey(baselineName)) {
            throw new IllegalArgumentException("report names and paths must be valid and include the baseline");
        }

        Path baselinePath = orderedReports.get(baselineName);
        JsonNode baseline = OBJECT_MAPPER.readTree(baselinePath.toFile());
        IntentEvaluationReportComparison baselineComparison = IntentEvaluationReportComparator.compare(
                baselinePath,
                baselinePath
        );
        Map<String, RunComparison> runs = new TreeMap<>();
        for (Map.Entry<String, Path> entry : orderedReports.entrySet()) {
            IntentEvaluationReportComparison comparison = IntentEvaluationReportComparator.compare(
                    baselinePath,
                    entry.getValue()
            );
            JsonNode candidate = OBJECT_MAPPER.readTree(entry.getValue().toFile());
            runs.put(entry.getKey(), new RunComparison(
                    entry.getKey(),
                    text(candidate, "policyVersion"),
                    comparison.candidateRunConfiguration(),
                    comparison.accuracyDelta(),
                    comparison.macroF1Delta(),
                    comparison.decisionContractMatchRateDelta(),
                    comparison.testAccuracyDelta(),
                    comparison.testMacroF1Delta(),
                    new TreeMap<>(comparison.perIntentF1Delta())
            ));
        }

        ReportMatrix matrix = new ReportMatrix(
                "1",
                baselineName,
                text(baseline, "datasetId"),
                text(baseline, "datasetSourceSha256"),
                text(baseline, "datasetCaseSha256"),
                text(baseline, "decisionVersion"),
                baselineComparison.baselinePolicyVersion(),
                baselineComparison.baselineRunConfiguration(),
                integerMap(baseline.path("splitCounts")),
            runs
        );
        Path parent = output.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        String json = OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(matrix) + "\n";
        Files.writeString(output, json, StandardCharsets.UTF_8);
    }

    private static Map<String, Integer> integerMap(JsonNode node) {
        Map<String, Integer> values = new TreeMap<>();
        if (node == null || !node.isObject()) {
            return values;
        }
        node.fields().forEachRemaining(entry -> {
            if (!entry.getValue().canConvertToInt()) {
                throw new IllegalArgumentException("splitCounts must contain integers");
            }
            values.put(entry.getKey(), entry.getValue().asInt());
        });
        return values;
    }

    private static String text(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("report field must be nonblank text: " + field);
        }
        return value.asText();
    }

    private record ReportMatrix(
            String reportSchemaVersion,
            String baselineName,
            String datasetId,
            String datasetSourceSha256,
            String datasetCaseSha256,
            String decisionVersion,
            String baselinePolicyVersion,
            IntentEvaluationRunConfiguration baselineRunConfiguration,
            Map<String, Integer> splitCounts,
            Map<String, RunComparison> runs
    ) {
    }

    private record RunComparison(
            String name,
            String policyVersion,
            IntentEvaluationRunConfiguration runConfiguration,
            double accuracyDelta,
            double macroF1Delta,
            double decisionContractMatchRateDelta,
            double testAccuracyDelta,
            double testMacroF1Delta,
            Map<String, Double> perIntentF1Delta
    ) {
    }
}
