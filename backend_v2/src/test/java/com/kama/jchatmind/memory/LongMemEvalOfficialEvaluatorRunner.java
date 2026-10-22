package com.kama.jchatmind.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

final class LongMemEvalOfficialEvaluatorRunner {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Map<String, String> METRIC_MODELS = Map.of(
            "llama-3.1-70b-instruct", "meta-llama/Meta-Llama-3.1-70B-Instruct",
            "gpt-4o-mini", "gpt-4o-mini-2024-07-18",
            "gpt-4o", "gpt-4o-2024-08-06"
    );

    private LongMemEvalOfficialEvaluatorRunner() {
    }

    static Result run(
            LongMemEvalReplayPreflightGate.Evidence evidence,
            DatasetIdentity dataset,
            List<LongMemEvalOfficialEvaluatorAdapter.Prediction> predictions,
            List<LongMemEvalOfficialEvaluatorAdapter.Reference> references,
            Config config,
            ProcessExecutor processExecutor
    ) {
        return run(evidence, dataset, predictions, references, config, processExecutor,
                LongMemEvalOfficialEvaluatorRunner::repositoryEvaluatorPin);
    }

    static Result run(
            LongMemEvalReplayPreflightGate.Evidence evidence,
            DatasetIdentity dataset,
            List<LongMemEvalOfficialEvaluatorAdapter.Prediction> predictions,
            List<LongMemEvalOfficialEvaluatorAdapter.Reference> references,
            Config config,
            ProcessExecutor processExecutor,
            OfficialEvaluatorPinSource pinSource
    ) {
        Objects.requireNonNull(evidence);
        Objects.requireNonNull(config);
        Objects.requireNonNull(processExecutor);
        LongMemEvalScoringInputPreparation.Result preparation = LongMemEvalScoringInputPreparation.prepare(
                evidence, predictions, references
        );
        if (!preparation.ready()) {
            return new Result("blocked", preparation.blockers(), null);
        }
        String configBlocker;
        try {
            Objects.requireNonNull(pinSource);
            configBlocker = validateConfig(dataset, config, pinSource.load());
        } catch (IOException exception) {
            return new Result("failed", List.of("official_evaluator_pin_unavailable"), null);
        }
        if (configBlocker != null) {
            return new Result("failed", List.of(configBlocker), null);
        }

        try {
            Files.createDirectories(config.runDirectory().getParent());
            Files.createDirectory(config.runDirectory());
            Path hypothesesPath = config.runDirectory().resolve("hypotheses.jsonl");
            Path referencesPath = config.runDirectory().resolve("references.json");
            Path resultsPath = Path.of(hypothesesPath + ".eval-results-" + config.metricModel());
            Files.writeString(hypothesesPath, preparation.inputs().hypothesesJsonl());
            Files.writeString(referencesPath, preparation.inputs().referencesJson());

            long startedAt = System.nanoTime();
            ProcessExecution processExecution;
            try {
                processExecution = processExecutor.execute(new Invocation(
                        List.of(config.pythonExecutable(), config.evaluatorScript().toString(),
                                config.metricModel(), hypothesesPath.toString(), referencesPath.toString()),
                        config.runDirectory(),
                        config.timeout(),
                        resultsPath
                ));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return new Result("failed", List.of("evaluator_interrupted"), null);
            } catch (IOException exception) {
                return new Result("failed", List.of("evaluator_launch_failed"), null);
            }
            long elapsedMillis = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
            if (processExecution.timedOut()) {
                return new Result("failed", List.of("evaluator_timed_out"), null);
            }
            if (processExecution.exitCode() != 0) {
                return new Result("failed", List.of("evaluator_nonzero_exit"), null);
            }

            ScoreSummary summary = summarize(
                    dataset, predictions, references, resultsPath, config.metricModel(), elapsedMillis
            );
            Files.writeString(config.runDirectory().resolve("score-summary.json"),
                    OBJECT_MAPPER.writeValueAsString(summary));
            return new Result("complete", List.of(), summary);
        } catch (IOException | RuntimeException exception) {
            return new Result("failed", List.of("evaluator_output_invalid"), null);
        }
    }

    private static String validateConfig(DatasetIdentity dataset, Config config, OfficialEvaluatorPin pin)
            throws IOException {
        if (dataset == null || !StringUtils.hasText(dataset.datasetId())
                || !isSha256(dataset.sourceSha256())
                || !isSha256(dataset.manifestSha256())
                || !isSha256(dataset.caseSha256())
                || pin == null || !isSha256(pin.sourceSha256()) || !isSha256(pin.evaluatorSha256())
                || !dataset.datasetId().equals(pin.datasetId())
                || !dataset.sourceSha256().equals(pin.sourceSha256())) {
            return "dataset_identity_invalid";
        }
        if (config.evaluatorScript() == null || !Files.isRegularFile(config.evaluatorScript())
                || !pin.evaluatorSha256().equals(sha256(config.evaluatorScript()))) {
            return "official_evaluator_pin_mismatch";
        }
        if (!StringUtils.hasText(config.pythonExecutable()) || !StringUtils.hasText(config.metricModel())
                || !METRIC_MODELS.containsKey(config.metricModel())) {
            return "official_evaluator_config_invalid";
        }
        if (config.runDirectory() == null || config.runDirectory().getParent() == null
                || Files.exists(config.runDirectory())) {
            return "run_directory_invalid_or_exists";
        }
        if (config.timeout() == null || config.timeout().isZero() || config.timeout().isNegative()) {
            return "evaluator_timeout_invalid";
        }
        return null;
    }

    private static OfficialEvaluatorPin repositoryEvaluatorPin() throws IOException {
        try (var input = LongMemEvalOfficialEvaluatorRunner.class.getClassLoader()
                .getResourceAsStream("memory-eval/longmemeval-source-pin-v1.json")) {
            if (input == null) {
                throw new IOException("LongMemEval source pin resource is unavailable");
            }
            JsonNode pin = OBJECT_MAPPER.readTree(input);
            return new OfficialEvaluatorPin(
                    pin.path("projectSubset").path("datasetId").asText(),
                    pin.path("source").path("sha256").asText(),
                    pin.path("officialEvaluator").path("sha256").asText()
            );
        }
    }

    private static ScoreSummary summarize(
            DatasetIdentity dataset,
            List<LongMemEvalOfficialEvaluatorAdapter.Prediction> predictions,
            List<LongMemEvalOfficialEvaluatorAdapter.Reference> references,
            Path resultsPath,
            String metricModel,
            long elapsedMillis
    ) throws IOException {
        Map<String, LongMemEvalOfficialEvaluatorAdapter.Prediction> predictionById = new LinkedHashMap<>();
        predictions.forEach(prediction -> predictionById.put(prediction.questionId(), prediction));
        Map<String, LongMemEvalOfficialEvaluatorAdapter.Reference> referenceById = new LinkedHashMap<>();
        references.forEach(reference -> referenceById.put(reference.questionId(), reference));
        Map<String, Boolean> labels = new LinkedHashMap<>();
        Map<String, int[]> countsByType = new LinkedHashMap<>();
        try (var lines = Files.lines(resultsPath)) {
            for (String line : lines.toList()) {
                JsonNode result = OBJECT_MAPPER.readTree(line);
                String questionId = result.path("question_id").asText();
                LongMemEvalOfficialEvaluatorAdapter.Prediction prediction = predictionById.get(questionId);
                LongMemEvalOfficialEvaluatorAdapter.Reference reference = referenceById.get(questionId);
                JsonNode label = result.path("autoeval_label");
                if (prediction == null || reference == null || labels.containsKey(questionId)
                        || !prediction.hypothesis().equals(result.path("hypothesis").asText())
                        || !METRIC_MODELS.get(metricModel).equals(label.path("model").asText())
                        || !label.path("label").isBoolean()) {
                    throw new IllegalStateException("official evaluator result case is invalid");
                }
                boolean correct = label.path("label").asBoolean();
                labels.put(questionId, correct);
                int[] counts = countsByType.computeIfAbsent(reference.questionType(), ignored -> new int[2]);
                counts[0]++;
                if (correct) {
                    counts[1]++;
                }
            }
        }
        if (!labels.keySet().equals(referenceById.keySet())) {
            throw new IllegalStateException("official evaluator result case coverage mismatch");
        }
        int correctCount = (int) labels.values().stream().filter(Boolean::booleanValue).count();
        Map<String, CategoryScore> categoryScores = new LinkedHashMap<>();
        countsByType.forEach((questionType, counts) -> categoryScores.put(
                questionType,
                new CategoryScore(counts[0], counts[1], counts[1] / (double) counts[0])
        ));
        return new ScoreSummary(
                dataset.datasetId(), dataset.sourceSha256(), dataset.manifestSha256(), dataset.caseSha256(),
                metricModel, labels.size(), correctCount, correctCount / (double) labels.size(),
                categoryScores, labels, elapsedMillis, true, false
        );
    }

    private static boolean isSha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static String sha256(Path path) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    static ProcessExecutor systemProcessExecutor() {
        return invocation -> {
            Process process = new ProcessBuilder(invocation.command())
                    .directory(invocation.workingDirectory().toFile())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(invocation.timeout().toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return new ProcessExecution(-1, true);
            }
            return new ProcessExecution(process.exitValue(), false);
        };
    }

    @FunctionalInterface
    interface ProcessExecutor {
        ProcessExecution execute(Invocation invocation) throws IOException, InterruptedException;
    }

    @FunctionalInterface
    interface OfficialEvaluatorPinSource {
        OfficialEvaluatorPin load() throws IOException;
    }

    record OfficialEvaluatorPin(String datasetId, String sourceSha256, String evaluatorSha256) {
    }

    record DatasetIdentity(String datasetId, String sourceSha256, String manifestSha256, String caseSha256) {
    }

    record Config(
            Path evaluatorScript,
            String pythonExecutable,
            String metricModel,
            Path runDirectory,
            Duration timeout
    ) {
    }

    record Invocation(List<String> command, Path workingDirectory, Duration timeout, Path resultsPath) {
    }

    record ProcessExecution(int exitCode, boolean timedOut) {
    }

    record CategoryScore(int caseCount, int correctCount, double accuracy) {
    }

    record ScoreSummary(
            String datasetId,
            String sourceSha256,
            String manifestSha256,
            String caseSha256,
            String metricModel,
            int caseCount,
            int correctCount,
            double accuracy,
            Map<String, CategoryScore> byQuestionType,
            Map<String, Boolean> labelsByQuestionId,
            long evaluatorElapsedMs,
            boolean candidateOnly,
            boolean releaseEligible
    ) {
    }

    record Result(String status, List<String> blockers, ScoreSummary report) {
    }
}
