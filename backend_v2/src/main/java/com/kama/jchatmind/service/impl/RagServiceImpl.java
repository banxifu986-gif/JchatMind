package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.dto.QueryRewriteResult;
import com.kama.jchatmind.model.dto.RagRetrievalContext;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.service.QueryRewriteService;
import com.kama.jchatmind.service.RagService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
public class RagServiceImpl implements RagService {
    private static final int RERANK_CANDIDATE_LIMIT = 10;
    private static final int TITLE_MATCH_CANDIDATE_LIMIT = 20;
    private static final int TITLE_CONTAINS_CANDIDATE_LIMIT = 10;
    private static final int TITLE_KEYWORD_CANDIDATE_LIMIT = 10;
    private static final int TITLE_TRIGRAM_CANDIDATE_LIMIT = 10;
    private static final int TITLE_FULL_TEXT_CANDIDATE_LIMIT = 10;
    private static final double BM25_K1 = 1.2D;
    private static final double BM25_B = 0.75D;
    private static final double RERANK_RANK_PENALTY = 0.03D;
    private static final double TITLE_TRIGRAM_MIN_SCORE = 0.18D;
    private static final int MIN_CONTENT_SUBSTRING_LENGTH = 8;
    private static final int MIN_PATH_SUBSTRING_LENGTH = 4;
    private static final int TITLE_CONTAINS_MIN_QUERY_LENGTH = 2;
    private static final int TITLE_KEYWORD_MIN_LENGTH = 2;
    private static final int TITLE_KEYWORD_MAX_COUNT = 6;

    private final WebClient webClient;
    private final ChunkBgeM3Mapper chunkBgeM3Mapper;
    private final QueryRewriteService queryRewriteService;
    private final String embeddingModel;
    private final boolean rerankDebug;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RagServiceImpl(WebClient.Builder builder, ChunkBgeM3Mapper chunkBgeM3Mapper,
                          QueryRewriteService queryRewriteService,
                          @Value("${ollama.base-url}") String ollamaBaseUrl,
                          @Value("${ollama.embedding-model}") String embeddingModel,
                          @Value("${rag.rerank.debug:false}") boolean rerankDebug) {
        this.webClient = builder.baseUrl(ollamaBaseUrl).build();
        this.chunkBgeM3Mapper = chunkBgeM3Mapper;
        this.queryRewriteService = queryRewriteService;
        this.embeddingModel = embeddingModel;
        this.rerankDebug = rerankDebug;
    }

    @Data
    private static class EmbeddingResponse {
        private float[] embedding;
    }

    private float[] doEmbed(String text) {
        EmbeddingResponse resp = webClient.post()
                .uri("/api/embeddings")
                .bodyValue(Map.of(
                        "model", embeddingModel,
                        "prompt", text
                ))
                .retrieve()
                .bodyToMono(EmbeddingResponse.class)
                .block();
        Assert.notNull(resp, "Embedding response cannot be null");
        return resp.getEmbedding();
    }

    @Override
    public float[] embed(String text) {
        return doEmbed(text);
    }

    @Override
    public List<String> similaritySearch(String kbId, String title) {
        return retrieve(kbId, title, 3).stream()
                .map(RagRetrievalResult::getContent)
                .toList();
    }

    @Override
    public List<RagRetrievalResult> retrieve(String kbId, String query, int limit) {
        return retrieve(kbId, query, null, limit);
    }

    @Override
    public List<RagRetrievalResult> retrieve(String kbId, String query, RagRetrievalContext context, int limit) {
        if (limit <= 0) {
            return List.of();
        }
        QueryRewriteResult rewritten = queryRewriteService.rewrite(kbId, query, context);
        return retrieveWithPlan(kbId, rewritten, limit);
    }

    private List<RagRetrievalResult> retrieveWithPlan(String kbId, QueryRewriteResult rewritten, int limit) {
        String query = rewritten.getQuery();
        String normalizedQuery = normalize(query);
        String queryEmbedding = toPgVector(doEmbed(query));
        int candidateLimit = Math.max(limit, RERANK_CANDIDATE_LIMIT);

        RetrievalContext normalizedContext = normalizeContext(rewritten.getContext());
        List<RagRetrievalResult> vectorCandidates = findVectorCandidates(kbId, queryEmbedding, normalizedContext, candidateLimit);
        List<RagRetrievalResult> titleCandidates = rewritten.isTitleQuery()
                ? findTitleExactCandidates(kbId, normalizedQuery, queryEmbedding, normalizedContext)
                : List.of();
        List<RagRetrievalResult> titleContainsCandidates = rewritten.isTitleQuery()
                ? findTitleContainsCandidates(kbId, normalizedQuery)
                : List.of();
        List<RagRetrievalResult> titleKeywordCandidates = rewritten.isTitleQuery()
                ? findTitleKeywordCandidates(kbId, normalizedQuery)
                : List.of();
        List<RagRetrievalResult> titleTrigramCandidates = rewritten.isTitleQuery()
                ? findTitleTrigramCandidates(kbId, normalizedQuery)
                : List.of();
        List<RagRetrievalResult> titleBm25Candidates = rewritten.isTitleQuery()
                ? findTitleBm25Candidates(kbId, normalizedQuery)
                : List.of();

        List<RagRetrievalResult> candidates = mergeCandidates(titleCandidates, vectorCandidates);
        candidates = mergeCandidates(candidates, titleContainsCandidates);
        candidates = mergeCandidates(candidates, titleKeywordCandidates);
        candidates = mergeCandidates(candidates, titleTrigramCandidates);
        candidates = mergeCandidates(candidates, titleBm25Candidates);
        candidates = filterByContext(candidates, normalizedContext);

        return rerank(normalizedQuery, normalizedContext, candidates).stream()
                .limit(limit)
                .toList();
    }

    private List<RagRetrievalResult> findVectorCandidates(
            String kbId,
            String queryEmbedding,
            RetrievalContext context,
            int candidateLimit
    ) {
        if (context.hasContext()) {
            return chunkBgeM3Mapper.similaritySearchDetailedWithContext(
                    kbId,
                    queryEmbedding,
                    context.sourceName(),
                    context.sourceType(),
                    context.contentPathPrefix(),
                    candidateLimit
            );
        }
        return chunkBgeM3Mapper.similaritySearchDetailed(kbId, queryEmbedding, candidateLimit);
    }

    private List<RagRetrievalResult> findTitleExactCandidates(
            String kbId,
            String normalizedQuery,
            String queryEmbedding,
            RetrievalContext context
    ) {
        if (!shouldTryTitleExactLookup(normalizedQuery)) {
            return List.of();
        }
        if (context.hasContext()) {
            return chunkBgeM3Mapper.searchByTitleExactWithContext(
                    kbId,
                    normalizedQuery,
                    queryEmbedding,
                    context.sourceName(),
                    context.sourceType(),
                    context.contentPathPrefix(),
                    TITLE_MATCH_CANDIDATE_LIMIT
            );
        }
        return chunkBgeM3Mapper.searchByTitleExact(kbId, normalizedQuery, queryEmbedding, TITLE_MATCH_CANDIDATE_LIMIT);
    }

    private List<RagRetrievalResult> findTitleContainsCandidates(String kbId, String normalizedQuery) {
        if (!shouldTryTitleContainsLookup(normalizedQuery)) {
            return List.of();
        }
        String containsPattern = "%" + normalizedQuery + "%";
        return chunkBgeM3Mapper.searchByTitleContains(
                kbId,
                normalizedQuery,
                containsPattern,
                TITLE_CONTAINS_CANDIDATE_LIMIT
        );
    }

    private List<RagRetrievalResult> findTitleKeywordCandidates(String kbId, String normalizedQuery) {
        if (!shouldTryTitleKeywordLookup(normalizedQuery)) {
            return List.of();
        }
        List<String> keywords = buildTitleKeywords(normalizedQuery);
        if (keywords.isEmpty()) {
            return List.of();
        }
        return chunkBgeM3Mapper.searchByTitleKeywords(
                kbId,
                keywords,
                normalizedQuery.length(),
                TITLE_KEYWORD_CANDIDATE_LIMIT
        );
    }

    private List<RagRetrievalResult> findTitleTrigramCandidates(String kbId, String normalizedQuery) {
        if (!shouldTryTitleTrigramLookup(normalizedQuery)) {
            return List.of();
        }
        return chunkBgeM3Mapper.searchByTitleTrigram(
                kbId,
                normalizedQuery,
                TITLE_TRIGRAM_MIN_SCORE,
                TITLE_TRIGRAM_CANDIDATE_LIMIT
        );
    }

    private List<RagRetrievalResult> findTitleBm25Candidates(String kbId, String normalizedQuery) {
        if (!shouldTryTitleFullTextLookup(normalizedQuery)) {
            return List.of();
        }

        List<String> queryTerms = RetrievableTitleLexicalizer.tokenize(normalizedQuery);
        if (queryTerms.isEmpty()) {
            return List.of();
        }

        List<RagRetrievalResult> lexicalCandidates = chunkBgeM3Mapper.selectLexicalCandidatesByKbId(kbId);
        if (lexicalCandidates.isEmpty()) {
            return List.of();
        }

        Map<String, Integer> documentFrequency = new HashMap<>();
        Map<String, Integer> docLengths = new HashMap<>();
        Map<String, Map<String, Integer>> termFrequencies = new HashMap<>();
        double totalDocLength = 0D;

        for (RagRetrievalResult candidate : lexicalCandidates) {
            String searchText = extractRetrievableTitleSearchText(candidate.getMetadata());
            List<String> docTerms = RetrievableTitleLexicalizer.tokenizeWithDuplicates(searchText);
            if (docTerms.isEmpty()) {
                continue;
            }

            docLengths.put(candidate.getChunkId(), docTerms.size());
            totalDocLength += docTerms.size();

            Map<String, Integer> tf = new HashMap<>();
            Set<String> seenTerms = new LinkedHashSet<>();
            for (String term : docTerms) {
                tf.merge(term, 1, Integer::sum);
                seenTerms.add(term);
            }
            termFrequencies.put(candidate.getChunkId(), tf);
            for (String term : seenTerms) {
                documentFrequency.merge(term, 1, Integer::sum);
            }
        }

        if (termFrequencies.isEmpty()) {
            return List.of();
        }

        double avgDocLength = totalDocLength / termFrequencies.size();
        int documentCount = termFrequencies.size();
        List<ScoredLexicalCandidate> scoredCandidates = new ArrayList<>();
        Map<String, Double> scoreByChunkId = new HashMap<>();

        for (RagRetrievalResult candidate : lexicalCandidates) {
            Map<String, Integer> tf = termFrequencies.get(candidate.getChunkId());
            if (tf == null || tf.isEmpty()) {
                continue;
            }

            double score = bm25Score(
                    queryTerms,
                    tf,
                    docLengths.get(candidate.getChunkId()),
                    avgDocLength,
                    documentFrequency,
                    documentCount
            );
            if (score <= 0D) {
                continue;
            }

            scoredCandidates.add(new ScoredLexicalCandidate(candidate, score));
            scoreByChunkId.put(candidate.getChunkId(), score);
        }

        List<RagRetrievalResult> rankedCandidates = scoredCandidates.stream()
                .sorted(Comparator
                        .comparingDouble(ScoredLexicalCandidate::score)
                        .reversed()
                        .thenComparing(item -> extractRetrievableTitle(item.result().getMetadata()))
                        .thenComparing(item -> item.result().getChunkId()))
                .limit(TITLE_FULL_TEXT_CANDIDATE_LIMIT)
                .map(ScoredLexicalCandidate::result)
                .toList();

        for (int i = 0; i < rankedCandidates.size(); i++) {
            RagRetrievalResult result = rankedCandidates.get(i);
            double score = scoreByChunkId.getOrDefault(result.getChunkId(), 0D);
            result.setDistance(1D / (1D + score));
            result.setRank(i + 1);
        }
        return rankedCandidates;
    }

    private boolean shouldTryTitleExactLookup(String normalizedQuery) {
        if (!StringUtils.hasText(normalizedQuery)) {
            return false;
        }
        return true;
    }

    private boolean shouldTryTitleContainsLookup(String normalizedQuery) {
        if (!shouldTryTitleExactLookup(normalizedQuery)) {
            return false;
        }
        return normalizedQuery.length() >= TITLE_CONTAINS_MIN_QUERY_LENGTH;
    }

    private boolean shouldTryTitleKeywordLookup(String normalizedQuery) {
        return shouldTryTitleExactLookup(normalizedQuery);
    }

    private boolean shouldTryTitleTrigramLookup(String normalizedQuery) {
        return shouldTryTitleExactLookup(normalizedQuery);
    }

    private boolean shouldTryTitleFullTextLookup(String normalizedQuery) {
        return shouldTryTitleExactLookup(normalizedQuery);
    }

    private List<String> buildTitleKeywords(String normalizedQuery) {
        Set<String> queryTerms = terms(normalizedQuery);
        List<String> keywords = new ArrayList<>();
        for (String term : queryTerms) {
            if (!StringUtils.hasText(term) || term.length() < TITLE_KEYWORD_MIN_LENGTH) {
                continue;
            }
            keywords.add(term);
            if (keywords.size() >= TITLE_KEYWORD_MAX_COUNT) {
                break;
            }
        }
        return keywords;
    }

    private List<RagRetrievalResult> mergeCandidates(
            List<RagRetrievalResult> primaryCandidates,
            List<RagRetrievalResult> secondaryCandidates
    ) {
        List<RagRetrievalResult> merged = new ArrayList<>();
        Set<String> seenChunkIds = new LinkedHashSet<>();

        for (RagRetrievalResult result : primaryCandidates) {
            if (seenChunkIds.add(result.getChunkId())) {
                merged.add(result);
            }
        }
        for (RagRetrievalResult result : secondaryCandidates) {
            if (seenChunkIds.add(result.getChunkId())) {
                merged.add(result);
            }
        }
        for (int i = 0; i < merged.size(); i++) {
            merged.get(i).setRank(i + 1);
        }
        return merged;
    }

    private List<RagRetrievalResult> filterByContext(List<RagRetrievalResult> candidates, RetrievalContext context) {
        if (!context.hasContext()) {
            return candidates;
        }
        List<RagRetrievalResult> filtered = candidates.stream()
                .filter(result -> matchesContext(result, context))
                .toList();
        for (int i = 0; i < filtered.size(); i++) {
            filtered.get(i).setRank(i + 1);
        }
        return filtered;
    }

    private boolean matchesContext(RagRetrievalResult result, RetrievalContext context) {
        String metadata = result.getMetadata();
        if (StringUtils.hasText(context.sourceType())
                && !context.sourceType().equals(extractMetadataText(metadata, "sourceType"))) {
            return false;
        }
        if (StringUtils.hasText(context.sourceName())
                && !context.sourceName().equals(extractMetadataText(metadata, "sourceName"))) {
            return false;
        }
        if (StringUtils.hasText(context.contentPathPrefix())) {
            String contentPath = normalize(extractMetadataText(metadata, "contentPath"));
            return contentPath.startsWith(context.contentPathPrefix());
        }
        return true;
    }

    private List<RagRetrievalResult> rerank(String normalizedQuery, RetrievalContext context, List<RagRetrievalResult> candidates) {
        if (candidates.size() <= 1 || !StringUtils.hasText(normalizedQuery)) {
            return candidates;
        }

        List<ScoredRagResult> scoredResults = candidates.stream()
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
        return reranked.stream()
                .map(ScoredRagResult::result)
                .toList();
    }

    private RerankScore rerankScore(String normalizedQuery, RetrievalContext context, RagRetrievalResult result) {
        RerankScore lexicalScore = lexicalScore(normalizedQuery, context, result);
        double rankPenalty = (safeRank(result.getRank()) - 1) * RERANK_RANK_PENALTY;
        return lexicalScore.withRankPenalty(rankPenalty);
    }

    private RerankScore lexicalScore(String normalizedQuery, RetrievalContext context, RagRetrievalResult result) {
        if (!StringUtils.hasText(normalizedQuery)) {
            return RerankScore.empty();
        }

        String title = normalize(extractRetrievableTitle(result.getMetadata()));
        String contentPath = normalize(extractMetadataText(result.getMetadata(), "contentPath"));
        String sourceName = normalize(extractMetadataText(result.getMetadata(), "sourceName"));
        String content = normalize(result.getContent());
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

        double contentPathScore = contentPathScore(normalizedQuery, context, contentPath, sourceName, queryTerms);

        return new RerankScore(
                titleExactScore,
                titleContainsScore,
                titleOverlapScore,
                contentContainsScore,
                contentOverlapScore,
                contentPathScore,
                0D
        );
    }

    private double contentPathScore(
            String normalizedQuery,
            RetrievalContext context,
            String contentPath,
            String sourceName,
            Set<String> queryTerms
    ) {
        if (!isPathAwareQuery(normalizedQuery) && !context.hasContext()) {
            return 0D;
        }

        double score = 0D;
        if (context.hasContext()) {
            score += contextMatchScore(context, contentPath, sourceName);
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

    private double contextMatchScore(RetrievalContext context, String contentPath, String sourceName) {
        double score = 0D;
        if (StringUtils.hasText(context.normalizedSourceName()) && context.normalizedSourceName().equals(normalize(sourceName))) {
            score += 0.12D;
        }
        if (StringUtils.hasText(context.contentPathPrefix())
                && StringUtils.hasText(contentPath)
                && normalize(contentPath).startsWith(context.contentPathPrefix())) {
            score += 0.20D;
        }
        return score;
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
                    "RAG rerank debug: query={}, newRank={}, oldRank={}, chunkId={}, title={}, distance={}, finalScore={}, lexicalScore={}, rankPenalty={}, titleExact={}, titleContains={}, titleOverlap={}, contentContains={}, contentOverlap={}, contentPath={}",
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
                    score.contentPathScore()
            );
        }
    }

    private String extractRetrievableTitle(String metadata) {
        if (!StringUtils.hasText(metadata)) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(metadata);
            JsonNode titleNode = root.get("retrievableTitle");
            if (titleNode == null || !titleNode.isTextual()) {
                titleNode = root.get("title");
            }
            return titleNode != null && titleNode.isTextual() ? titleNode.asText() : "";
        } catch (JsonProcessingException e) {
            return "";
        }
    }

    private String extractRetrievableTitleSearchText(String metadata) {
        return extractMetadataText(metadata, "retrievableTitleSearchText");
    }

    private String extractMetadataText(String metadata, String fieldName) {
        if (!StringUtils.hasText(metadata)) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(metadata);
            JsonNode node = root.get(fieldName);
            return node != null && node.isTextual() ? node.asText() : "";
        } catch (JsonProcessingException e) {
            return "";
        }
    }

    private double bm25Score(
            List<String> queryTerms,
            Map<String, Integer> termFrequency,
            Integer docLength,
            double avgDocLength,
            Map<String, Integer> documentFrequency,
            int documentCount
    ) {
        if (queryTerms.isEmpty() || termFrequency.isEmpty() || docLength == null || docLength <= 0 || avgDocLength <= 0D) {
            return 0D;
        }

        double score = 0D;
        Set<String> uniqueQueryTerms = new LinkedHashSet<>(queryTerms);
        for (String term : uniqueQueryTerms) {
            Integer tf = termFrequency.get(term);
            if (tf == null || tf <= 0) {
                continue;
            }

            int df = documentFrequency.getOrDefault(term, 0);
            double idf = Math.log1p((documentCount - df + 0.5D) / (df + 0.5D));
            double denominator = tf + BM25_K1 * (1D - BM25_B + BM25_B * docLength / avgDocLength);
            score += idf * (tf * (BM25_K1 + 1D) / denominator);
        }
        return score;
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

    private Set<String> terms(String text) {
        return new LinkedHashSet<>(RetrievableTitleLexicalizer.tokenize(text));
    }

    private int safeRank(Integer rank) {
        return rank == null || rank <= 0 ? Integer.MAX_VALUE : rank;
    }

    private String normalize(String text) {
        return RetrievableTitleLexicalizer.normalize(text);
    }

    private RetrievalContext normalizeContext(RagRetrievalContext context) {
        if (context == null || !context.hasContext()) {
            return RetrievalContext.empty();
        }
        String sourceType = StringUtils.hasText(context.getSourceType()) ? context.getSourceType().trim() : null;
        String sourceName = StringUtils.hasText(context.getSourceName()) ? context.getSourceName().trim() : null;
        String normalizedSourceName = normalize(sourceName);
        String contentPath = StringUtils.hasText(context.getContentPath()) ? context.getContentPath().trim() : null;
        String contentPathPrefix = normalize(contentPath);
        return new RetrievalContext(
                sourceType,
                sourceName,
                StringUtils.hasText(normalizedSourceName) ? normalizedSourceName : null,
                contentPath,
                StringUtils.hasText(contentPathPrefix) ? contentPathPrefix : null
        );
    }

    private String parentContentPath(String contentPath) {
        if (!StringUtils.hasText(contentPath)) {
            return null;
        }
        int separatorIndex = contentPath.lastIndexOf(" > ");
        if (separatorIndex <= 0) {
            return contentPath;
        }
        return contentPath.substring(0, separatorIndex);
    }

    private String toPgVector(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            sb.append(v[i]);
            if (i < v.length - 1) {
                sb.append(",");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    private record ScoredRagResult(
            RagRetrievalResult result,
            RerankScore score
    ) {
    }

    private record ScoredLexicalCandidate(
            RagRetrievalResult result,
            double score
    ) {
    }

    private record RetrievalContext(
            String sourceType,
            String sourceName,
            String normalizedSourceName,
            String contentPath,
            String contentPathPrefix
    ) {
        static RetrievalContext empty() {
            return new RetrievalContext(null, null, null, null, null);
        }

        boolean hasContext() {
            return StringUtils.hasText(sourceType)
                    || StringUtils.hasText(sourceName)
                    || StringUtils.hasText(contentPathPrefix);
        }
    }

    private record RerankScore(
            double titleExactScore,
            double titleContainsScore,
            double titleOverlapScore,
            double contentContainsScore,
            double contentOverlapScore,
            double contentPathScore,
            double rankPenalty
    ) {
        static RerankScore empty() {
            return new RerankScore(0D, 0D, 0D, 0D, 0D, 0D, 0D);
        }

        RerankScore withRankPenalty(double rankPenalty) {
            return new RerankScore(
                    titleExactScore,
                    titleContainsScore,
                    titleOverlapScore,
                    contentContainsScore,
                    contentOverlapScore,
                    contentPathScore,
                    rankPenalty
            );
        }

        double lexicalScore() {
            return Math.min(
                    titleExactScore + titleContainsScore + titleOverlapScore + contentContainsScore + contentOverlapScore + contentPathScore,
                    0.75D
            );
        }

        double finalScore() {
            return lexicalScore() - rankPenalty;
        }
    }
}
