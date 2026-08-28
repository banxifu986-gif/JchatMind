package com.kama.jchatmind.rag;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class MmarcoZhSampledRuntimeReplayRunner {

    List<MmarcoZhSampledReplayCollector.RuntimeQueryResult> run(
            List<MmarcoZhSampledDatasetFreezer.Query> queries,
            QueryRetriever queryRetriever
    ) {
        if (queries == null || queries.isEmpty() || queryRetriever == null) {
            throw new IllegalArgumentException("mMARCO runtime replay 执行输入不完整");
        }

        Set<String> queryIds = new LinkedHashSet<>();
        List<MmarcoZhSampledReplayCollector.RuntimeQueryResult> results = new ArrayList<>(queries.size());
        for (MmarcoZhSampledDatasetFreezer.Query query : queries) {
            if (query == null || query.id() == null || query.id().isBlank()
                    || query.text() == null || query.text().isBlank() || !queryIds.add(query.id())) {
                throw new IllegalStateException("mMARCO frozen query 无效或重复");
            }
            long startedAt = System.nanoTime();
            RetrievalOutcome outcome = queryRetriever.retrieve(query);
            long latencyMs = Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
            validateOutcome(query.id(), outcome);
            results.add(new MmarcoZhSampledReplayCollector.RuntimeQueryResult(
                    query.id(), outcome.runtimeChunkUuids(), latencyMs, outcome.teiFallback()
            ));
        }
        return List.copyOf(results);
    }

    private void validateOutcome(String queryId, RetrievalOutcome outcome) {
        if (outcome == null || outcome.runtimeChunkUuids() == null) {
            throw new IllegalStateException("mMARCO runtime retrieval 结果为空: " + queryId);
        }
        Set<String> uniqueChunkUuids = new LinkedHashSet<>();
        for (String runtimeChunkUuid : outcome.runtimeChunkUuids()) {
            if (runtimeChunkUuid == null || runtimeChunkUuid.isBlank() || !uniqueChunkUuids.add(runtimeChunkUuid)) {
                throw new IllegalStateException("mMARCO runtime retrieval UUID 无效或重复: " + queryId);
            }
        }
    }

    @FunctionalInterface
    interface QueryRetriever {
        RetrievalOutcome retrieve(MmarcoZhSampledDatasetFreezer.Query query);
    }

    record RetrievalOutcome(List<String> runtimeChunkUuids, boolean teiFallback) {
        RetrievalOutcome {
            runtimeChunkUuids = runtimeChunkUuids == null ? null : List.copyOf(runtimeChunkUuids);
        }
    }
}
