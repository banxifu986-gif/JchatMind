package com.kama.jchatmind.rag;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class MmarcoZhSampledReplayCollector {

    MmarcoZhSampledEvaluator.VariantRun collect(
            String variant,
            MmarcoZhSampledEvaluator.EvaluationFingerprint fingerprint,
            List<MmarcoZhSampledDatasetFreezer.Query> evaluationQueries,
            Map<String, List<String>> goldLogicalChunkIdsByQueryId,
            Map<String, String> logicalChunkIdByRuntimeUuid,
            List<RuntimeQueryResult> runtimeResults
    ) {
        if (variant == null || variant.isBlank() || fingerprint == null || evaluationQueries == null
                || evaluationQueries.isEmpty() || goldLogicalChunkIdsByQueryId == null
                || logicalChunkIdByRuntimeUuid == null || logicalChunkIdByRuntimeUuid.isEmpty()
                || runtimeResults == null) {
            throw new IllegalArgumentException("mMARCO runtime replay 输入不完整");
        }
        Map<String, RuntimeQueryResult> runtimeByQueryId = indexRuntimeResults(runtimeResults);
        Set<String> expectedQueryIds = new LinkedHashSet<>();
        for (MmarcoZhSampledDatasetFreezer.Query query : evaluationQueries) {
            if (query == null || query.id() == null || query.id().isBlank()
                    || !expectedQueryIds.add(query.id()) || !goldLogicalChunkIdsByQueryId.containsKey(query.id())) {
                throw new IllegalStateException("mMARCO 冻结 query 或 gold 无效");
            }
        }
        if (!expectedQueryIds.equals(runtimeByQueryId.keySet())) {
            throw new IllegalStateException("mMARCO runtime replay query 集不一致");
        }
        if (logicalChunkIdByRuntimeUuid.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                || entry.getKey().isBlank() || entry.getValue() == null || entry.getValue().isBlank())) {
            throw new IllegalStateException("mMARCO runtime UUID mapping 无效");
        }

        List<MmarcoZhSampledEvaluator.QueryReplay> replays = evaluationQueries.stream()
                .map(query -> toReplay(
                        query.id(),
                        goldLogicalChunkIdsByQueryId.get(query.id()),
                        runtimeByQueryId.get(query.id()),
                        logicalChunkIdByRuntimeUuid
                ))
                .toList();
        return new MmarcoZhSampledEvaluator.VariantRun(variant, fingerprint, replays);
    }

    private Map<String, RuntimeQueryResult> indexRuntimeResults(List<RuntimeQueryResult> runtimeResults) {
        Map<String, RuntimeQueryResult> indexed = new LinkedHashMap<>();
        for (RuntimeQueryResult result : runtimeResults) {
            if (result == null || result.queryId() == null || result.queryId().isBlank()
                    || result.runtimeChunkUuids() == null || result.latencyMs() < 0
                    || indexed.putIfAbsent(result.queryId(), result) != null) {
                throw new IllegalStateException("mMARCO runtime replay query 无效或重复");
            }
        }
        return Map.copyOf(indexed);
    }

    private MmarcoZhSampledEvaluator.QueryReplay toReplay(
            String queryId,
            List<String> goldLogicalChunkIds,
            RuntimeQueryResult runtimeResult,
            Map<String, String> logicalChunkIdByRuntimeUuid
    ) {
        if (goldLogicalChunkIds == null || goldLogicalChunkIds.isEmpty()) {
            throw new IllegalStateException("mMARCO runtime replay 缺少 gold: " + queryId);
        }
        Set<String> seenRuntimeUuids = new LinkedHashSet<>();
        List<String> rankedLogicalChunkIds = runtimeResult.runtimeChunkUuids().stream()
                .map(runtimeUuid -> {
                    if (runtimeUuid == null || !seenRuntimeUuids.add(runtimeUuid)) {
                        throw new IllegalStateException("mMARCO runtime replay 存在重复 UUID: " + queryId);
                    }
                    String logicalChunkId = logicalChunkIdByRuntimeUuid.get(runtimeUuid);
                    if (logicalChunkId == null) {
                        throw new IllegalStateException("mMARCO runtime replay 包含未知 UUID: " + runtimeUuid);
                    }
                    return logicalChunkId;
                })
                .toList();
        return new MmarcoZhSampledEvaluator.QueryReplay(
                queryId,
                Set.copyOf(goldLogicalChunkIds),
                rankedLogicalChunkIds,
                runtimeResult.latencyMs(),
                runtimeResult.teiFallback()
        );
    }

    record RuntimeQueryResult(
            String queryId,
            List<String> runtimeChunkUuids,
            long latencyMs,
            boolean teiFallback
    ) {
        RuntimeQueryResult {
            runtimeChunkUuids = runtimeChunkUuids == null ? null : List.copyOf(runtimeChunkUuids);
        }
    }
}
