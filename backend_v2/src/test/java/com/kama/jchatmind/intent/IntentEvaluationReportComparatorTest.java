package com.kama.jchatmind.intent;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class IntentEvaluationReportComparatorTest {

    @Test
    void shouldCompareModelAndPromptVersionsOnTheSameDatasetFingerprint() throws Exception {
        IntentEvaluationDataset dataset = IntentEvaluationDatasetLoader.load(
                "intent-eval/datasets/manifests/fixture-fast-v1.json"
        );
        IntentRouter ruleRouter = new IntentRouter();
        IntentRoutingProperties properties = new IntentRoutingProperties();
        properties.setLlmEnabled(true);
        properties.setModel("comparison-stub");
        properties.setModelVersion("comparison-stub-v1");
        IntentFallbackRouter modelRouter = new IntentFallbackRouter(
                new IntentRouter(),
                new com.fasterxml.jackson.databind.ObjectMapper(),
                query -> "{\"businessIntent\":\"DIRECT_CHAT\",\"confidence\":0.95,\"margin\":0.8}",
                properties,
                false,
                IntentDecisionTelemetry.noop()
        );
        Path baselinePath = Path.of("target/intent-eval-report-test/comparison/rule-baseline.json");
        Path candidatePath = Path.of("target/intent-eval-report-test/comparison/model-candidate.json");
        IntentEvaluationReportWriter.write(
                baselinePath,
                dataset,
                IntentEvaluationEvaluator.evaluate(dataset, ruleRouter)
        );
        IntentEvaluationReportWriter.write(
                candidatePath,
                dataset,
                IntentEvaluationEvaluator.evaluate(dataset, modelRouter)
        );

        IntentEvaluationReportComparison comparison = IntentEvaluationReportComparator.compare(
                baselinePath,
                candidatePath
        );

        assertThat(comparison.datasetId()).isEqualTo("intent-fixture-fast-v1");
        assertThat(comparison.datasetCaseSha256()).isEqualTo(dataset.effectiveCaseSha256());
        assertThat(comparison.decisionVersion()).isEqualTo(IntentRouter.DECISION_VERSION);
        assertThat(comparison.baselinePolicyVersion()).isEqualTo(IntentRouter.POLICY_VERSION);
        assertThat(comparison.candidatePolicyVersion()).isEqualTo(IntentRouter.POLICY_VERSION);
        assertThat(comparison.baselineRunConfiguration().mode()).isEqualTo("rule-only");
        assertThat(comparison.candidateRunConfiguration().modelVersion()).isEqualTo("comparison-stub-v1");
        assertThat(comparison.accuracyDelta()).isCloseTo(-0.15D, org.assertj.core.data.Offset.offset(0.0001D));
        assertThat(comparison.macroF1Delta()).isNegative();
        assertThat(comparison.decisionContractMatchRateDelta()).isNegative();
        assertThat(comparison.perIntentF1Delta()).containsKey("CLARIFY");
    }

    @Test
    void shouldRejectReportsWithDifferentEffectiveCaseHashes() throws Exception {
        IntentEvaluationDataset dataset = IntentEvaluationDatasetLoader.load(
                "intent-eval/datasets/manifests/fixture-fast-v1.json"
        );
        IntentEvaluationReport report = IntentEvaluationEvaluator.evaluate(dataset, new IntentRouter());
        IntentEvaluationDataset differentCases = new IntentEvaluationDataset(
                dataset.manifest(),
                dataset.cases(),
                "f".repeat(64)
        );
        Path baselinePath = Path.of("target/intent-eval-report-test/comparison/hash-baseline.json");
        Path differentPath = Path.of("target/intent-eval-report-test/comparison/hash-different.json");
        IntentEvaluationReportWriter.write(
                baselinePath,
                dataset,
                report
        );
        ObjectNode differentReport = (ObjectNode) new ObjectMapper().readTree(baselinePath.toFile());
        differentReport.put("datasetCaseSha256", differentCases.effectiveCaseSha256());
        Files.writeString(differentPath, new ObjectMapper().writeValueAsString(differentReport));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                IntentEvaluationReportComparator.compare(baselinePath, differentPath)
        ).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("datasetCaseSha256");
    }
}
