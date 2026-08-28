package com.kama.jchatmind.rag;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

final class RagIndependentBranchEvaluator {

    private static final int TOP_K = 10;
    private static final int[] RECALL_CUTOFFS = {1, 3, 5, TOP_K};

    EvaluationReport evaluate(VariantRun r0, VariantRun r1, VariantRun r2) {
        requireVariant(r0, "R0");
        requireVariant(r1, "R1");
        requireVariant(r2, "R2");
        if (!comparable(r0.fingerprint(), r1.fingerprint()) || !comparable(r0.fingerprint(), r2.fingerprint())
                || !sameFrozenCases(r0.queryReplays(), r1.queryReplays())
                || !sameFrozenCases(r0.queryReplays(), r2.queryReplays())) {
            throw new IllegalStateException("R0/R1/R2 冻结输入不一致");
        }
        List<VariantSummary> variants = List.of(
                summary(r0),
                summary(r1),
                summary(r2)
        );
        return new EvaluationReport(variants, gate(variants.get(0), variants.get(2)));
    }

    private Gate gate(VariantSummary r0, VariantSummary r2) {
        List<String> reasons = new ArrayList<>();
        if (!qualityAtLeast(r2, r0)) {
            reasons.add("r2_quality_below_r0");
        }
        if (!p95WithinLimit(r0.p95LatencyMs(), r2.p95LatencyMs())) {
            reasons.add("r2_p95_latency_above_15_percent");
        }
        if (r2.abstentionViolations() > 0) {
            reasons.add("abstention_violations");
        }
        if (r2.permissionViolations() > 0) {
            reasons.add("permission_violations");
        }
        return new Gate(
                reasons.isEmpty() ? "eligible_for_rerank_ab" : "inconclusive",
                reasons
        );
    }

    private boolean qualityAtLeast(VariantSummary candidate, VariantSummary baseline) {
        return candidate.recallAt1() >= baseline.recallAt1()
                && candidate.recallAt3() >= baseline.recallAt3()
                && candidate.recallAt5() >= baseline.recallAt5()
                && candidate.recallAt10() >= baseline.recallAt10()
                && candidate.mrrAt10() >= baseline.mrrAt10()
                && candidate.ndcgAt10() >= baseline.ndcgAt10();
    }

    private boolean p95WithinLimit(long baselineP95LatencyMs, long candidateP95LatencyMs) {
        return BigInteger.valueOf(candidateP95LatencyMs).multiply(BigInteger.valueOf(100L))
                .compareTo(BigInteger.valueOf(baselineP95LatencyMs).multiply(BigInteger.valueOf(115L))) <= 0;
    }

    private void requireVariant(VariantRun run, String expectedVariant) {
        if (run == null || !expectedVariant.equals(run.variant()) || !validFingerprint(run.fingerprint()) || run.queryReplays() == null) {
            throw new IllegalArgumentException("独立三路评测臂无效: " + expectedVariant);
        }
    }

    private boolean validFingerprint(EvaluationFingerprint fingerprint) {
        return fingerprint != null
                && nonBlank(fingerprint.datasetId())
                && sha256(fingerprint.goldSha256())
                && sha256(fingerprint.scopeSha256())
                && sha256(fingerprint.queryReplaySha256())
                && sha256(fingerprint.branchConfigSha256())
                && fingerprint.topK() >= TOP_K
                && fingerprint.candidateBudget() > 0
                && sha256(fingerprint.effectiveQuerySetSha256());
    }

    private boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private boolean sha256(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private boolean comparable(EvaluationFingerprint left, EvaluationFingerprint right) {
        return Objects.equals(left.datasetId(), right.datasetId())
                && Objects.equals(left.goldSha256(), right.goldSha256())
                && Objects.equals(left.scopeSha256(), right.scopeSha256())
                && Objects.equals(left.queryReplaySha256(), right.queryReplaySha256())
                && left.topK() == right.topK()
                && left.candidateBudget() == right.candidateBudget()
                && Objects.equals(left.effectiveQuerySetSha256(), right.effectiveQuerySetSha256());
    }

    private boolean sameFrozenCases(List<QueryReplay> left, List<QueryReplay> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            QueryReplay leftReplay = left.get(index);
            QueryReplay rightReplay = right.get(index);
            if (leftReplay == null || rightReplay == null
                    || !Objects.equals(leftReplay.caseId(), rightReplay.caseId())
                    || !Objects.equals(leftReplay.goldChunkIds(), rightReplay.goldChunkIds())
                    || leftReplay.shouldAbstain() != rightReplay.shouldAbstain()) {
                return false;
            }
        }
        return true;
    }

    private VariantSummary summary(VariantRun run) {
        int answerableCount = 0;
        int abstentionViolations = 0;
        int permissionViolations = 0;
        double reciprocalRankSum = 0D;
        double ndcgSum = 0D;
        int[] recallHits = new int[4];
        List<Long> latencies = new ArrayList<>();
        for (QueryReplay replay : run.queryReplays()) {
            if (replay == null || replay.goldChunkIds() == null || replay.rankedChunkIds() == null
                    || replay.branchDiagnostics() == null || replay.latencyMs() < 0L
                    || hasDuplicatedChunkIds(replay.rankedChunkIds())) {
                throw new IllegalStateException("独立三路 query replay 无效");
            }
            latencies.add(replay.latencyMs());
            if (replay.permissionViolation()) {
                permissionViolations++;
            }
            if (replay.shouldAbstain()) {
                if (!replay.rankedChunkIds().isEmpty()) {
                    abstentionViolations++;
                }
                continue;
            }
            if (replay.goldChunkIds().isEmpty()) {
                throw new IllegalStateException("可回答 query 缺少 gold chunk");
            }
            answerableCount++;
            int firstGoldRank = firstGoldRank(replay.rankedChunkIds(), replay.goldChunkIds());
            if (firstGoldRank > 0) {
                reciprocalRankSum += 1D / firstGoldRank;
            }
            ndcgSum += ndcgAtTen(replay.rankedChunkIds(), replay.goldChunkIds());
            for (int index = 0; index < recallHits.length; index++) {
                if (firstGoldRank > 0 && firstGoldRank <= RECALL_CUTOFFS[index]) {
                    recallHits[index]++;
                }
            }
        }
        if (answerableCount == 0) {
            throw new IllegalStateException("独立三路评测没有可回答 query");
        }
        return new VariantSummary(
                run.variant(),
                answerableCount,
                recallHits[0] / (double) answerableCount,
                recallHits[1] / (double) answerableCount,
                recallHits[2] / (double) answerableCount,
                recallHits[3] / (double) answerableCount,
                reciprocalRankSum / answerableCount,
                ndcgSum / answerableCount,
                percentile(latencies, 0.5D),
                percentile(latencies, 0.95D),
                abstentionViolations,
                permissionViolations
        );
    }

    private boolean hasDuplicatedChunkIds(List<String> chunkIds) {
        return new LinkedHashSet<>(chunkIds).size() != chunkIds.size();
    }

    private int firstGoldRank(List<String> rankedChunkIds, Set<String> goldChunkIds) {
        for (int index = 0; index < Math.min(TOP_K, rankedChunkIds.size()); index++) {
            if (goldChunkIds.contains(rankedChunkIds.get(index))) {
                return index + 1;
            }
        }
        return -1;
    }

    private double ndcgAtTen(List<String> rankedChunkIds, Set<String> goldChunkIds) {
        double dcg = 0D;
        for (int index = 0; index < Math.min(TOP_K, rankedChunkIds.size()); index++) {
            if (goldChunkIds.contains(rankedChunkIds.get(index))) {
                dcg += 1D / log2(index + 2D);
            }
        }
        double idealDcg = 0D;
        for (int index = 0; index < Math.min(TOP_K, goldChunkIds.size()); index++) {
            idealDcg += 1D / log2(index + 2D);
        }
        return idealDcg == 0D ? 0D : dcg / idealDcg;
    }

    private double log2(double value) {
        return Math.log(value) / Math.log(2D);
    }

    private long percentile(List<Long> values, double quantile) {
        List<Long> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        int index = Math.max(0, (int) Math.ceil(quantile * sorted.size()) - 1);
        return sorted.get(index);
    }

    record EvaluationFingerprint(
            String datasetId,
            String goldSha256,
            String scopeSha256,
            String queryReplaySha256,
            String branchConfigSha256,
            int topK,
            int candidateBudget,
            String effectiveQuerySetSha256
    ) {
    }

    record VariantRun(String variant, EvaluationFingerprint fingerprint, List<QueryReplay> queryReplays) {
        VariantRun {
            queryReplays = queryReplays == null ? null : List.copyOf(queryReplays);
        }
    }

    record QueryReplay(
            String caseId,
            Set<String> goldChunkIds,
            List<String> rankedChunkIds,
            long latencyMs,
            List<BranchDiagnostic> branchDiagnostics,
            boolean shouldAbstain,
            boolean permissionViolation
    ) {
        QueryReplay {
            goldChunkIds = goldChunkIds == null ? null : Set.copyOf(new LinkedHashSet<>(goldChunkIds));
            rankedChunkIds = rankedChunkIds == null ? null : List.copyOf(rankedChunkIds);
            branchDiagnostics = branchDiagnostics == null ? null : List.copyOf(branchDiagnostics);
        }
    }

    record BranchDiagnostic(
            String branch,
            int candidateCount,
            int deduplicatedCandidateCount,
            Set<String> goldChunkIds,
            int outerRrfRank
    ) {
        BranchDiagnostic {
            goldChunkIds = goldChunkIds == null ? null : Set.copyOf(new LinkedHashSet<>(goldChunkIds));
        }
    }

    record EvaluationReport(List<VariantSummary> variants, Gate gate) {
        EvaluationReport {
            variants = List.copyOf(variants);
        }
    }

    record Gate(String verdict, List<String> reasons) {
        Gate {
            reasons = List.copyOf(reasons);
        }
    }

    record VariantSummary(
            String variant,
            int answerableCount,
            double recallAt1,
            double recallAt3,
            double recallAt5,
            double recallAt10,
            double mrrAt10,
            double ndcgAt10,
            long p50LatencyMs,
            long p95LatencyMs,
            int abstentionViolations,
            int permissionViolations
    ) {
    }
}
