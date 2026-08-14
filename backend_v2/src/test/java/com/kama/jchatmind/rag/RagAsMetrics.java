package com.kama.jchatmind.rag;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class RagAsMetrics {

    private RagAsMetrics() {
    }

    public static double contextPrecision(List<String> rankedChunkIds, Set<String> goldChunkIds) {
        if (rankedChunkIds == null || rankedChunkIds.isEmpty() || goldChunkIds == null || goldChunkIds.isEmpty()) {
            return 0D;
        }
        int relevant = 0;
        double precisionSum = 0D;
        for (int i = 0; i < rankedChunkIds.size(); i++) {
            if (goldChunkIds.contains(rankedChunkIds.get(i))) {
                relevant++;
                precisionSum += (double) relevant / (i + 1);
            }
        }
        return relevant == 0 ? 0D : precisionSum / relevant;
    }

    public static double contextRecall(List<String> rankedChunkIds, Set<String> goldChunkIds) {
        if (rankedChunkIds == null || rankedChunkIds.isEmpty() || goldChunkIds == null || goldChunkIds.isEmpty()) {
            return 0D;
        }
        Set<String> covered = new HashSet<>(rankedChunkIds);
        covered.retainAll(goldChunkIds);
        return (double) covered.size() / goldChunkIds.size();
    }

    public static double clampScore(double score) {
        return Math.max(0D, Math.min(1D, score));
    }
}
