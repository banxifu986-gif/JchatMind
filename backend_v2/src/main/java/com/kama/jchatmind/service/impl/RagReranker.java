package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.model.dto.QueryRewriteResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static com.kama.jchatmind.service.impl.RagRetrievalContextPolicy.*;
import static com.kama.jchatmind.service.impl.RagRetrievalMetadata.*;
import static com.kama.jchatmind.service.impl.RagRetrievalSignals.*;

@Slf4j
final class RagReranker {
    static final int RERANK_CANDIDATE_LIMIT = 50;
    private static final double RERANK_RANK_PENALTY = 0.03D;
    private static final double MAX_RERANK_RANK_PENALTY = 0.15D;
    private static final int MIN_CONTENT_SUBSTRING_LENGTH = 8;
    private static final int MIN_PATH_SUBSTRING_LENGTH = 4;
    private static final Set<String> GENERIC_LEAF_TITLES = Set.of("回答", "原理", "总结", "方案");

    private final BgeRerankerService bgeRerankerService;
    private final boolean rerankDebug;

    RagReranker(BgeRerankerService bgeRerankerService, boolean rerankDebug) {
        this.bgeRerankerService = bgeRerankerService;
        this.rerankDebug = rerankDebug;
    }

    List<RagRetrievalResult> rerank(
            String normalizedQuery,
            RetrievalContext context,
            List<RagRetrievalResult> candidates
    ) {
        if (candidates.size() <= 1 || !StringUtils.hasText(normalizedQuery)) {
            return candidates;
        }

        int rerankCandidateCount = Math.min(RERANK_CANDIDATE_LIMIT, candidates.size());
        List<RagRetrievalResult> rerankCandidates = candidates.subList(0, rerankCandidateCount);
        List<RagRetrievalResult> remainingCandidates = candidates.subList(rerankCandidateCount, candidates.size());
        List<Double> bgeScores = rerankWithBge(normalizedQuery, rerankCandidates);
        if (bgeScores.size() == rerankCandidateCount) {
            return applyBgeRerank(rerankCandidates, remainingCandidates, bgeScores);
        }
        List<ScoredRagResult> scoredResults = rerankCandidates.stream()
                .map(result -> new ScoredRagResult(result, rerankScore(normalizedQuery, context, result)))
                .toList();

        Comparator<ScoredRagResult> comparator = Comparator
                .comparingDouble((ScoredRagResult item) -> item.score().finalScore())
                .reversed()
                .thenComparing(item -> item.result().getDistance(), Comparator.nullsLast(Double::compareTo))
                .thenComparingInt(item -> safeRank(item.result().getRank()));

        List<ScoredRagResult> reranked = scoredResults.stream()
                .sorted(comparator)
                .toList();

        if (rerankDebug) {
            logRerankDebug(normalizedQuery, reranked);
        }

        for (int i = 0; i < reranked.size(); i++) {
            reranked.get(i).result().setRank(i + 1);
        }
        List<RagRetrievalResult> results = new ArrayList<>(candidates.size());
        results.addAll(reranked.stream().map(ScoredRagResult::result).toList());
        for (int i = 0; i < remainingCandidates.size(); i++) {
            remainingCandidates.get(i).setRank(rerankCandidateCount + i + 1);
        }
        results.addAll(remainingCandidates);
        return results;
    }

    private List<Double> rerankWithBge(String normalizedQuery, List<RagRetrievalResult> candidates) {
        try {
            return bgeRerankerService.rerank(
                    normalizedQuery,
                    candidates.stream().map(this::rerankText).toList()
            );
        } catch (RuntimeException exception) {
            log.warn("BGE reranker unavailable; fallback to local rerank: {}", exception.getMessage());
            return List.of();
        }
    }

    private List<RagRetrievalResult> applyBgeRerank(
            List<RagRetrievalResult> rerankCandidates,
            List<RagRetrievalResult> remainingCandidates,
            List<Double> bgeScores
    ) {
        List<RerankedRagResult> scoredResults = new ArrayList<>(rerankCandidates.size());
        for (int i = 0; i < rerankCandidates.size(); i++) {
            scoredResults.add(new RerankedRagResult(rerankCandidates.get(i), bgeScores.get(i)));
        }
        List<RerankedRagResult> reranked = scoredResults.stream()
                .sorted(Comparator
                        .comparingDouble(RerankedRagResult::score)
                        .reversed()
                        .thenComparing(item -> item.result().getDistance(), Comparator.nullsLast(Double::compareTo))
                        .thenComparingInt(item -> safeRank(item.result().getRank())))
                .toList();
        List<RagRetrievalResult> results = new ArrayList<>(rerankCandidates.size() + remainingCandidates.size());
        for (int i = 0; i < reranked.size(); i++) {
            RagRetrievalResult result = reranked.get(i).result();
            result.setRerankScore(reranked.get(i).score());
            result.setRank(i + 1);
            results.add(result);
        }
        for (int i = 0; i < remainingCandidates.size(); i++) {
            remainingCandidates.get(i).setRank(rerankCandidates.size() + i + 1);
            results.add(remainingCandidates.get(i));
        }
        return results;
    }

    private String rerankText(RagRetrievalResult result) {
        String title = extractRetrievableTitle(result.getMetadata());
        if (!StringUtils.hasText(title)) {
            return result.getContent();
        }
        return title + "\n" + (result.getContent() == null ? "" : result.getContent());
    }

    private RerankScore rerankScore(String normalizedQuery, RetrievalContext context, RagRetrievalResult result) {
        RerankScore lexicalScore = lexicalScore(normalizedQuery, context, result);
        double titleBm25SignalScore = bm25SignalScore(result.getTitleBm25Rank(), result.getTitleBm25Score(), 0.05D, 0.03D);
        double contentBm25SignalScore = bm25SignalScore(result.getContentBm25Rank(), result.getContentBm25Score(), 0.10D, 0.05D);
        double vectorSignalScore = vectorSignalScore(result);
        double rankPenalty = Math.min(
                (safeRank(result.getRank()) - 1) * RERANK_RANK_PENALTY,
                MAX_RERANK_RANK_PENALTY
        );
        return lexicalScore.withRetrievalSignals(titleBm25SignalScore, contentBm25SignalScore, vectorSignalScore)
                .withRankPenalty(rankPenalty);
    }

    private RerankScore lexicalScore(String normalizedQuery, RetrievalContext context, RagRetrievalResult result) {
        if (!StringUtils.hasText(normalizedQuery)) {
            return RerankScore.empty();
        }

        String title = normalize(extractRetrievableTitle(result.getMetadata()));
        String contentPath = normalize(extractMetadataText(result.getMetadata(), "contentPath"));
        String parentContentPath = normalize(extractMetadataText(result.getMetadata(), "parentContentPath"));
        String sourceName = normalize(extractMetadataText(result.getMetadata(), "sourceName"));
        String content = normalize(result.getContent());
        String sectionType = extractMetadataText(result.getMetadata(), "sectionType");
        Integer pathDepth = extractMetadataInt(result.getMetadata(), "pathDepth");
        Integer localContentLength = extractMetadataInt(result.getMetadata(), "localContentLength");
        Set<String> queryTerms = terms(normalizedQuery);

        double titleExactScore = 0D;
        double titleContainsScore = 0D;
        double titleOverlapScore = 0D;
        if (StringUtils.hasText(title)) {
            if (title.equals(normalizedQuery)) {
                titleExactScore = 0.45D;
            } else if (containsMeaningful(title, normalizedQuery) || containsMeaningful(normalizedQuery, title)) {
                titleContainsScore = 0.25D;
            }
            titleOverlapScore = overlapRatio(queryTerms, terms(title)) * 0.12D;
        }

        double contentContainsScore = 0D;
        double contentOverlapScore = 0D;
        if (StringUtils.hasText(content)) {
            if (normalizedQuery.length() >= MIN_CONTENT_SUBSTRING_LENGTH && content.contains(normalizedQuery)) {
                contentContainsScore = 0.22D;
            } else if (content.length() >= MIN_CONTENT_SUBSTRING_LENGTH && normalizedQuery.contains(content)) {
                contentContainsScore = 0.14D;
            }
            contentOverlapScore = overlapRatio(queryTerms, terms(content)) * 0.16D;
        }

        double contentPathScore = contentPathScore(normalizedQuery, context, contentPath, sourceName, queryTerms, result.getKbId());
        double structureScore = structureScore(
                normalizedQuery,
                queryTerms,
                title,
                contentPath,
                parentContentPath,
                sectionType,
                pathDepth,
                localContentLength
        );
        return new RerankScore(
                titleExactScore,
                titleContainsScore,
                titleOverlapScore,
                contentContainsScore,
                contentOverlapScore,
                contentPathScore,
                structureScore,
                0D,
                0D,
                0D,
                0D
        );
    }

    private double bm25SignalScore(Integer rank, Double score, double rankWeight, double scoreWeight) {
        return Math.min(normalizedRankScore(rank) * rankWeight + boundedBm25Score(score) * scoreWeight, rankWeight + scoreWeight);
    }

    private double vectorSignalScore(RagRetrievalResult result) {
        double score = normalizedRankScore(result.getVectorRank()) * 0.08D;
        Double distance = result.getVectorDistance();
        if (distance != null && !distance.isNaN()) {
            score += 1D / (1D + Math.max(distance, 0D)) * 0.08D;
        }
        return Math.min(score, 0.16D);
    }

    private double normalizedRankScore(Integer rank) {
        if (rank == null || rank <= 0 || rank > RERANK_CANDIDATE_LIMIT) {
            return 0D;
        }
        return (double) (RERANK_CANDIDATE_LIMIT - rank + 1) / RERANK_CANDIDATE_LIMIT;
    }

    private double boundedBm25Score(Double score) {
        if (score == null || score <= 0D) {
            return 0D;
        }
        return Math.min(score / (1D + score), 1D);
    }

    private double contentPathScore(
            String normalizedQuery,
            RetrievalContext context,
            String contentPath,
            String sourceName,
            Set<String> queryTerms,
            String kbId
    ) {
        if (!isPathAwareQuery(normalizedQuery) && !context.hasContext()) {
            return 0D;
        }

        double score = 0D;
        if (context.hasContext()) {
            score += contextMatchScore(context, contentPath, sourceName, kbId);
        }
        if (StringUtils.hasText(contentPath)) {
            if (containsMeaningfulPath(contentPath, normalizedQuery) || containsMeaningfulPath(normalizedQuery, contentPath)) {
                score += 0.28D;
            }
            score += overlapRatio(queryTerms, terms(contentPath)) * 0.18D;
        }
        if (StringUtils.hasText(sourceName)) {
            if (containsMeaningfulPath(sourceName, normalizedQuery) || containsMeaningfulPath(normalizedQuery, sourceName)) {
                score += 0.10D;
            }
            score += overlapRatio(queryTerms, terms(sourceName)) * 0.08D;
        }
        return Math.min(score, 0.32D);
    }

    private double contextMatchScore(RetrievalContext context, String contentPath, String sourceName, String kbId) {
        double score = 0D;
        if (StringUtils.hasText(context.kbId()) && context.kbId().equals(kbId)) {
            score += 0.08D;
        }
        if (StringUtils.hasText(context.normalizedSourceName()) && context.normalizedSourceName().equals(normalize(sourceName))) {
            score += 0.12D;
        }
        if (StringUtils.hasText(context.contentPathPrefix())
                && StringUtils.hasText(contentPath)
                && normalize(contentPath).startsWith(context.contentPathPrefix())) {
            score += 0.20D;
        }
        if (context.applyMode() == QueryRewriteResult.ContextApplyMode.HARD) {
            score += 0.05D;
        }
        return score;
    }

    private double structureScore(
            String normalizedQuery,
            Set<String> queryTerms,
            String title,
            String contentPath,
            String parentContentPath,
            String sectionType,
            Integer pathDepth,
            Integer localContentLength
    ) {
        QueryIntentProfile intentProfile = detectQueryIntentProfile(normalizedQuery);
        if (intentProfile == QueryIntentProfile.NONE) {
            return 0D;
        }

        double score = 0D;
        boolean isLeafQa = "LEAF_QA".equals(sectionType);
        boolean isLeafContent = "LEAF_CONTENT".equals(sectionType);
        boolean isParentOverview = "PARENT_OVERVIEW".equals(sectionType);

        if (isLeafQa) {
            score += 0.08D;
        } else if (isLeafContent) {
            score += 0.03D;
        }
        if (isParentOverview) {
            score -= 0.03D;
        }

        if (StringUtils.hasText(parentContentPath)) {
            score += overlapRatio(queryTerms, terms(parentContentPath)) * 0.03D;
        }

        if (StringUtils.hasText(contentPath)) {
            score += overlapRatio(queryTerms, terms(contentPath)) * 0.02D;
        }

        if (isParentOverview && (localContentLength == null || localContentLength == 0)) {
            score -= 0.02D;
        }

        if (isLeafQa && isGenericLeafTitle(title)) {
            score += 0.02D;
        }

        if (intentProfile == QueryIntentProfile.HOW_TO && isLeafQa) {
            score += 0.03D;
        }
        if (intentProfile == QueryIntentProfile.PRINCIPLE && StringUtils.hasText(title) && title.contains("原理")) {
            score += 0.05D;
        }
        if (intentProfile == QueryIntentProfile.DIFFERENCE && StringUtils.hasText(title)
                && (title.contains("区别") || title.contains("对比"))) {
            score += 0.05D;
        }
        if (intentProfile == QueryIntentProfile.WHY && StringUtils.hasText(title) && title.contains("为什么")) {
            score += 0.04D;
        }

        return score;
    }

    private QueryIntentProfile detectQueryIntentProfile(String normalizedQuery) {
        if (!StringUtils.hasText(normalizedQuery)) {
            return QueryIntentProfile.NONE;
        }
        if (normalizedQuery.contains("有什么区别") || normalizedQuery.contains("区别") || normalizedQuery.contains("对比")) {
            return QueryIntentProfile.DIFFERENCE;
        }
        if (normalizedQuery.contains("原理")) {
            return QueryIntentProfile.PRINCIPLE;
        }
        if (normalizedQuery.contains("为什么")) {
            return QueryIntentProfile.WHY;
        }
        if (normalizedQuery.contains("如何使用")
                || normalizedQuery.contains("怎么使用")
                || normalizedQuery.contains("怎么用")
                || normalizedQuery.contains("如何用")
                || normalizedQuery.contains("具体该怎么做")) {
            return QueryIntentProfile.HOW_TO;
        }
        int separatorIndex = normalizedQuery.indexOf(' ');
        String leadToken = separatorIndex >= 0 ? normalizedQuery.substring(0, separatorIndex) : normalizedQuery;
        return isGenericLeafTitle(leadToken) ? QueryIntentProfile.GENERIC_LEAF : QueryIntentProfile.NONE;
    }

    private boolean isGenericLeafTitle(String title) {
        return StringUtils.hasText(title) && GENERIC_LEAF_TITLES.contains(title.trim());
    }

    private boolean isPathAwareQuery(String normalizedQuery) {
        return normalizedQuery.contains(">")
                || normalizedQuery.contains("/")
                || normalizedQuery.contains("\\");
    }

    private void logRerankDebug(String query, List<ScoredRagResult> reranked) {
        for (int i = 0; i < reranked.size(); i++) {
            ScoredRagResult item = reranked.get(i);
            RagRetrievalResult result = item.result();
            RerankScore score = item.score();
            log.info(
                    "RAG rerank debug: query={}, newRank={}, oldRank={}, chunkId={}, title={}, distance={}, finalScore={}, lexicalScore={}, rankPenalty={}, titleExact={}, titleContains={}, titleOverlap={}, contentContains={}, contentOverlap={}, titleBm25Signal={}, contentBm25Signal={}, vectorSignal={}, contentPath={}, structure={}",
                    query,
                    i + 1,
                    result.getRank(),
                    result.getChunkId(),
                    extractRetrievableTitle(result.getMetadata()),
                    result.getDistance(),
                    score.finalScore(),
                    score.lexicalScore(),
                    score.rankPenalty(),
                    score.titleExactScore(),
                    score.titleContainsScore(),
                    score.titleOverlapScore(),
                    score.contentContainsScore(),
                    score.contentOverlapScore(),
                    score.titleBm25SignalScore(),
                    score.contentBm25SignalScore(),
                    score.vectorSignalScore(),
                    score.contentPathScore(),
                    score.structureScore()
            );
        }
    }

    private boolean containsMeaningful(String text, String query) {
        return query.length() >= 2 && text.contains(query);
    }

    private boolean containsMeaningfulPath(String text, String query) {
        return query.length() >= MIN_PATH_SUBSTRING_LENGTH && text.contains(query);
    }

    private double overlapRatio(Set<String> queryTerms, Set<String> targetTerms) {
        if (queryTerms.isEmpty() || targetTerms.isEmpty()) {
            return 0D;
        }
        long matched = queryTerms.stream()
                .filter(targetTerms::contains)
                .count();
        return (double) matched / queryTerms.size();
    }


    private record ScoredRagResult(
            RagRetrievalResult result,
            RerankScore score
    ) {
    }

    private record RerankedRagResult(
            RagRetrievalResult result,
            double score
    ) {
    }

    private enum QueryIntentProfile {
        NONE,
        GENERIC_LEAF,
        DIFFERENCE,
        PRINCIPLE,
        WHY,
        HOW_TO
    }

    private record RerankScore(
            double titleExactScore,
            double titleContainsScore,
            double titleOverlapScore,
            double contentContainsScore,
            double contentOverlapScore,
            double contentPathScore,
            double structureScore,
            double titleBm25SignalScore,
            double contentBm25SignalScore,
            double vectorSignalScore,
            double rankPenalty
    ) {
        static RerankScore empty() {
            return new RerankScore(0D, 0D, 0D, 0D, 0D, 0D, 0D, 0D, 0D, 0D, 0D);
        }

        RerankScore withRetrievalSignals(
                double titleBm25SignalScore,
                double contentBm25SignalScore,
                double vectorSignalScore
        ) {
            return new RerankScore(
                    titleExactScore,
                    titleContainsScore,
                    titleOverlapScore,
                    contentContainsScore,
                    contentOverlapScore,
                    contentPathScore,
                    structureScore,
                    titleBm25SignalScore,
                    contentBm25SignalScore,
                    vectorSignalScore,
                    rankPenalty
            );
        }

        RerankScore withRankPenalty(double rankPenalty) {
            return new RerankScore(
                    titleExactScore,
                    titleContainsScore,
                    titleOverlapScore,
                    contentContainsScore,
                    contentOverlapScore,
                    contentPathScore,
                    structureScore,
                    titleBm25SignalScore,
                    contentBm25SignalScore,
                    vectorSignalScore,
                    rankPenalty
            );
        }

        double lexicalScore() {
            return Math.min(
                    titleExactScore + titleContainsScore + titleOverlapScore + contentContainsScore + contentOverlapScore + contentPathScore + structureScore,
                    0.75D
            );
        }

        double retrievalSignalScore() {
            return Math.min(titleBm25SignalScore + contentBm25SignalScore + vectorSignalScore, 0.26D);
        }

        double finalScore() {
            return lexicalScore() + retrievalSignalScore() - rankPenalty;
        }
    }
}
