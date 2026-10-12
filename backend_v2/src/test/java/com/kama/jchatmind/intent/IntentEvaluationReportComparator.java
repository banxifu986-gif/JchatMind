package com.kama.jchatmind.intent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

final class IntentEvaluationReportComparator {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private IntentEvaluationReportComparator() {
    }

    static IntentEvaluationReportComparison compare(Path baselinePath, Path candidatePath) throws IOException {
        if (baselinePath == null || candidatePath == null) {
            throw new IllegalArgumentException("baseline and candidate report paths are required");
        }
        JsonNode baseline = OBJECT_MAPPER.readTree(baselinePath.toFile());
        JsonNode candidate = OBJECT_MAPPER.readTree(candidatePath.toFile());
        requireSame("reportSchemaVersion", text(baseline, "reportSchemaVersion"),
                text(candidate, "reportSchemaVersion"));
        String datasetId = text(baseline, "datasetId");
        requireSame("datasetId", datasetId, text(candidate, "datasetId"));
        String datasetCaseSha256 = text(baseline, "datasetCaseSha256");
        requireSame("datasetCaseSha256", datasetCaseSha256, text(candidate, "datasetCaseSha256"));
        requireSame("datasetSourceSha256", text(baseline, "datasetSourceSha256"),
                text(candidate, "datasetSourceSha256"));
        requireSame("generatorVersion", optionalText(baseline, "generatorVersion"),
                optionalText(candidate, "generatorVersion"));
        if (!object(baseline, "splitCounts").equals(object(candidate, "splitCounts"))) {
            throw new IllegalArgumentException("reports must share the same split counts");
        }
        String decisionVersion = text(baseline, "decisionVersion");
        requireSame("decisionVersion", decisionVersion, text(candidate, "decisionVersion"));

        JsonNode baselineMetrics = object(baseline, "metrics");
        JsonNode candidateMetrics = object(candidate, "metrics");
        JsonNode baselineTest = object(object(baselineMetrics, "splitMetrics"), "test");
        JsonNode candidateTest = object(object(candidateMetrics, "splitMetrics"), "test");
        IntentEvaluationRunConfiguration baselineConfiguration = readConfiguration(
                object(baseline, "runConfiguration")
        );
        IntentEvaluationRunConfiguration candidateConfiguration = readConfiguration(
                object(candidate, "runConfiguration")
        );

        return new IntentEvaluationReportComparison(
                datasetId,
                datasetCaseSha256,
                decisionVersion,
                text(baseline, "policyVersion"),
                text(candidate, "policyVersion"),
                baselineConfiguration,
                candidateConfiguration,
                number(candidateMetrics, "accuracy") - number(baselineMetrics, "accuracy"),
                number(candidateMetrics, "macroF1") - number(baselineMetrics, "macroF1"),
                number(candidateMetrics, "decisionContractMatchRate")
                        - number(baselineMetrics, "decisionContractMatchRate"),
                number(candidateTest, "accuracy") - number(baselineTest, "accuracy"),
                number(candidateTest, "macroF1") - number(baselineTest, "macroF1"),
                perIntentF1Delta(
                        object(baselineMetrics, "perIntentMetrics"),
                        object(candidateMetrics, "perIntentMetrics")
                )
        );
    }

    private static Map<String, Double> perIntentF1Delta(JsonNode baseline, JsonNode candidate) {
        Set<String> labels = new TreeSet<>();
        baseline.fieldNames().forEachRemaining(labels::add);
        candidate.fieldNames().forEachRemaining(labels::add);
        Map<String, Double> delta = new TreeMap<>();
        for (String label : labels) {
            JsonNode baselineClass = baseline.path(label);
            JsonNode candidateClass = candidate.path(label);
            delta.put(label,
                    optionalNumber(candidateClass.path("f1")) - optionalNumber(baselineClass.path("f1")));
        }
        return Collections.unmodifiableMap(delta);
    }

    private static IntentEvaluationRunConfiguration readConfiguration(JsonNode node) {
        return new IntentEvaluationRunConfiguration(
                text(node, "mode"),
                text(node, "modelName"),
                text(node, "modelVersion"),
                text(node, "promptVersion"),
                text(node, "routingConfigSha256")
        );
    }

    private static JsonNode object(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isObject()) {
            throw new IllegalArgumentException("report field must be an object: " + field);
        }
        return value;
    }

    private static String text(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("report field must be nonblank text: " + field);
        }
        return value.asText();
    }

    private static String optionalText(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : "none";
    }

    private static double number(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isNumber()) {
            throw new IllegalArgumentException("report field must be numeric: " + field);
        }
        return value.asDouble();
    }

    private static double optionalNumber(JsonNode value) {
        return value.isNumber() ? value.asDouble() : 0D;
    }

    private static void requireSame(String field, String baseline, String candidate) {
        if (!baseline.equals(candidate)) {
            throw new IllegalArgumentException("reports must share the same " + field);
        }
    }
}
