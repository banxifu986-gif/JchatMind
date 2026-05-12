package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.service.RagService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
public class RagServiceImpl implements RagService {
    private static final int RERANK_CANDIDATE_LIMIT = 10;
    private static final int TITLE_MATCH_CANDIDATE_LIMIT = 5;
    private static final int TITLE_CONTAINS_CANDIDATE_LIMIT = 10;
    private static final int TITLE_KEYWORD_CANDIDATE_LIMIT = 10;
    private static final int TITLE_TRIGRAM_CANDIDATE_LIMIT = 10;
    private static final double RERANK_RANK_PENALTY = 0.03D;
    private static final double TITLE_TRIGRAM_MIN_SCORE = 0.18D;
    private static final int MIN_CONTENT_SUBSTRING_LENGTH = 8;
    private static final int TITLE_LOOKUP_MAX_QUERY_LENGTH = 80;
    private static final int TITLE_CONTAINS_MIN_QUERY_LENGTH = 2;
    private static final int TITLE_KEYWORD_MIN_LENGTH = 2;
    private static final int TITLE_KEYWORD_MAX_COUNT = 6;

    // 封装本地的模型调用
    private final WebClient webClient;
    private final ChunkBgeM3Mapper chunkBgeM3Mapper;
    private final String embeddingModel;
    private final boolean rerankDebug;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RagServiceImpl(WebClient.Builder builder, ChunkBgeM3Mapper chunkBgeM3Mapper,
                          @Value("${ollama.base-url}") String ollamaBaseUrl,
                          @Value("${ollama.embedding-model}") String embeddingModel,
                          @Value("${rag.rerank.debug:false}") boolean rerankDebug) {
        this.webClient = builder.baseUrl(ollamaBaseUrl).build();
        this.chunkBgeM3Mapper = chunkBgeM3Mapper;
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
        if (limit <= 0) {
            return List.of();
        }
        String normalizedQuery = normalize(query);
        String queryEmbedding = toPgVector(doEmbed(query));
        int candidateLimit = Math.max(limit, RERANK_CANDIDATE_LIMIT);
        List<RagRetrievalResult> vectorCandidates = chunkBgeM3Mapper.similaritySearchDetailed(kbId, queryEmbedding, candidateLimit);
        List<RagRetrievalResult> titleCandidates = findTitleExactCandidates(kbId, normalizedQuery);
        List<RagRetrievalResult> titleContainsCandidates = findTitleContainsCandidates(kbId, normalizedQuery);
        List<RagRetrievalResult> titleKeywordCandidates = findTitleKeywordCandidates(kbId, normalizedQuery);
        List<RagRetrievalResult> titleTrigramCandidates = findTitleTrigramCandidates(kbId, normalizedQuery);
        List<RagRetrievalResult> candidates = mergeCandidates(titleCandidates, vectorCandidates);
        candidates = mergeCandidates(candidates, titleContainsCandidates);
        candidates = mergeCandidates(candidates, titleKeywordCandidates);
        candidates = mergeCandidates(candidates, titleTrigramCandidates);
        return rerank(normalizedQuery, candidates).stream()
                .limit(limit)
                .toList();
    }

    private List<RagRetrievalResult> findTitleExactCandidates(String kbId, String normalizedQuery) {
        if (!shouldTryTitleExactLookup(normalizedQuery)) {
            return List.of();
        }
        return chunkBgeM3Mapper.searchByTitleExact(kbId, normalizedQuery, TITLE_MATCH_CANDIDATE_LIMIT);
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

    private boolean shouldTryTitleExactLookup(String normalizedQuery) {
        if (!StringUtils.hasText(normalizedQuery)) {
            return false;
        }
        if (normalizedQuery.length() > TITLE_LOOKUP_MAX_QUERY_LENGTH) {
            return false;
        }
        return !normalizedQuery.contains("?")
                && !normalizedQuery.contains("？")
                && !normalizedQuery.contains("。")
                && !normalizedQuery.contains("！");
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

    private List<String> buildTitleKeywords(String normalizedQuery) {
        Set<String> queryTerms = terms(normalizedQuery);
        List<String> keywords = new java.util.ArrayList<>();
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
            List<RagRetrievalResult> titleCandidates,
            List<RagRetrievalResult> vectorCandidates
    ) {
        List<RagRetrievalResult> merged = new java.util.ArrayList<>();
        Set<String> seenChunkIds = new LinkedHashSet<>();

        for (RagRetrievalResult result : titleCandidates) {
            if (seenChunkIds.add(result.getChunkId())) {
                merged.add(result);
            }
        }
        for (RagRetrievalResult result : vectorCandidates) {
            if (seenChunkIds.add(result.getChunkId())) {
                merged.add(result);
            }
        }
        for (int i = 0; i < merged.size(); i++) {
            merged.get(i).setRank(i + 1);
        }
        return merged;
    }

    private List<RagRetrievalResult> rerank(String normalizedQuery, List<RagRetrievalResult> candidates) {
        if (candidates.size() <= 1 || !StringUtils.hasText(normalizedQuery)) {
            return candidates;
        }

        List<ScoredRagResult> scoredResults = candidates.stream()
                .map(result -> new ScoredRagResult(result, rerankScore(normalizedQuery, result)))
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

    private RerankScore rerankScore(String normalizedQuery, RagRetrievalResult result) {
        RerankScore lexicalScore = lexicalScore(normalizedQuery, result);
        double rankPenalty = (safeRank(result.getRank()) - 1) * RERANK_RANK_PENALTY;
        return lexicalScore.withRankPenalty(rankPenalty);
    }

    private RerankScore lexicalScore(String normalizedQuery, RagRetrievalResult result) {
        if (!StringUtils.hasText(normalizedQuery)) {
            return RerankScore.empty();
        }

        String title = normalize(extractTitle(result.getMetadata()));
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

        return new RerankScore(
                titleExactScore,
                titleContainsScore,
                titleOverlapScore,
                contentContainsScore,
                contentOverlapScore,
                0D
        );
    }

    private void logRerankDebug(String query, List<ScoredRagResult> reranked) {
        for (int i = 0; i < reranked.size(); i++) {
            ScoredRagResult item = reranked.get(i);
            RagRetrievalResult result = item.result();
            RerankScore score = item.score();
            log.info(
                    "RAG rerank debug: query={}, newRank={}, oldRank={}, chunkId={}, title={}, distance={}, finalScore={}, lexicalScore={}, rankPenalty={}, titleExact={}, titleContains={}, titleOverlap={}, contentContains={}, contentOverlap={}",
                    query,
                    i + 1,
                    result.getRank(),
                    result.getChunkId(),
                    extractTitle(result.getMetadata()),
                    result.getDistance(),
                    score.finalScore(),
                    score.lexicalScore(),
                    score.rankPenalty(),
                    score.titleExactScore(),
                    score.titleContainsScore(),
                    score.titleOverlapScore(),
                    score.contentContainsScore(),
                    score.contentOverlapScore()
            );
        }
    }

    private String extractTitle(String metadata) {
        if (!StringUtils.hasText(metadata)) {
            return "";
        }
        try {
            JsonNode root = objectMapper.readTree(metadata);
            JsonNode titleNode = root.get("title");
            return titleNode != null && titleNode.isTextual() ? titleNode.asText() : "";
        } catch (JsonProcessingException e) {
            return "";
        }
    }

    private boolean containsMeaningful(String text, String query) {
        return query.length() >= 2 && text.contains(query);
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
        Set<String> result = new LinkedHashSet<>();
        String normalized = normalize(text).replaceAll("[^\\p{IsHan}\\p{L}\\p{N}]+", " ");
        for (String token : normalized.split("\\s+")) {
            if (!StringUtils.hasText(token)) {
                continue;
            }
            if (containsHan(token)) {
                addCjkBigrams(result, token);
            } else if (token.length() >= 2) {
                result.add(token);
            }
        }
        return result;
    }

    private void addCjkBigrams(Set<String> terms, String token) {
        int[] codePoints = token.codePoints().toArray();
        if (codePoints.length == 1) {
            terms.add(token);
            return;
        }
        for (int i = 0; i < codePoints.length - 1; i++) {
            terms.add(new String(codePoints, i, 2));
        }
    }

    private boolean containsHan(String text) {
        return text.codePoints()
                .anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    private int safeRank(Integer rank) {
        return rank == null || rank <= 0 ? Integer.MAX_VALUE : rank;
    }

    private String normalize(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT)
                .replace("\r", " ")
                .replace("\n", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String toPgVector(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            sb.append(v[i]);
            if (i < v.length - 1) sb.append(",");
        }
        sb.append("]");
        return sb.toString();
    }

    private record ScoredRagResult(
            RagRetrievalResult result,
            RerankScore score
    ) {
    }

    private record RerankScore(
            double titleExactScore,
            double titleContainsScore,
            double titleOverlapScore,
            double contentContainsScore,
            double contentOverlapScore,
            double rankPenalty
    ) {
        static RerankScore empty() {
            return new RerankScore(0D, 0D, 0D, 0D, 0D, 0D);
        }

        RerankScore withRankPenalty(double rankPenalty) {
            return new RerankScore(
                    titleExactScore,
                    titleContainsScore,
                    titleOverlapScore,
                    contentContainsScore,
                    contentOverlapScore,
                    rankPenalty
            );
        }

        double lexicalScore() {
            return Math.min(
                    titleExactScore + titleContainsScore + titleOverlapScore + contentContainsScore + contentOverlapScore,
                    0.65D
            );
        }

        double finalScore() {
            return lexicalScore() - rankPenalty;
        }
    }
}
