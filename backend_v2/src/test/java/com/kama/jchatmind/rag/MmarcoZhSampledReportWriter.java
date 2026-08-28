package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class MmarcoZhSampledReportWriter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    void write(
            Path reportPath,
            String runId,
            String rerankerModel,
            List<MmarcoZhSampledEvaluator.VariantRun> runs,
            MmarcoZhSampledEvaluator.Comparison comparison
    ) throws IOException {
        if (runId == null || runId.isBlank() || rerankerModel == null || rerankerModel.isBlank()
                || runs == null || runs.isEmpty() || comparison == null) {
            throw new IllegalStateException("mMARCO 报告缺少实验臂或 B/C 对比结果");
        }
        MmarcoZhSampledEvaluator.EvaluationFingerprint fingerprint = runs.get(0).fingerprint();
        if (fingerprint == null || runs.stream().anyMatch(run -> !fingerprint.equals(run.fingerprint()))) {
            throw new IllegalStateException("mMARCO 报告不能混合不同冻结输入");
        }
        int sampleSize = runs.get(0).replays().size();
        if (runs.stream().anyMatch(run -> run.replays().size() != sampleSize)) {
            throw new IllegalStateException("mMARCO 报告实验臂样本分母不一致");
        }
        MmarcoZhSampledEvaluator evaluator = new MmarcoZhSampledEvaluator();
        List<MmarcoZhSampledEvaluator.EvaluationReport> evaluations = runs.stream()
                .map(evaluator::evaluate)
                .toList();
        Report report = new Report(
                runId,
                fingerprint.datasetVersion(),
                fingerprint.sourceSha256(),
                fingerprint.candidateManifestSha256(),
                fingerprint.mappingVersion(),
                fingerprint.indexVersion(),
                fingerprint.embeddingModel(),
                fingerprint.bm25DictionaryVersion(),
                fingerprint.rrfConfigSha256(),
                rerankerModel,
                fingerprint.topK(),
                fingerprint.candidateBudget(),
                fingerprint.querySetSha256(),
                sampleSize,
                toArmReports(runs, evaluations),
                comparison
        );
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }

    private List<ArmReport> toArmReports(
            List<MmarcoZhSampledEvaluator.VariantRun> runs,
            List<MmarcoZhSampledEvaluator.EvaluationReport> evaluations
    ) {
        return java.util.stream.IntStream.range(0, runs.size())
                .mapToObj(index -> toArmReport(runs.get(index), evaluations.get(index)))
                .toList();
    }

    private ArmReport toArmReport(
            MmarcoZhSampledEvaluator.VariantRun variantRun,
            MmarcoZhSampledEvaluator.EvaluationReport evaluation
    ) {
        MmarcoZhSampledEvaluator.Metrics metrics = evaluation.metrics();
        return new ArmReport(
                evaluation.variant(),
                evaluation.status(),
                evaluation.validCount(),
                evaluation.invalidCount(),
                evaluation.invalidReasons(),
                evaluation.teiSuccessRate(),
                new RetrievalMetrics(
                        metrics.recallAt1(),
                        metrics.recallAt3(),
                        metrics.recallAt5(),
                        metrics.recallAt10(),
                        metrics.mrrAt10(),
                        metrics.ndcgAt10(),
                        metrics.p50LatencyMs(),
                        metrics.p95LatencyMs()
                ),
                new RagasReport(
                        "id_based",
                        metrics.contextPrecisionAt10(),
                        metrics.contextRecallAt10()
                ),
                variantRun.replays()
        );
    }

    private record Report(
            String runId,
            String datasetVersion,
            String sourceSha256,
            String candidateManifestSha256,
            String mappingVersion,
            String indexVersion,
            String embeddingModel,
            String bm25DictionaryVersion,
            String configSha256,
            String rerankerModel,
            int topK,
            int candidateBudget,
            String querySetSha256,
            int sampleSize,
            List<ArmReport> runs,
            MmarcoZhSampledEvaluator.Comparison comparison
    ) {
    }

    private record ArmReport(
            String variant,
            String status,
            int validCount,
            int invalidCount,
            List<String> invalidReasons,
            Double teiSuccessRate,
            RetrievalMetrics metrics,
            RagasReport ragas,
            List<MmarcoZhSampledEvaluator.QueryReplay> queryReplays
    ) {
    }

    private record RetrievalMetrics(
            double recallAt1,
            double recallAt3,
            double recallAt5,
            double recallAt10,
            double mrrAt10,
            double ndcgAt10,
            long p50LatencyMs,
            long p95LatencyMs
    ) {
    }

    private record RagasReport(
            String status,
            double idBasedContextPrecisionAt10,
            double idBasedContextRecallAt10
    ) {
    }
}
