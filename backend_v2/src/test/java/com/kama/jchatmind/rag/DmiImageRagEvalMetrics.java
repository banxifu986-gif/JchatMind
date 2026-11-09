package com.kama.jchatmind.rag;

import java.util.List;
import java.util.Locale;

/**
 * dmi-image-rag-v1 指标计算（§7.2）：OCR CER、实体 P/R/F1、检索 hit@k 与 MRR。
 */
final class DmiImageRagEvalMetrics {

    private DmiImageRagEvalMetrics() {
    }

    /** 字符错误率：editDistance(reference, hypothesis) / reference 长度；reference 为空时返回 0。 */
    static double cer(String reference, String hypothesis) {
        String ref = normalize(reference);
        String hyp = normalize(hypothesis);
        if (ref.isEmpty()) {
            return 0D;
        }
        return (double) editDistance(ref, hyp) / ref.length();
    }

    /** 集合精确率；gold 为空时返回 0。 */
    static double precision(java.util.Set<String> gold, java.util.Set<String> actual) {
        if (gold == null || gold.isEmpty() || actual == null || actual.isEmpty()) {
            return 0D;
        }
        long hits = actual.stream().filter(gold::contains).count();
        return (double) hits / actual.size();
    }

    /** 集合召回率；gold 为空时返回 0。 */
    static double recall(java.util.Set<String> gold, java.util.Set<String> actual) {
        if (gold == null || gold.isEmpty() || actual == null || actual.isEmpty()) {
            return 0D;
        }
        long hits = actual.stream().filter(gold::contains).count();
        return (double) hits / gold.size();
    }

    static double f1(double precision, double recall) {
        if (precision + recall == 0D) {
            return 0D;
        }
        return 2D * precision * recall / (precision + recall);
    }

    static double entityF1(java.util.Set<String> gold, java.util.Set<String> actual) {
        return f1(precision(gold, actual), recall(gold, actual));
    }

    /** 命中率：rank 列表中 <=k 的比例；无 case 时返回 0。 */
    static double hitAtK(List<Integer> ranks, int k) {
        if (ranks == null || ranks.isEmpty()) {
            return 0D;
        }
        return (double) ranks.stream().filter(rank -> rank != null && rank > 0 && rank <= k).count() / ranks.size();
    }

    /** MRR：1/rank 的平均值；未命中记 0。 */
    static double mrr(List<Integer> ranks) {
        if (ranks == null || ranks.isEmpty()) {
            return 0D;
        }
        return ranks.stream()
                .mapToDouble(rank -> rank == null || rank <= 0 ? 0D : 1D / rank)
                .average()
                .orElse(0D);
    }

    static int editDistance(String left, String right) {
        int[][] dp = new int[left.length() + 1][right.length() + 1];
        for (int i = 0; i <= left.length(); i++) {
            dp[i][0] = i;
        }
        for (int j = 0; j <= right.length(); j++) {
            dp[0][j] = j;
        }
        for (int i = 1; i <= left.length(); i++) {
            for (int j = 1; j <= right.length(); j++) {
                int substitution = dp[i - 1][j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1);
                dp[i][j] = Math.min(substitution, Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1));
            }
        }
        return dp[left.length()][right.length()];
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}