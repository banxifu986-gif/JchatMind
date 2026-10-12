package com.kama.jchatmind.intent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class IntentEvaluationReportWriterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldWriteVersionedReportWithDatasetAndPolicyFingerprint() throws Exception {
        Path tempDir = testOutputDirectory();
        IntentEvaluationDataset dataset = IntentEvaluationDatasetLoader.load(
                "intent-eval/datasets/manifests/fixture-fast-v1.json"
        );
        IntentEvaluationReport report = IntentEvaluationEvaluator.evaluate(dataset, new IntentRouter());
        Path output = tempDir.resolve("reports/intent-fixture-fast-v1.json");

        IntentEvaluationReportWriter.write(output, dataset, report);

        JsonNode root = objectMapper.readTree(Files.readString(output));
        assertThat(root.path("reportSchemaVersion").asText()).isEqualTo("3");
        assertThat(root.path("datasetId").asText()).isEqualTo("intent-fixture-fast-v1");
        assertThat(root.path("datasetCaseSha256").asText()).hasSize(64);
        assertThat(root.path("splitCounts").path("test").asInt()).isEqualTo(20);
        assertThat(root.path("decisionVersion").asText()).isEqualTo(IntentRouter.DECISION_VERSION);
        assertThat(root.path("runConfiguration").path("mode").asText()).isEqualTo("rule-only");
        assertThat(root.path("runConfiguration").path("routingConfigSha256").asText()).hasSize(64);
        assertThat(root.path("policyVersion").asText()).isEqualTo("intent-policy-v5");
        assertThat(root.path("eligibleForRelease").asBoolean()).isFalse();
        assertThat(root.path("releaseGateBlockers").toString()).contains("fixture_only_dataset");
        assertThat(root.path("metrics").path("evaluatedCases").asInt()).isEqualTo(20);
        assertThat(root.path("metrics").path("failedCases").asInt()).isZero();
        assertThat(root.path("metrics").path("accuracy").asDouble()).isEqualTo(1D);
        assertThat(root.path("metrics").path("macroF1").asDouble()).isEqualTo(1D);
        assertThat(root.path("metrics").path("decisionContractMatchCases").asInt()).isEqualTo(18);
        assertThat(root.path("metrics").path("decisionContractMatchRate").asDouble()).isEqualTo(0.90D);
        assertThat(root.path("metrics").path("perIntentMetrics").path("KNOWLEDGE_QA").path("precision").asDouble())
                .isEqualTo(1D);
    }

    @Test
    void shouldWriteDeterministicBytesForSameEvaluation() throws Exception {
        Path tempDir = testOutputDirectory();
        IntentEvaluationDataset dataset = IntentEvaluationDatasetLoader.load(
                "intent-eval/datasets/manifests/fixture-fast-v1.json"
        );
        IntentEvaluationReport report = IntentEvaluationEvaluator.evaluate(dataset, new IntentRouter());
        Path first = tempDir.resolve("first.json");
        Path second = tempDir.resolve("second.json");

        IntentEvaluationReportWriter.write(first, dataset, report);
        IntentEvaluationReportWriter.write(second, dataset, report);

        assertThat(Files.readAllBytes(first)).isEqualTo(Files.readAllBytes(second));
    }

    @Test
    void shouldWriteCandidateEvaluationAndKeepReleaseGateBlocked() throws Exception {
        Path tempDir = testOutputDirectory();
        IntentEvaluationDataset dataset = IntentEvaluationDatasetLoader.load(
                "intent-eval/datasets/manifests/intent-candidate-v1.json"
        );
        IntentEvaluationReport report = IntentEvaluationEvaluator.evaluate(dataset, new IntentRouter());
        Path output = tempDir.resolve("reports/intent-candidate-v1.json");

        IntentEvaluationReportWriter.write(output, dataset, report);

        JsonNode root = objectMapper.readTree(Files.readString(output));
        assertThat(root.path("datasetStatus").asText()).isEqualTo("candidate");
        assertThat(root.path("metrics").path("totalCases").asInt()).isEqualTo(300);
        assertThat(root.path("metrics").path("splitMetrics").path("dev").path("totalCases").asInt())
                .isEqualTo(200);
        assertThat(root.path("metrics").path("splitMetrics").path("test").path("totalCases").asInt())
                .isEqualTo(100);
        assertThat(root.path("metrics").path("splitMetrics").path("test").path("perIntentMetrics")
                .has("EXTERNAL_LOOKUP")).isTrue();
        assertThat(root.path("eligibleForRelease").asBoolean()).isFalse();
        assertThat(root.path("releaseGateBlockers").toString())
                .contains("dataset_not_frozen", "cases_not_fully_reviewed");
    }

    @Test
    void shouldWriteModelPromptAndRoutingConfigurationFingerprints() throws Exception {
        Path output = testOutputDirectory().resolve("reports/intent-fallback-fixture.json");
        IntentEvaluationDataset dataset = IntentEvaluationDatasetLoader.load(
                "intent-eval/datasets/manifests/fixture-fast-v1.json"
        );
        IntentRoutingProperties properties = new IntentRoutingProperties();
        properties.setLlmEnabled(true);
        properties.setModel("glm-4.6");
        properties.setModelVersion("glm-4.6-build-42");
        IntentFallbackRouter router = new IntentFallbackRouter(
                new IntentRouter(),
                objectMapper,
                query -> "{\"businessIntent\":\"KNOWLEDGE_QA\",\"confidence\":0.94,\"margin\":0.6}",
                properties,
                false,
                IntentDecisionTelemetry.noop()
        );
        properties.setModel("mutated-after-router-construction");
        properties.setModelVersion("mutated-version");
        IntentEvaluationReport report = IntentEvaluationEvaluator.evaluate(dataset, router);
        IntentEvaluationReportWriter.write(output, dataset, report);

        JsonNode root = objectMapper.readTree(Files.readString(output));
        assertThat(root.path("reportSchemaVersion").asText()).isEqualTo("3");
        assertThat(root.path("metrics").path("failedCases").asInt()).isZero();
        assertThat(root.path("decisionVersion").asText()).isEqualTo(IntentRouter.DECISION_VERSION);
        assertThat(report.runConfiguration().modelVersion()).isEqualTo("glm-4.6-build-42");
        assertThat(root.path("runConfiguration").path("mode").asText()).isEqualTo("llm-fallback");
        assertThat(root.path("runConfiguration").path("modelName").asText()).isEqualTo("glm-4.6");
        assertThat(root.path("runConfiguration").path("modelVersion").asText()).isEqualTo("glm-4.6-build-42");
        assertThat(root.path("runConfiguration").path("promptVersion").asText())
                .isEqualTo(ChatClientIntentModelClient.PROMPT_VERSION);
        assertThat(root.path("runConfiguration").path("routingConfigSha256").asText()).hasSize(64);
    }

    @Test
    void shouldChangeRoutingConfigurationFingerprintWhenThresholdsChange() {
        IntentRoutingProperties first = new IntentRoutingProperties();
        IntentRoutingProperties second = new IntentRoutingProperties();
        second.setMinMargin(0.24D);

        IntentEvaluationRunConfiguration firstConfiguration = IntentEvaluationRunConfiguration.from(first);
        IntentEvaluationRunConfiguration secondConfiguration = IntentEvaluationRunConfiguration.from(second);

        assertThat(firstConfiguration.routingConfigSha256()).hasSize(64);
        assertThat(secondConfiguration.routingConfigSha256()).hasSize(64);
        assertThat(firstConfiguration.routingConfigSha256())
                .isNotEqualTo(secondConfiguration.routingConfigSha256());
    }

    @Test
    void shouldBlockModelReportWhenModelVersionIsUnspecified() throws Exception {
        Path output = testOutputDirectory().resolve("reports/intent-unversioned-model.json");
        IntentEvaluationDataset dataset = IntentEvaluationDatasetLoader.load(
                "intent-eval/datasets/manifests/fixture-fast-v1.json"
        );
        IntentRoutingProperties properties = new IntentRoutingProperties();
        properties.setLlmEnabled(true);
        IntentFallbackRouter router = new IntentFallbackRouter(
                new IntentRouter(),
                objectMapper,
                query -> "{\"businessIntent\":\"KNOWLEDGE_QA\",\"confidence\":0.94,\"margin\":0.6}",
                properties,
                false,
                IntentDecisionTelemetry.noop()
        );
        IntentEvaluationReport report = IntentEvaluationEvaluator.evaluate(dataset, router);

        IntentEvaluationReportWriter.write(output, dataset, report);

        JsonNode root = objectMapper.readTree(Files.readString(output));
        assertThat(root.path("eligibleForRelease").asBoolean()).isFalse();
        assertThat(root.path("releaseGateBlockers").toString()).contains("model_version_unversioned");
    }

    @Test
    void shouldRejectDatasetWhenItDoesNotMatchTheEvaluationReport() throws Exception {
        IntentEvaluationDataset dataset = IntentEvaluationDatasetLoader.load(
                "intent-eval/datasets/manifests/fixture-fast-v1.json"
        );
        IntentEvaluationReport report = IntentEvaluationEvaluator.evaluate(dataset, new IntentRouter());
        IntentEvaluationDataset differentDataset = new IntentEvaluationDataset(
                dataset.manifest(),
                dataset.cases(),
                "f".repeat(64)
        );

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> IntentEvaluationReportWriter.write(
                testOutputDirectory().resolve("reports/mismatched-evaluation.json"),
                differentDataset,
                report
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("datasetCaseSha256");
    }

    private Path testOutputDirectory() throws Exception {
        Path directory = Path.of("target/intent-eval-report-test");
        Files.createDirectories(directory);
        return directory;
    }
}
