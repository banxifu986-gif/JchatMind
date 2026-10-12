package com.kama.jchatmind.rag;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class G2CurrentFixtureRuntimeAblationEvaluator {

    private static final int TOP_K = 10;
    private static final String EXPECTED_ROUTE = "PRIVATE_RAG";

    RuntimeReport evaluate(G2CurrentFixtureProfile profile, List<CaseReplay> replays) {
        Map<String, G2CurrentFixtureCase> casesById = indexCases(profile);
        if (replays == null || replays.size() != casesById.size()) {
            throw new IllegalStateException("current fixture runtime replay 必须精确覆盖冻结 case");
        }

        Set<String> replayCaseIds = new HashSet<>();
        for (CaseReplay replay : replays) {
            if (replay == null || !replayCaseIds.add(replay.caseId()) || !casesById.containsKey(replay.caseId())) {
                throw new IllegalStateException("current fixture runtime replay caseId 无效或重复");
            }
            validateArm(replay.fixed());
            validateArm(replay.router());
        }

        VariantSummary fixed = summarize("fixed", casesById, replays, false);
        VariantSummary router = summarize("router", casesById, replays, true);
        return new RuntimeReport(
                profile.datasetId(),
                casesById.size(),
                (int) casesById.values().stream().filter(item -> !item.shouldAbstain()).count(),
                (int) casesById.values().stream().filter(G2CurrentFixtureCase::shouldAbstain).count(),
                List.copyOf(replays),
                fixed,
                router,
                gate(fixed, router),
                false,
                false
        );
    }

    private Map<String, G2CurrentFixtureCase> indexCases(G2CurrentFixtureProfile profile) {
        if (profile == null || !"fixture-fast-v1".equals(profile.datasetId())
                || profile.cases() == null || profile.caseCount() != profile.cases().size()) {
            throw new IllegalArgumentException("current fixture runtime profile 无效");
        }
        Map<String, G2CurrentFixtureCase> casesById = new HashMap<>();
        for (G2CurrentFixtureCase item : profile.cases()) {
            if (item == null || item.caseId() == null || item.caseId().isBlank()
                    || item.goldChunkIds() == null || item.expectedRoute() == null
                    || !EXPECTED_ROUTE.equals(item.expectedRoute())
                    || casesById.put(item.caseId(), item) != null) {
                throw new IllegalArgumentException("current fixture runtime profile case 无效或重复");
            }
        }
        return casesById;
    }

    private void validateArm(RuntimeArm arm) {
        if (arm == null || arm.route() == null || arm.route().isBlank()
                || arm.selectedTopK() < 0 || arm.rankedChunkIds() == null
                || arm.rankedChunkIds().size() > arm.selectedTopK()
                || arm.latencyMs() <= 0L || arm.contextCharacters() < 0
                || new HashSet<>(arm.rankedChunkIds()).size() != arm.rankedChunkIds().size()
                || arm.rankedChunkIds().stream().anyMatch(item -> item == null || item.isBlank())) {
            throw new IllegalStateException("current fixture runtime arm 无效");
        }
    }

    private VariantSummary summarize(
            String variant,
            Map<String, G2CurrentFixtureCase> casesById,
            List<CaseReplay> replays,
            boolean router
    ) {
        int answerableCases = 0;
        int recallHitsAt5 = 0;
        int evidenceCoveredAt10 = 0;
        int routeViolations = 0;
        int permissionViolations = 0;
        int externalCalls = 0;
        long contextCharacters = 0L;
        double reciprocalRankSum = 0D;
        List<Long> latencies = new ArrayList<>(replays.size());
        for (CaseReplay replay : replays) {
            G2CurrentFixtureCase evaluationCase = casesById.get(replay.caseId());
            RuntimeArm arm = router ? replay.router() : replay.fixed();
            latencies.add(arm.latencyMs());
            contextCharacters += arm.contextCharacters();
            if (arm.permissionViolation()) {
                permissionViolations++;
            }
            if (arm.externalCall()) {
                externalCalls++;
            }
            if (router && !evaluationCase.expectedRoute().equals(arm.route())) {
                routeViolations++;
            }
            if (evaluationCase.shouldAbstain()) {
                continue;
            }
            answerableCases++;
            int firstGoldRank = firstGoldRank(arm.rankedChunkIds(), evaluationCase.goldChunkIds());
            if (firstGoldRank > 0) {
                reciprocalRankSum += 1D / firstGoldRank;
                if (firstGoldRank <= 5) {
                    recallHitsAt5++;
                }
            }
            if (arm.rankedChunkIds().stream().limit(TOP_K).collect(java.util.stream.Collectors.toSet())
                    .containsAll(evaluationCase.goldChunkIds())) {
                evidenceCoveredAt10++;
            }
        }
        if (answerableCases == 0) {
            throw new IllegalStateException("current fixture runtime evaluation 没有可回答 case");
        }
        return new VariantSummary(
                variant,
                replays.size(),
                answerableCases,
                recallHitsAt5 / (double) answerableCases,
                reciprocalRankSum / answerableCases,
                evidenceCoveredAt10 / (double) answerableCases,
                percentile(latencies, 0.95D),
                contextCharacters / (double) replays.size(),
                routeViolations,
                permissionViolations,
                externalCalls
        );
    }

    private Gate gate(VariantSummary fixed, VariantSummary router) {
        List<String> reasons = new ArrayList<>();
        if (router.recallAt5() < fixed.recallAt5()
                || router.mrrAt10() < fixed.mrrAt10()
                || router.goldEvidenceCoverageAt10() < fixed.goldEvidenceCoverageAt10()) {
            reasons.add("router_retrieval_quality_below_fixed_chain");
        }
        if (!p95WithinLimit(fixed.p95LatencyMs(), router.p95LatencyMs())) {
            reasons.add("router_p95_latency_above_15_percent");
        }
        if (router.averageContextCharacters() > fixed.averageContextCharacters() * 1.10D) {
            reasons.add("router_context_cost_above_10_percent");
        }
        if (router.routeViolations() > 0) {
            reasons.add("router_route_mismatch");
        }
        if (fixed.permissionViolations() > 0 || router.permissionViolations() > 0) {
            reasons.add("router_permission_violations");
        }
        if (fixed.externalCalls() > 0) {
            reasons.add("fixed_chain_external_calls");
        }
        if (router.externalCalls() > 0) {
            reasons.add("router_external_calls");
        }
        return new Gate(reasons.isEmpty() ? "eligible_for_router_runtime_ab" : "inconclusive", reasons);
    }

    private int firstGoldRank(List<String> rankedChunkIds, List<String> goldChunkIds) {
        for (int index = 0; index < Math.min(TOP_K, rankedChunkIds.size()); index++) {
            if (goldChunkIds.contains(rankedChunkIds.get(index))) {
                return index + 1;
            }
        }
        return -1;
    }

    private boolean p95WithinLimit(long fixedP95LatencyMs, long routerP95LatencyMs) {
        return BigInteger.valueOf(routerP95LatencyMs).multiply(BigInteger.valueOf(100L))
                .compareTo(BigInteger.valueOf(fixedP95LatencyMs).multiply(BigInteger.valueOf(115L))) <= 0;
    }

    private long percentile(List<Long> values, double quantile) {
        List<Long> sorted = values.stream().sorted(Comparator.naturalOrder()).toList();
        int index = Math.max(0, (int) Math.ceil(quantile * sorted.size()) - 1);
        return sorted.get(index);
    }

    record RuntimeArm(
            String route,
            int selectedTopK,
            List<String> rankedChunkIds,
            long latencyMs,
            int contextCharacters,
            boolean permissionViolation,
            boolean externalCall
    ) {
        RuntimeArm {
            rankedChunkIds = rankedChunkIds == null ? null : List.copyOf(rankedChunkIds);
        }
    }

    record CaseReplay(String caseId, RuntimeArm fixed, RuntimeArm router) {
    }

    record VariantSummary(
            String variant,
            int caseCount,
            int answerableCaseCount,
            double recallAt5,
            double mrrAt10,
            double goldEvidenceCoverageAt10,
            long p95LatencyMs,
            double averageContextCharacters,
            int routeViolations,
            int permissionViolations,
            int externalCalls
    ) {
    }

    record Gate(String verdict, List<String> reasons) {
        Gate {
            reasons = List.copyOf(reasons);
        }
    }

    record RuntimeReport(
            String datasetId,
            int caseCount,
            int answerableCaseCount,
            int noAnswerCaseCount,
            List<CaseReplay> caseReplays,
            VariantSummary fixed,
            VariantSummary router,
            Gate gate,
            boolean answerQualityMeasured,
            boolean answerCitationAccuracyMeasured
    ) {
        RuntimeReport {
            caseReplays = List.copyOf(caseReplays);
        }
    }
}
