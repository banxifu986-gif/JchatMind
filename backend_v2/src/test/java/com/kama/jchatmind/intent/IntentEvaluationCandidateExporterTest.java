package com.kama.jchatmind.intent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

class IntentEvaluationCandidateExporterTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void shouldExportCandidateCasesAsDeterministicReviewableJsonl() throws Exception {
        IntentEvaluationDataset dataset = IntentEvaluationDatasetLoader.load(
                "intent-eval/datasets/manifests/intent-candidate-v1.json"
        );
        Path output = Path.of("target/intent-eval-review/intent-candidate-v1-seed-paraphrase-expansion-v4.jsonl");

        IntentEvaluationCandidateExport export = IntentEvaluationCandidateExporter.write(output, dataset);
        String jsonl = Files.readString(output, StandardCharsets.UTF_8);
        String[] lines = jsonl.strip().split("\\R");

        assertThat(export.caseCount()).isEqualTo(300);
        assertThat(export.sha256()).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(jsonl.getBytes(StandardCharsets.UTF_8))
        ));
        assertThat(lines).hasSize(300);
        assertThat(objectMapper.readTree(lines[0]).path("reviewStatus").asText()).isEqualTo("pending_review");
        assertThat(objectMapper.readTree(lines[0]).path("tags").toString()).contains("seed-family:");
        JsonNode exportManifest = objectMapper.readTree(Files.readString(export.manifestPath()));
        assertThat(exportManifest.path("status").asText()).isEqualTo("candidate");
        assertThat(exportManifest.path("caseSha256").asText()).isEqualTo(export.sha256());
        assertThat(exportManifest.path("caseCount").asInt()).isEqualTo(300);
        assertThat(exportManifest.path("generatorVersion").asText())
                .isEqualTo("seed-paraphrase-expansion-v4");
        assertThat(exportManifest.path("splitCounts").path("dev").asInt()).isEqualTo(200);
        assertThat(exportManifest.path("splitCounts").path("test").asInt()).isEqualTo(100);
    }

    @Test
    void shouldRejectFrozenDatasetExport() throws Exception {
        IntentEvaluationDataset dataset = IntentEvaluationDatasetLoader.load(
                "intent-eval/datasets/manifests/fixture-fast-v1.json"
        );

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> IntentEvaluationCandidateExporter.write(
                Path.of("target/intent-eval-review/should-not-export.jsonl"),
                dataset
        )).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void shouldNotOverwriteExistingReviewContent() throws Exception {
        IntentEvaluationDataset dataset = IntentEvaluationDatasetLoader.load(
                "intent-eval/datasets/manifests/intent-candidate-v1.json"
        );
        Path output = Path.of("target/intent-eval-review/modified-by-reviewer.jsonl");
        Files.createDirectories(output.getParent());
        String reviewedContent = "reviewer-edited-case\n";
        Files.writeString(output, reviewedContent, StandardCharsets.UTF_8);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                IntentEvaluationCandidateExporter.write(output, dataset)
        ).isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("already exists with different content");
        assertThat(Files.readString(output, StandardCharsets.UTF_8)).isEqualTo(reviewedContent);
    }
}
