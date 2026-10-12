package com.kama.jchatmind.intent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class IntentEvaluationReportMatrixWriterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldWriteDeterministicMatrixForMultipleVersionsOnSameDataset() throws Exception {
        IntentEvaluationDataset dataset = IntentEvaluationDatasetLoader.load(
                "intent-eval/datasets/manifests/fixture-fast-v1.json"
        );
        Path directory = Path.of("target/intent-eval-report-test/matrix");
        Path baseline = writeReport(directory.resolve("rule-baseline.json"), dataset, null, null);
        Path modelV1 = writeReport(directory.resolve("model-v1.json"), dataset, "matrix-stub", "stub-v1");
        Path modelV2 = writeReport(directory.resolve("model-v2.json"), dataset, "matrix-stub", "stub-v2");
        Map<String, Path> reports = Map.of(
                "rule-baseline", baseline,
                "model-v1", modelV1,
                "model-v2", modelV2
        );
        Path first = directory.resolve("matrix-first.json");
        Path second = directory.resolve("matrix-second.json");

        IntentEvaluationReportMatrixWriter.write(first, "rule-baseline", reports);
        IntentEvaluationReportMatrixWriter.write(second, "rule-baseline", reports);

        JsonNode root = objectMapper.readTree(Files.readString(first));
        assertThat(Files.readAllBytes(first)).isEqualTo(Files.readAllBytes(second));
        assertThat(root.path("reportSchemaVersion").asText()).isEqualTo("1");
        assertThat(root.path("datasetId").asText()).isEqualTo(dataset.manifest().datasetId());
        assertThat(root.path("datasetCaseSha256").asText()).isEqualTo(dataset.effectiveCaseSha256());
        assertThat(root.path("baselineName").asText()).isEqualTo("rule-baseline");
        assertThat(root.path("runs").size()).isEqualTo(3);
        assertThat(root.path("runs").path("model-v1").path("runConfiguration").path("modelVersion").asText())
                .isEqualTo("stub-v1");
        assertThat(root.path("runs").path("model-v2").path("runConfiguration").path("modelVersion").asText())
                .isEqualTo("stub-v2");
        assertThat(root.path("runs").path("rule-baseline").path("accuracyDelta").asDouble()).isZero();
    }

    @Test
    void shouldRejectMatrixWhenAnyReportUsesDifferentDatasetCases() throws Exception {
        IntentEvaluationDataset dataset = IntentEvaluationDatasetLoader.load(
                "intent-eval/datasets/manifests/fixture-fast-v1.json"
        );
        Path directory = Path.of("target/intent-eval-report-test/matrix-mismatch");
        Path baseline = writeReport(directory.resolve("baseline.json"), dataset, null, null);
        ObjectNode incompatible = (ObjectNode) objectMapper.readTree(baseline.toFile());
        incompatible.put("datasetCaseSha256", "f".repeat(64));
        Path candidate = directory.resolve("candidate.json");
        Files.writeString(candidate, objectMapper.writeValueAsString(incompatible));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> IntentEvaluationReportMatrixWriter.write(
                directory.resolve("matrix.json"),
                "baseline",
                Map.of("baseline", baseline, "candidate", candidate)
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("datasetCaseSha256");
    }

    private Path writeReport(Path output, IntentEvaluationDataset dataset, String model, String modelVersion)
            throws Exception {
        IntentEvaluationReport report;
        if (model == null) {
            report = IntentEvaluationEvaluator.evaluate(dataset, new IntentRouter());
        } else {
            IntentRoutingProperties properties = new IntentRoutingProperties();
            properties.setLlmEnabled(true);
            properties.setModel(model);
            properties.setModelVersion(modelVersion);
            IntentFallbackRouter router = new IntentFallbackRouter(
                    new IntentRouter(),
                    objectMapper,
                    query -> "{\"businessIntent\":\"DIRECT_CHAT\",\"confidence\":0.95,\"margin\":0.8}",
                    properties,
                    false,
                    IntentDecisionTelemetry.noop()
            );
            report = IntentEvaluationEvaluator.evaluate(dataset, router);
        }
        IntentEvaluationReportWriter.write(output, dataset, report);
        return output;
    }
}
