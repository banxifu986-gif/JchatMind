package com.kama.jchatmind.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class LongMemEvalOfficialEvaluatorRunnerTest {

    @TempDir
    Path tempDirectory;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void blocksWithoutWritingInputsOrLaunchingEvaluatorWhenPreflightIsIncomplete() {
        Path runDirectory = tempDirectory.resolve("blocked-run");
        AtomicInteger launches = new AtomicInteger();

        LongMemEvalOfficialEvaluatorRunner.Result result = LongMemEvalOfficialEvaluatorRunner.run(
                new LongMemEvalReplayPreflightGate.Evidence(true, true, false, false, true, false, true),
                datasetIdentity(),
                predictions(),
                references(),
                new LongMemEvalOfficialEvaluatorRunner.Config(
                        tempDirectory.resolve("missing-evaluator.py"),
                        "python",
                        "gpt-4o-mini",
                        runDirectory,
                        Duration.ofMinutes(5)
                ),
                invocation -> {
                    launches.incrementAndGet();
                    return new LongMemEvalOfficialEvaluatorRunner.ProcessExecution(0, false);
                }
        );

        assertThat(result.status()).isEqualTo("blocked");
        assertThat(result.blockers()).contains("m2_review_incomplete", "m2_decisions_not_applied", "g2_gates_not_eligible");
        assertThat(launches).hasValue(0);
        assertThat(runDirectory).doesNotExist();
    }

    @Test
    void launchesPinnedEvaluatorAndSummarizesExactCandidateCoverage() throws Exception {
        Path evaluatorScript = tempDirectory.resolve("evaluate_qa.py");
        Files.writeString(evaluatorScript, "pinned evaluator fixture");
        String evaluatorSha256 = sha256(evaluatorScript);
        Path runDirectory = tempDirectory.resolve("scoring-run");

        LongMemEvalOfficialEvaluatorRunner.Result result = LongMemEvalOfficialEvaluatorRunner.run(
                allEvidenceReady(),
                datasetIdentity(),
                predictions(),
                references(),
                evaluatorConfig(evaluatorScript, runDirectory),
                invocation -> {
                    assertThat(invocation.command()).containsExactly(
                            "python",
                            evaluatorScript.toString(),
                            "gpt-4o-mini",
                            runDirectory.resolve("hypotheses.jsonl").toString(),
                            runDirectory.resolve("references.json").toString()
                    );
                    assertThat(Files.readString(runDirectory.resolve("hypotheses.jsonl")))
                            .contains("\"hypothesis\":\"Project answer one\"")
                            .doesNotContain("Reference answer");
                    assertThat(objectMapper.readTree(Files.readString(runDirectory.resolve("references.json")))
                            .get(0).path("answer").asText()).isEqualTo("Reference answer one");
                    Files.writeString(invocation.resultsPath(), """
                            {"question_id":"case-1","hypothesis":"Project answer one","autoeval_label":{"model":"gpt-4o-mini-2024-07-18","label":true}}
                            {"question_id":"case-2_abs","hypothesis":"I do not know","autoeval_label":{"model":"gpt-4o-mini-2024-07-18","label":false}}
                            """);
                    return new LongMemEvalOfficialEvaluatorRunner.ProcessExecution(0, false);
                },
                () -> new LongMemEvalOfficialEvaluatorRunner.OfficialEvaluatorPin(
                        "longmemeval-30-v1", "a".repeat(64), evaluatorSha256
                )
        );

        assertThat(result.status()).isEqualTo("complete");
        assertThat(result.blockers()).isEmpty();
        assertThat(result.report()).isNotNull();
        assertThat(result.report().caseCount()).isEqualTo(2);
        assertThat(result.report().correctCount()).isEqualTo(1);
        assertThat(result.report().accuracy()).isEqualTo(0.5D);
        assertThat(result.report().labelsByQuestionId()).containsEntry("case-1", true)
                .containsEntry("case-2_abs", false);
        assertThat(result.report().candidateOnly()).isTrue();
        assertThat(result.report().releaseEligible()).isFalse();
        assertThat(runDirectory.resolve("score-summary.json")).exists();
    }

    @Test
    void refusesToLaunchWhenEvaluatorScriptDoesNotMatchItsPin() throws Exception {
        Path evaluatorScript = tempDirectory.resolve("tampered-evaluator.py");
        Files.writeString(evaluatorScript, "modified evaluator");
        Path runDirectory = tempDirectory.resolve("tampered-run");
        AtomicInteger launches = new AtomicInteger();

        LongMemEvalOfficialEvaluatorRunner.Result result = LongMemEvalOfficialEvaluatorRunner.run(
                allEvidenceReady(),
                pinnedDatasetIdentity(),
                predictions(),
                references(),
                evaluatorConfig(evaluatorScript, runDirectory),
                invocation -> {
                    launches.incrementAndGet();
                    return new LongMemEvalOfficialEvaluatorRunner.ProcessExecution(0, false);
                }
        );

        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.blockers()).containsExactly("official_evaluator_pin_mismatch");
        assertThat(launches).hasValue(0);
        assertThat(runDirectory).doesNotExist();
    }

    @Test
    void reportsEvaluatorLaunchFailureWithoutExposingExceptionText() throws Exception {
        Path evaluatorScript = tempDirectory.resolve("launch-failure-evaluator.py");
        Files.writeString(evaluatorScript, "pinned evaluator fixture");
        String evaluatorSha256 = sha256(evaluatorScript);
        Path runDirectory = tempDirectory.resolve("launch-failure-run");

        LongMemEvalOfficialEvaluatorRunner.Result result = LongMemEvalOfficialEvaluatorRunner.run(
                allEvidenceReady(),
                datasetIdentity(),
                predictions(),
                references(),
                evaluatorConfig(evaluatorScript, runDirectory),
                invocation -> {
                    throw new java.io.IOException("private process details");
                },
                () -> new LongMemEvalOfficialEvaluatorRunner.OfficialEvaluatorPin(
                        "longmemeval-30-v1", "a".repeat(64), evaluatorSha256
                )
        );

        assertThat(result.status()).isEqualTo("failed");
        assertThat(result.blockers()).containsExactly("evaluator_launch_failed");
        assertThat(result.blockers()).doesNotContain("private process details");
        assertThat(runDirectory.resolve("hypotheses.jsonl")).exists();
        assertThat(runDirectory.resolve("references.json")).exists();
        assertThat(runDirectory.resolve("score-summary.json")).doesNotExist();
    }

    private LongMemEvalReplayPreflightGate.Evidence allEvidenceReady() {
        return new LongMemEvalReplayPreflightGate.Evidence(true, true, true, true, true, true, true);
    }

    private LongMemEvalOfficialEvaluatorRunner.DatasetIdentity datasetIdentity() {
        return new LongMemEvalOfficialEvaluatorRunner.DatasetIdentity(
                "longmemeval-30-v1", "a".repeat(64), "b".repeat(64), "c".repeat(64)
        );
    }

    private List<LongMemEvalOfficialEvaluatorAdapter.Prediction> predictions() {
        return List.of(
                new LongMemEvalOfficialEvaluatorAdapter.Prediction("case-1", "Project answer one"),
                new LongMemEvalOfficialEvaluatorAdapter.Prediction("case-2_abs", "I do not know")
        );
    }

    private List<LongMemEvalOfficialEvaluatorAdapter.Reference> references() {
        return List.of(
                new LongMemEvalOfficialEvaluatorAdapter.Reference(
                        "case-1", "multi-session", "Question one", "Reference answer one"
                ),
                new LongMemEvalOfficialEvaluatorAdapter.Reference(
                        "case-2_abs", "temporal-reasoning", "Question two", "unanswerable"
                )
        );
    }

    private LongMemEvalOfficialEvaluatorRunner.Config evaluatorConfig(Path script, Path runDirectory) {
        return new LongMemEvalOfficialEvaluatorRunner.Config(
                script,
                "python",
                "gpt-4o-mini",
                runDirectory,
                Duration.ofMinutes(5)
        );
    }

    private LongMemEvalOfficialEvaluatorRunner.DatasetIdentity pinnedDatasetIdentity() throws Exception {
        try (var input = getClass().getResourceAsStream("/memory-eval/longmemeval-source-pin-v1.json")) {
            var pin = objectMapper.readTree(input);
            return new LongMemEvalOfficialEvaluatorRunner.DatasetIdentity(
                    pin.path("projectSubset").path("datasetId").asText(),
                    pin.path("source").path("sha256").asText(),
                    "b".repeat(64),
                    "c".repeat(64)
            );
        }
    }

    private String sha256(Path path) throws Exception {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
        );
    }
}
