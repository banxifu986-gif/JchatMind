package com.kama.jchatmind.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("l2")
class IntentPerformanceL2Test {

    @Test
    void shouldStayWithinLatencyBudgetForContextualRouting() throws Exception {
        IntentEvaluationDataset dataset = IntentEvaluationDatasetLoader.load(
                "intent-eval/datasets/manifests/intent-candidate-v1.json"
        );

        IntentEvaluationLatencyReport report = IntentEvaluationPerformanceEvaluator.evaluate(
                dataset,
                new IntentRouter(),
                3,
                5
        );
        Path output = Path.of("target/intent-eval-performance/intent-policy-v5-context-p95.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, new ObjectMapper().writeValueAsString(report));

        assertThat(report.sampleCountPerMode()).isEqualTo(1500);
        assertThat(report.baselineP95Nanos()).isPositive();
        assertThat(report.contextualP95Nanos()).isPositive();
        assertThat(report.withinBudget()).isTrue();
    }
}
