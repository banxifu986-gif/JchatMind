package com.kama.jchatmind.rag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

final class MmarcoZhSampledEvaluator {

    private static final String TEI_VARIANT = "tei-bge-rerank";
    private static final int TOP_K = 10;
    private static final int BOOTSTRAP_SAMPLES = 1_000;
    private static final long BOOTSTRAP_SEED = 20_260_825L;

    Map<String, String> stableChunkIdMapping(List<String> passageIds) {
        if (passageIds == null || passageIds.isEmpty()) {
            throw new IllegalStateException("mMARCO passage 映射不能为空");
        }
        Map<String, String> mapping = new LinkedHashMap<>();
        for (String passageId : passageIds) {
            if (passageId == null || passageId.isBlank() || mapping.putIfAbsent(
                    passageId, "mmarco:zh:" + passageId
            ) != null) {
                throw new IllegalStateException("mMARCO passage ID 为空或重复: " + passageId);
            }
        }
        return Map.copyOf(mapping);
    }

    EvaluationReport evaluate(VariantRun run) {
        validateRun(run);
        boolean teiVariant = TEI_VARIANT.equals(run.variant());
        List<QueryReplay> validReplays = run.replays().stream()
                .filter(item -> !item.teiFallback())
                .toList();
        int invalidCount = run.replays().size() - validReplays.size();
        boolean invalidTeiArm = teiVariant && invalidCount > 0;
        List<String> invalidReasons = invalidTeiArm ? List.of("invalid_tei_fallback") : List.of();
        return new EvaluationReport(
                run.variant(),
                run.fingerprint(),
                invalidTeiArm ? "invalid" : "valid",
                validReplays.size(),
                invalidCount,
                invalidReasons,
                teiVariant ? validReplays.size() / (double) run.replays().size() : null,
                metrics(validReplays)
        );
    }

    Comparison compare(VariantRun localRuleRun, VariantRun teiRun) {
        validateComparableInputs(localRuleRun, teiRun);
        EvaluationReport localReport = evaluate(localRuleRun);
        EvaluationReport teiReport = evaluate(teiRun);
        if (!"valid".equals(teiReport.status())) {
            return new Comparison("invalid_tei_fallback", localReport, teiReport, null);
        }

        Map<String, QueryReplay> localByQueryId = byQueryId(localRuleRun.replays());
        Map<String, QueryReplay> teiByQueryId = byQueryId(teiRun.replays());
        List<Double> mrrDifferences = new ArrayList<>();
        List<Double> ndcgDifferences = new ArrayList<>();
        for (String queryId : localByQueryId.keySet()) {
            QueryReplay localReplay = localByQueryId.get(queryId);
            QueryReplay teiReplay = teiByQueryId.get(queryId);
            mrrDifferences.add(mrrAt(teiReplay, TOP_K) - mrrAt(localReplay, TOP_K));
            ndcgDifferences.add(ndcgAt(teiReplay, TOP_K) - ndcgAt(localReplay, TOP_K));
        }
        BootstrapSummary bootstrap = new BootstrapSummary(
                BOOTSTRAP_SAMPLES,
                bootstrap(mrrDifferences),
                bootstrap(ndcgDifferences)
        );
        boolean eligible = teiReport.metrics().mrrAt10() >= localReport.metrics().mrrAt10()
                && teiReport.metrics().ndcgAt10() >= localReport.metrics().ndcgAt10()
                && teiReport.metrics().p95LatencyMs() <= localReport.metrics().p95LatencyMs() * 1.15D;
        return new Comparison(
                eligible ? "eligible_for_full_validation" : "inconclusive",
                localReport,
                teiReport,
                bootstrap
        );
    }

    private void validateComparableInputs(VariantRun localRuleRun, VariantRun teiRun) {
        validateRun(localRuleRun);
        validateRun(teiRun);
        if (!localRuleRun.fingerprint().equals(teiRun.fingerprint())) {
            throw new IllegalStateException("B/C rerank 评测冻结输入不一致");
        }
        if (!byQueryId(localRuleRun.replays()).keySet().equals(byQueryId(teiRun.replays()).keySet())) {
            throw new IllegalStateException("B/C rerank 评测有效 query 集不一致");
        }
    }

    private void validateRun(VariantRun run) {
        if (run == null || run.variant() == null || run.variant().isBlank()
                || run.fingerprint() == null || run.replays() == null || run.replays().isEmpty()) {
            throw new IllegalStateException("mMARCO 评测运行信息不完整");
        }
        byQueryId(run.replays());
        for (QueryReplay replay : run.replays()) {
            if (replay.goldChunkIds() == null || replay.goldChunkIds().isEmpty()
                    || replay.rankedChunkIds() == null || replay.latencyMs() < 0) {
                throw new IllegalStateException("mMARCO query replay 不完整: " + replay.queryId());
            }
        }
    }

    private Map<String, QueryReplay> byQueryId(List<QueryReplay> replays) {
        Map<String, QueryReplay> byQueryId = new LinkedHashMap<>();
        for (QueryReplay replay : replays) {
            if (replay == null || replay.queryId() == null || replay.queryId().isBlank()
                    || byQueryId.putIfAbsent(replay.queryId(), replay) != null) {
                throw new IllegalStateException("mMARCO query ID 为空或重复");
            }
        }
        return Map.copyOf(byQueryId);
    }

    private Metrics metrics(List<QueryReplay> replays) {
        if (replays.isEmpty()) {
            return new Metrics(0D, 0D, 0D, 0D, 0D, 0D, 0D, 0D, 0L, 0L);
        }
        return new Metrics(
                average(replays, item -> hitAt(item, 1)),
                average(replays, item -> hitAt(item, 3)),
                average(replays, item -> hitAt(item, 5)),
                average(replays, item -> hitAt(item, 10)),
                average(replays, item -> mrrAt(item, TOP_K)),
                average(replays, item -> ndcgAt(item, TOP_K)),
                average(replays, item -> RagAsMetrics.contextPrecision(
                        item.rankedChunkIds().stream().limit(TOP_K).toList(), item.goldChunkIds()
                )),
                average(replays, item -> RagAsMetrics.contextRecall(
                        item.rankedChunkIds().stream().limit(TOP_K).toList(), item.goldChunkIds()
                )),
                percentile(replays.stream().map(QueryReplay::latencyMs).toList(), 0.5D),
                percentile(replays.stream().map(QueryReplay::latencyMs).toList(), 0.95D)
        );
    }

    private double hitAt(QueryReplay replay, int limit) {
        return replay.rankedChunkIds().stream().limit(limit).anyMatch(replay.goldChunkIds()::contains) ? 1D : 0D;
    }

    private double mrrAt(QueryReplay replay, int limit) {
        for (int index = 0; index < Math.min(limit, replay.rankedChunkIds().size()); index++) {
            if (replay.goldChunkIds().contains(replay.rankedChunkIds().get(index))) {
                return 1D / (index + 1);
            }
        }
        return 0D;
    }

    private double ndcgAt(QueryReplay replay, int limit) {
        double dcg = 0D;
        for (int index = 0; index < Math.min(limit, replay.rankedChunkIds().size()); index++) {
            if (replay.goldChunkIds().contains(replay.rankedChunkIds().get(index))) {
                dcg += 1D / log2(index + 2);
            }
        }
        int idealRelevant = Math.min(limit, replay.goldChunkIds().size());
        double idealDcg = 0D;
        for (int index = 0; index < idealRelevant; index++) {
            idealDcg += 1D / log2(index + 2);
        }
        return idealDcg == 0D ? 0D : dcg / idealDcg;
    }

    private double average(List<QueryReplay> replays, java.util.function.ToDoubleFunction<QueryReplay> metric) {
        return replays.stream().mapToDouble(metric).average().orElse(0D);
    }

    private long percentile(List<Long> values, double percentile) {
        List<Long> sorted = values.stream().sorted().toList();
        return sorted.get((int) Math.ceil(percentile * sorted.size()) - 1);
    }

    private MetricInterval bootstrap(List<Double> differences) {
        double pointEstimate = differences.stream().mapToDouble(Double::doubleValue).average().orElse(0D);
        Random random = new Random(BOOTSTRAP_SEED);
        List<Double> samples = new ArrayList<>(BOOTSTRAP_SAMPLES);
        for (int sample = 0; sample < BOOTSTRAP_SAMPLES; sample++) {
            double total = 0D;
            for (int index = 0; index < differences.size(); index++) {
                total += differences.get(random.nextInt(differences.size()));
            }
            samples.add(total / differences.size());
        }
        Collections.sort(samples);
        return new MetricInterval(
                pointEstimate,
                samples.get((int) Math.floor((BOOTSTRAP_SAMPLES - 1) * 0.025D)),
                samples.get((int) Math.ceil((BOOTSTRAP_SAMPLES - 1) * 0.975D))
        );
    }

    private double log2(int value) {
        return Math.log(value) / Math.log(2D);
    }

    record EvaluationFingerprint(
            String datasetVersion,
            String sourceSha256,
            String candidateManifestSha256,
            String mappingVersion,
            String indexVersion,
            String embeddingModel,
            String bm25DictionaryVersion,
            String rrfConfigSha256,
            int topK,
            int candidateBudget,
            String querySetSha256
    ) {
    }

    record QueryReplay(
            String queryId,
            Set<String> goldChunkIds,
            List<String> rankedChunkIds,
            long latencyMs,
            boolean teiFallback
    ) {
        QueryReplay {
            goldChunkIds = goldChunkIds == null ? null : Set.copyOf(new LinkedHashSet<>(goldChunkIds));
            rankedChunkIds = rankedChunkIds == null ? null : List.copyOf(rankedChunkIds);
        }
    }

    record VariantRun(String variant, EvaluationFingerprint fingerprint, List<QueryReplay> replays) {
        VariantRun {
            replays = replays == null ? null : List.copyOf(replays);
        }
    }

    record Metrics(
            double recallAt1,
            double recallAt3,
            double recallAt5,
            double recallAt10,
            double mrrAt10,
            double ndcgAt10,
            double contextPrecisionAt10,
            double contextRecallAt10,
            long p50LatencyMs,
            long p95LatencyMs
    ) {
    }

    record EvaluationReport(
            String variant,
            EvaluationFingerprint fingerprint,
            String status,
            int validCount,
            int invalidCount,
            List<String> invalidReasons,
            Double teiSuccessRate,
            Metrics metrics
    ) {
    }

    record MetricInterval(double pointEstimate, double lowerBound, double upperBound) {
    }

    record BootstrapSummary(int samples, MetricInterval mrrAt10, MetricInterval ndcgAt10) {
    }

    record Comparison(
            String status,
            EvaluationReport localRule,
            EvaluationReport tei,
            BootstrapSummary bootstrap
    ) {
    }
}
