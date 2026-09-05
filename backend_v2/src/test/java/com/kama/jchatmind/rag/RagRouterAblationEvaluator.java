package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RagRouterAblationEvaluator {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    RagRouterAblationEvaluationReport evaluate(
            RagRouterAblationDataset dataset,
            List<RagRouterAblationReplay> replays
    ) {
        Map<String, RagRouterAblationCase> casesById = indexCases(dataset);
        if (replays == null || replays.size() != casesById.size()) {
            throw new IllegalStateException("Router 消融 replay 必须精确覆盖冻结 case");
        }
        Set<String> replayCaseIds = new HashSet<>();
        for (RagRouterAblationReplay replay : replays) {
            if (replay == null || !replayCaseIds.add(replay.caseId()) || !casesById.containsKey(replay.caseId())) {
                throw new IllegalStateException("Router 消融 replay caseId 无效或重复");
            }
            validateReplay(replay, casesById.get(replay.caseId()));
        }

        RagRouterAblationEvaluationReport.VariantSummary fixed = summarize(
                "fixed", casesById, replays, false
        );
        RagRouterAblationEvaluationReport.VariantSummary router = summarize(
                "router", casesById, replays, true
        );
        return new RagRouterAblationEvaluationReport(
                dataset.datasetVersion(), fixed, router, gate(fixed, router)
        );
    }

    RagRouterAblationEvaluationReport evaluateAndWrite(
            RagRouterAblationDataset dataset,
            List<RagRouterAblationReplay> replays,
            Path reportPath
    ) throws IOException {
        RagRouterAblationEvaluationReport report = evaluate(dataset, replays);
        Files.createDirectories(reportPath.getParent());
        Files.writeString(reportPath, OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        return report;
    }

    private Map<String, RagRouterAblationCase> indexCases(RagRouterAblationDataset dataset) {
        if (dataset == null || dataset.cases() == null || dataset.datasetVersion() == null
                || dataset.datasetVersion().isBlank()) {
            throw new IllegalStateException("Router 消融数据集版本或 case 不完整");
        }
        Map<String, RagRouterAblationCase> casesById = new HashMap<>();
        for (RagRouterAblationCase item : dataset.cases()) {
            if (item == null || item.caseId() == null || item.caseId().isBlank()
                    || item.goldChunkIds() == null || item.expectedRoute() == null || item.expectedRoute().isBlank()
                    || casesById.put(item.caseId(), item) != null) {
                throw new IllegalStateException("Router 消融冻结 case 不完整或重复");
            }
        }
        return casesById;
    }

    private void validateReplay(RagRouterAblationReplay replay, RagRouterAblationCase evaluationCase) {
        if (replay.fixedRankedChunkIds() == null || replay.routerRankedChunkIds() == null
                || replay.fixedLatencyMs() < 0L || replay.routerLatencyMs() < 0L
                || replay.fixedInputTokens() < 0 || replay.routerInputTokens() < 0
                || hasDuplicates(replay.fixedRankedChunkIds()) || hasDuplicates(replay.routerRankedChunkIds())
                || replay.routerRoute() == null || replay.routerRoute().isBlank()) {
            throw new IllegalStateException("Router 消融 replay 无效");
        }
        if (!evaluationCase.externalAllowed() && replay.routerExternalCall()) {
            throw new IllegalStateException("未获许可的外部调用不得进入 Router 消融 replay");
        }
    }

    private RagRouterAblationEvaluationReport.VariantSummary summarize(
            String variant,
            Map<String, RagRouterAblationCase> casesById,
            List<RagRouterAblationReplay> replays,
            boolean router
    ) {
        int answerableCases = 0;
        int recallHits = 0;
        double reciprocalRankSum = 0D;
        int citationCorrect = 0;
        int abstentionCorrect = 0;
        int permissionViolations = 0;
        int unauthorizedExternalCalls = 0;
        int routeViolations = 0;
        List<Long> latencies = new ArrayList<>();
        long inputTokenTotal = 0L;
        for (RagRouterAblationReplay replay : replays) {
            RagRouterAblationCase evaluationCase = casesById.get(replay.caseId());
            List<String> rankedChunkIds = router ? replay.routerRankedChunkIds() : replay.fixedRankedChunkIds();
            boolean abstained = router ? replay.routerAbstained() : replay.fixedAbstained();
            boolean citationIsCorrect = router ? replay.routerCitationCorrect() : replay.fixedCitationCorrect();
            long latencyMs = router ? replay.routerLatencyMs() : replay.fixedLatencyMs();
            int inputTokens = router ? replay.routerInputTokens() : replay.fixedInputTokens();
            latencies.add(latencyMs);
            inputTokenTotal += inputTokens;
            if (citationIsCorrect) {
                citationCorrect++;
            }
            if (abstained == evaluationCase.shouldAbstain()) {
                abstentionCorrect++;
            }
            if (router) {
                if (replay.routerPermissionViolation()) {
                    permissionViolations++;
                }
                if (replay.routerExternalCall() && !evaluationCase.externalAllowed()) {
                    unauthorizedExternalCalls++;
                }
                if (!evaluationCase.expectedRoute().equals(replay.routerRoute())) {
                    routeViolations++;
                }
            } else if (replay.fixedPermissionViolation()) {
                permissionViolations++;
            }
            if (!evaluationCase.shouldAbstain()) {
                answerableCases++;
                int firstGoldRank = firstGoldRank(rankedChunkIds, evaluationCase.goldChunkIds());
                if (firstGoldRank > 0) {
                    reciprocalRankSum += 1D / firstGoldRank;
                    if (firstGoldRank <= 5) {
                        recallHits++;
                    }
                }
            }
        }
        return new RagRouterAblationEvaluationReport.VariantSummary(
                variant,
                replays.size(),
                answerableCases,
                answerableCases == 0 ? 0D : recallHits / (double) answerableCases,
                answerableCases == 0 ? 0D : reciprocalRankSum / answerableCases,
                citationCorrect / (double) replays.size(),
                abstentionCorrect / (double) replays.size(),
                percentile(latencies, 0.95D),
                inputTokenTotal / (double) replays.size(),
                permissionViolations,
                unauthorizedExternalCalls,
                routeViolations
        );
    }

    private RagRouterAblationEvaluationReport.Gate gate(
            RagRouterAblationEvaluationReport.VariantSummary fixed,
            RagRouterAblationEvaluationReport.VariantSummary router
    ) {
        List<String> reasons = new ArrayList<>();
        if (router.recallAt5() < fixed.recallAt5() || router.mrrAt10() < fixed.mrrAt10()
                || router.citationAccuracy() < fixed.citationAccuracy()
                || router.abstentionAccuracy() < fixed.abstentionAccuracy()) {
            reasons.add("router_quality_below_fixed_chain");
        }
        if (!p95WithinLimit(fixed.p95LatencyMs(), router.p95LatencyMs())) {
            reasons.add("router_p95_latency_above_15_percent");
        }
        if (router.averageInputTokens() > fixed.averageInputTokens() * 1.10D) {
            reasons.add("router_token_cost_above_10_percent");
        }
        if (router.permissionViolations() > 0) {
            reasons.add("router_permission_violations");
        }
        if (router.unauthorizedExternalCalls() > 0) {
            reasons.add("router_unauthorized_external_calls");
        }
        if (router.routeViolations() > 0) {
            reasons.add("router_route_mismatch");
        }
        return new RagRouterAblationEvaluationReport.Gate(
                reasons.isEmpty() ? "eligible_for_router_ab" : "inconclusive", reasons
        );
    }

    private boolean p95WithinLimit(long fixedP95LatencyMs, long routerP95LatencyMs) {
        return BigInteger.valueOf(routerP95LatencyMs).multiply(BigInteger.valueOf(100L))
                .compareTo(BigInteger.valueOf(fixedP95LatencyMs).multiply(BigInteger.valueOf(115L))) <= 0;
    }

    private int firstGoldRank(List<String> rankedChunkIds, Set<String> goldChunkIds) {
        for (int index = 0; index < Math.min(10, rankedChunkIds.size()); index++) {
            if (goldChunkIds.contains(rankedChunkIds.get(index))) {
                return index + 1;
            }
        }
        return -1;
    }

    private long percentile(List<Long> values, double quantile) {
        List<Long> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        int index = Math.max(0, (int) Math.ceil(quantile * sorted.size()) - 1);
        return sorted.get(index);
    }

    private boolean hasDuplicates(List<String> values) {
        return new HashSet<>(values).size() != values.size();
    }
}
