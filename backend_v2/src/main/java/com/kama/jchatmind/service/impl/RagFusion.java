package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.model.dto.RagRetrievalResult;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import static com.kama.jchatmind.service.impl.RagRetrievalSignals.*;

final class RagFusion {
    static final int RRF_K = 60;

    static List<RagRetrievalResult> rrfFuse(List<RetrievalChannel> channels) {
        return rrfFuse(channels, true);
    }

    static List<RagRetrievalResult> rrfFuseByRank(List<RetrievalChannel> channels) {
        return rrfFuse(channels, false);
    }

    static List<RagRetrievalResult> rrfFuse(
            List<RetrievalChannel> channels,
            boolean usePrimaryDistanceTieBreak
    ) {
        if (channels.isEmpty()) {
            return List.of();
        }

        Map<String, RagRetrievalResult> mergedByChunkId = new LinkedHashMap<>();
        for (RetrievalChannel channel : collapseSameSourceChannels(channels)) {
            List<RagRetrievalResult> results = channel.results();
            for (int i = 0; i < results.size(); i++) {
                RagRetrievalResult incoming = results.get(i);
                if (!StringUtils.hasText(incoming.getChunkId())) {
                    continue;
                }

                int rank = safeRank(incoming.getRank()) == Integer.MAX_VALUE ? i + 1 : incoming.getRank();
                double contribution = 1D / (RRF_K + rank);
                RagRetrievalResult merged = mergedByChunkId.computeIfAbsent(
                        incoming.getChunkId(),
                        key -> copyResult(incoming)
                );
                mergeSignals(merged, incoming);
                merged.setRrfScore((merged.getRrfScore() == null ? 0D : merged.getRrfScore()) + contribution);
            }
        }

        Comparator<RagRetrievalResult> comparator = Comparator
                .comparing((RagRetrievalResult result) -> result.getRrfScore(), Comparator.nullsLast(Double::compareTo))
                .reversed();
        if (usePrimaryDistanceTieBreak) {
            comparator = comparator.thenComparing(RagRetrievalSignals::primaryDistance, Comparator.nullsLast(Double::compareTo));
        }
        comparator = comparator.thenComparing(result -> result.getChunkId(), Comparator.nullsLast(String::compareTo));
        List<RagRetrievalResult> fused = mergedByChunkId.values().stream()
                .sorted(comparator)
                .toList();

        for (int i = 0; i < fused.size(); i++) {
            fused.get(i).setRank(i + 1);
        }
        return fused;
    }

    static List<RetrievalChannel> collapseSameSourceChannels(List<RetrievalChannel> channels) {
        Map<String, LinkedHashMap<String, GroupedRetrieval>> grouped = new LinkedHashMap<>();
        for (RetrievalChannel channel : channels) {
            String group = retrievalGroup(channel.name());
            LinkedHashMap<String, GroupedRetrieval> groupResults = grouped.computeIfAbsent(
                    group,
                    key -> new LinkedHashMap<>()
            );
            List<RagRetrievalResult> results = channel.results();
            for (int i = 0; i < results.size(); i++) {
                RagRetrievalResult incoming = results.get(i);
                if (!StringUtils.hasText(incoming.getChunkId())) {
                    continue;
                }
                int incomingRank = safeRank(incoming.getRank()) == Integer.MAX_VALUE ? i + 1 : incoming.getRank();
                GroupedRetrieval existing = groupResults.get(incoming.getChunkId());
                if (existing == null) {
                    RagRetrievalResult copy = copyResult(incoming);
                    copy.setRank(incomingRank);
                    existing = new GroupedRetrieval(
                            copy,
                            incomingRank,
                            retrievalProvenance(incoming, channel.name())
                    );
                    groupResults.put(incoming.getChunkId(), existing);
                } else {
                    mergeSignals(existing.result(), incoming);
                    if (incomingRank < existing.bestRank()) {
                        existing.result().setRank(incomingRank);
                        existing.setBestRank(incomingRank);
                    }
                    existing.provenance().addAll(retrievalProvenance(incoming, channel.name()));
                }
            }
        }
        return grouped.entrySet().stream()
                .map(entry -> new RetrievalChannel(
                        entry.getKey(),
                        entry.getValue().values().stream()
                                .map(GroupedRetrieval::result)
                                .peek(result -> result.setRetrievalProvenance(
                                        List.copyOf(entry.getValue().get(result.getChunkId()).provenance())
                                ))
                                .toList()
                ))
                .toList();
    }

    static LinkedHashSet<String> retrievalProvenance(RagRetrievalResult result, String fallback) {
        LinkedHashSet<String> provenance = new LinkedHashSet<>();
        if (result.getRetrievalProvenance() != null) {
            provenance.addAll(result.getRetrievalProvenance());
        }
        if (provenance.isEmpty()) {
            provenance.add(fallback);
        }
        return provenance;
    }

    static RetrievalChannel retrievalChannel(
            String branch,
            String channel,
            String querySource,
            List<RagRetrievalResult> candidates,
            boolean vectorChannel
    ) {
        String provenance = branch + ":" + channel + ":" + querySource;
        List<RagRetrievalResult> results = candidates == null
                ? List.of()
                : candidates.stream()
                .filter(candidate -> StringUtils.hasText(candidate.getChunkId()))
                .map(RagRetrievalSignals::copyResult)
                .peek(candidate -> {
                    if (vectorChannel) {
                        candidate.setVectorRank(candidate.getRank());
                        candidate.setVectorDistance(candidate.getDistance());
                    }
                    candidate.setRetrievalProvenance(List.of(provenance));
                })
                .toList();
        return new RetrievalChannel(provenance, results);
    }

    static void addChannel(List<RetrievalChannel> channels, RetrievalChannel channel) {
        if (!channel.results().isEmpty()) {
            channels.add(channel);
        }
    }

    static void addBranch(List<RetrievalChannel> branches, String branch, List<RagRetrievalResult> results) {
        if (!results.isEmpty()) {
            branches.add(new RetrievalChannel(branch, results));
        }
    }

    static List<RagRetrievalResult> fuseBranch(List<RetrievalChannel> channels) {
        return fuseBranch(channels, Integer.MAX_VALUE);
    }

    static List<RagRetrievalResult> fuseBranch(List<RetrievalChannel> channels, int candidateBudget) {
        List<RagRetrievalResult> results = rrfFuseByRank(channels);
        List<RagRetrievalResult> cappedResults = results.stream()
                .limit(candidateBudget)
                .toList();
        for (RagRetrievalResult result : cappedResults) {
            result.setRrfScore(null);
        }
        return cappedResults;
    }

    static String retrievalGroup(String channelName) {
        if (channelName.startsWith("vector_")) {
            return "vector";
        }
        if (channelName.startsWith("title_")) {
            return "title";
        }
        return channelName;
    }

    record RetrievalChannel(
            String name,
            List<RagRetrievalResult> results
    ) {
    }

    private static final class GroupedRetrieval {
        private final RagRetrievalResult result;
        private int bestRank;
        private final LinkedHashSet<String> provenance;

        private GroupedRetrieval(
                RagRetrievalResult result,
                int bestRank,
                LinkedHashSet<String> provenance
        ) {
            this.result = result;
            this.bestRank = bestRank;
            this.provenance = provenance;
        }

        private RagRetrievalResult result() {
            return result;
        }

        private int bestRank() {
            return bestRank;
        }

        private void setBestRank(int bestRank) {
            this.bestRank = bestRank;
        }

        private LinkedHashSet<String> provenance() {
            return provenance;
        }
    }

}
