package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class RagServiceImpl implements RagService {
    private static final int RERANK_CANDIDATE_LIMIT = 50;
    private static final int TITLE_MATCH_CANDIDATE_LIMIT = 20;
    private static final int TITLE_CONTAINS_CANDIDATE_LIMIT = 10;
    private static final int TITLE_KEYWORD_CANDIDATE_LIMIT = 10;
    private static final int TITLE_TRIGRAM_CANDIDATE_LIMIT = 10;
    private static final int TITLE_FULL_TEXT_CANDIDATE_LIMIT = 10;
    private static final int CONTENT_FULL_TEXT_CANDIDATE_LIMIT = 20;
    private static final int MAX_SCOPE_MULTIPLIER = 5;
    private static final int RRF_K = 60;
    private static final double RERANK_RANK_PENALTY = 0.03D;
    private static final double MAX_RERANK_RANK_PENALTY = 0.15D;
    private static final double TITLE_TRIGRAM_MIN_SCORE = 0.18D;
    private static final int MIN_CONTENT_SUBSTRING_LENGTH = 8;
    private static final int MIN_PATH_SUBSTRING_LENGTH = 4;
    private static final int TITLE_CONTAINS_MIN_QUERY_LENGTH = 2;
    private static final int TITLE_KEYWORD_MIN_LENGTH = 2;
    private static final int TITLE_KEYWORD_MAX_COUNT = 6;
    private static final int TECHNICAL_ANCHOR_MIN_LENGTH = 16;
    private static final Duration EMBEDDING_TIMEOUT = Duration.ofSeconds(30);
    private static final Set<String> GENERIC_LEAF_TITLES = Set.of("回答", "原理", "总结", "方案");
    private static final Pattern PAGE_REFERENCE_PATTERN = Pattern.compile("第\\s*(\\d+)\\s*页");

    private final WebClient webClient;
    private final ChunkBgeM3Mapper chunkBgeM3Mapper;
    private final QueryRewriteService queryRewriteService;
    private final VchordBm25QueryService vchordBm25QueryService;
    private final BgeRerankerService bgeRerankerService;
    private final String embeddingModel;
    private final boolean rerankDebug;
    private final boolean disableQueryExpansion;
    private final boolean disableRerank;
    private final Map<String, float[]> embeddingCache;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RagServiceImpl(
            WebClient.Builder builder,
            ChunkBgeM3Mapper chunkBgeM3Mapper,
            QueryRewriteService queryRewriteService,
            VchordBm25QueryService vchordBm25QueryService,
            BgeRerankerService bgeRerankerService,
            @Value("${ollama.base-url}") String ollamaBaseUrl,
            @Value("${ollama.embedding-model}") String embeddingModel,
            @Value("${rag.rerank.debug:false}") boolean rerankDebug,
            @Value("${rag.eval.disable-query-expansion:false}") boolean disableQueryExpansion,
            @Value("${rag.eval.disable-rerank:false}") boolean disableRerank,
            @Value("${rag.embedding.cache.max-entries:2048}") int embeddingCacheMaxEntries
    ) {
        this.webClient = builder.baseUrl(ollamaBaseUrl).build();
        this.chunkBgeM3Mapper = chunkBgeM3Mapper;
        this.queryRewriteService = queryRewriteService;
        this.vchordBm25QueryService = vchordBm25QueryService;
        this.bgeRerankerService = bgeRerankerService;
        this.embeddingModel = embeddingModel;
        this.rerankDebug = rerankDebug;
        this.disableQueryExpansion = disableQueryExpansion;
        this.disableRerank = disableRerank;
        this.embeddingCache = createEmbeddingCache(embeddingCacheMaxEntries);
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class EmbeddingResponse {
        private List<float[]> embeddings;
    }

    private Map<String, float[]> createEmbeddingCache(int maxEntries) {
        int cacheSize = Math.max(maxEntries, 0);
        if (cacheSize == 0) {
            return Map.of();
        }
        return Collections.synchronizedMap(new LinkedHashMap<>(cacheSize, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
                return size() > cacheSize;
            }
        });
    }

    private float[] doEmbed(String text) {
        String cacheKey = StringUtils.hasText(text) ? text.trim() : text;
        if (!CollectionUtils.isEmpty(embeddingCache) && embeddingCache.containsKey(cacheKey)) {
            return embeddingCache.get(cacheKey).clone();
        }

        Map<String, Object> body = new HashMap<>();
        body.put("model", embeddingModel);
        body.put("input", text);
        body.put("keep_alive", -1);

        EmbeddingResponse resp = webClient.post()
                .uri("/api/embed")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(EmbeddingResponse.class)
                .block(EMBEDDING_TIMEOUT);
        Assert.notNull(resp, "Embedding response cannot be null");
        Assert.isTrue(!CollectionUtils.isEmpty(resp.getEmbeddings()), "Embedding response cannot be empty");
        Assert.notNull(resp.getEmbeddings().get(0), "Embedding vector cannot be null");

        float[] embedding = resp.getEmbeddings().get(0);
        if (!CollectionUtils.isEmpty(embeddingCache)) {
            embeddingCache.put(cacheKey, embedding.clone());
        }
        return embedding;
    }

    @Override
    public float[] embed(String text) {
        return doEmbed(text);
    }

    @Override
    public List<String> similaritySearch(List<String> kbIds, String title) {
        return retrieve(kbIds, title, 3).stream()
                .map(RagRetrievalResult::getContent)
                .toList();
    }

    @Override
    public List<RagRetrievalResult> retrieve(List<String> kbIds, String query, int limit) {
        return retrieve(kbIds, query, null, limit);
    }

    @Override
    public List<RagRetrievalResult> retrieve(
            List<String> kbIds,
            String query,
            RagRetrievalContext context,
            int limit
    ) {
        if (limit <= 0) {
            return List.of();
        }

        List<String> effectiveKbIds = sanitizeKbIds(kbIds);
        if (effectiveKbIds.isEmpty()) {
            return List.of();
        }

        QueryRewriteResult rewritten = queryRewriteService.rewrite(effectiveKbIds, query, context);
        return retrieveWithPlan(effectiveKbIds, rewritten, limit);
    }

    List<RagRetrievalResult> retrieveForIndependentBranchEvaluation(
            List<String> kbIds,
            String query,
            int limit,
            String variant
    ) {
        if (limit <= 0) {
            return List.of();
        }
        List<String> effectiveKbIds = sanitizeKbIds(kbIds);
        if (effectiveKbIds.isEmpty()) {
            return List.of();
        }
        QueryRewriteResult rewritten = queryRewriteService.rewrite(effectiveKbIds, query, null);
        return switch (variant) {
            case "current-flat" -> retrieveCurrentFlat(effectiveKbIds, rewritten, limit);
            case "two-branch-original" -> retrieveWithPlan(effectiveKbIds, rewritten, limit, false, true);
            case "three-branch-expanded" -> retrieveWithPlan(effectiveKbIds, rewritten, limit, true, true);
            default -> throw new IllegalArgumentException("未知独立三路评测变体: " + variant);
        };
    }

    @Override
    public List<RagRetrievalResult> retrievePdfPageAssets(
            List<String> kbIds,
            String query,
            RagRetrievalContext context,
            int limit
    ) {
        return retrieveAssetCandidates(
                kbIds,
                query,
                context,
                limit,
                "asset_pdf_page_text",
                chunkBgeM3Mapper::similaritySearchPdfPageAssets
        );
    }

    @Override
    public List<RagRetrievalResult> retrieveMarkdownTableAssets(
            List<String> kbIds,
            String query,
            RagRetrievalContext context,
            int limit
    ) {
        return retrieveAssetCandidates(
                kbIds,
                query,
                context,
                limit,
                "asset_table",
                chunkBgeM3Mapper::similaritySearchMarkdownTableAssets
        );
    }

    private List<RagRetrievalResult> retrieveAssetCandidates(
            List<String> kbIds,
            String query,
            RagRetrievalContext context,
            int limit,
            String channelPrefix,
            AssetCandidateSearcher assetCandidateSearcher
    ) {
        if (limit <= 0 || !StringUtils.hasText(query)) {
            return List.of();
        }

        List<String> effectiveKbIds = sanitizeKbIds(kbIds);
        if (effectiveKbIds.isEmpty()) {
            return List.of();
        }

        QueryRewriteResult rewritten = queryRewriteService.rewrite(effectiveKbIds, query, context);
        if (rewritten == null) {
            return List.of();
        }
        List<String> retrievalQueries = disableQueryExpansion
                ? List.of(rewritten.getQuery())
                : rewritten.getRetrievalQueries() == null || rewritten.getRetrievalQueries().isEmpty()
                ? List.of(rewritten.getQuery())
                : rewritten.getRetrievalQueries();
        List<String> retrievalQuerySources = rewritten.getRetrievalQuerySources() == null
                || rewritten.getRetrievalQuerySources().size() != retrievalQueries.size()
                ? Collections.nCopies(retrievalQueries.size(), "original")
                : rewritten.getRetrievalQuerySources();
        int candidateLimit = scaledCandidateLimit(limit, scopeMultiplier(effectiveKbIds.size()));
        Map<String, String> embeddingLiteralCache = new LinkedHashMap<>();
        List<RetrievalChannel> channels = new ArrayList<>();
        RetrievalContext normalizedContext = normalizeContext(rewritten.getContext(), rewritten.getContextApplyMode());
        List<String> candidateKbIds = scopedKbIds(effectiveKbIds, normalizedContext);
        if (candidateKbIds.isEmpty()) {
            return List.of();
        }

        for (int i = 0; i < retrievalQueries.size(); i++) {
            String retrievalQuery = retrievalQueries.get(i);
            String queryEmbedding = embeddingLiteral(embeddingLiteralCache, retrievalQuery);
            if (!StringUtils.hasText(queryEmbedding)) {
                continue;
            }
            List<RagRetrievalResult> candidates = assetCandidateSearcher.search(
                    candidateKbIds,
                    queryEmbedding,
                    hardContextValue(normalizedContext, RetrievalContext::sourceName),
                    hardContextValue(normalizedContext, RetrievalContext::sourceType),
                    hardContextValue(normalizedContext, RetrievalContext::contentPathPrefix),
                    candidateLimit
            );
            if (candidates != null && !candidates.isEmpty()) {
                channels.add(new RetrievalChannel(
                        channelPrefix + "_" + retrievalQuerySources.get(i),
                        annotateVectorCandidates(candidates)
                ));
            }
        }

        return rrfFuse(channels).stream()
                .limit(limit)
                .toList();
    }

    private List<RagRetrievalResult> retrieveWithPlan(List<String> kbIds, QueryRewriteResult rewritten, int limit) {
        return retrieveWithPlan(kbIds, rewritten, limit, true, false);
    }

    private List<RagRetrievalResult> retrieveWithPlan(
            List<String> kbIds,
            QueryRewriteResult rewritten,
            int limit,
            boolean includeExpandedQuery
    ) {
        return retrieveWithPlan(kbIds, rewritten, limit, includeExpandedQuery, false);
    }

    private List<RagRetrievalResult> retrieveWithPlan(
            List<String> kbIds,
            QueryRewriteResult rewritten,
            int limit,
            boolean includeExpandedQuery,
            boolean preserveQueryAnchors
    ) {
        String originalQuery = rewritten.getQuery();
        String normalizedOriginalQuery = normalize(originalQuery);
        Map<String, String> embeddingLiteralCache = new LinkedHashMap<>();

        int scopeMultiplier = scopeMultiplier(kbIds.size());
        int vectorCandidateLimit = scaledCandidateLimit(Math.max(limit, RERANK_CANDIDATE_LIMIT), scopeMultiplier);
        int titleMatchCandidateLimit = scaledCandidateLimit(TITLE_MATCH_CANDIDATE_LIMIT, scopeMultiplier);
        int titleContainsCandidateLimit = scaledCandidateLimit(TITLE_CONTAINS_CANDIDATE_LIMIT, scopeMultiplier);
        int titleKeywordCandidateLimit = scaledCandidateLimit(TITLE_KEYWORD_CANDIDATE_LIMIT, scopeMultiplier);
        int titleTrigramCandidateLimit = scaledCandidateLimit(TITLE_TRIGRAM_CANDIDATE_LIMIT, scopeMultiplier);
        int titleFullTextCandidateLimit = scaledCandidateLimit(TITLE_FULL_TEXT_CANDIDATE_LIMIT, scopeMultiplier);
        int contentFullTextCandidateLimit = scaledCandidateLimit(CONTENT_FULL_TEXT_CANDIDATE_LIMIT, scopeMultiplier);
        int sparseCandidateBudget = sparseCandidateBudget(
                rewritten.isTitleQuery(),
                titleMatchCandidateLimit,
                titleContainsCandidateLimit,
                titleKeywordCandidateLimit,
                titleTrigramCandidateLimit,
                titleFullTextCandidateLimit,
                contentFullTextCandidateLimit
        );

        RetrievalContext normalizedContext = normalizeContext(rewritten.getContext(), rewritten.getContextApplyMode());
        List<String> candidateKbIds = scopedKbIds(kbIds, normalizedContext);
        if (candidateKbIds.isEmpty()) {
            return List.of();
        }
        String originalQueryEmbedding = embeddingLiteral(
                embeddingLiteralCache,
                StringUtils.hasText(originalQuery) ? originalQuery : normalizedOriginalQuery
        );
        List<RagRetrievalResult> denseOriginal = fuseBranch(List.of(
                retrievalChannel(
                        "dense-original",
                        "vector",
                        "original",
                        findVectorCandidates(
                                candidateKbIds,
                                originalQueryEmbedding,
                                normalizedContext,
                                vectorCandidateLimit
                        ),
                        true
                )
        ), vectorCandidateLimit);
        List<RagRetrievalResult> sparseOriginal = fuseBranch(sparseChannels(
                "sparse-original",
                "original",
                candidateKbIds,
                normalizedOriginalQuery,
                originalQueryEmbedding,
                rewritten.isTitleQuery(),
                normalizedContext,
                titleMatchCandidateLimit,
                titleContainsCandidateLimit,
                titleKeywordCandidateLimit,
                titleTrigramCandidateLimit,
                titleFullTextCandidateLimit,
                contentFullTextCandidateLimit
        ), sparseCandidateBudget);
        List<RagRetrievalResult> expandedQuery = includeExpandedQuery
                ? retrieveExpandedQueryBranch(
                rewritten,
                originalQuery,
                normalizedOriginalQuery,
                candidateKbIds,
                normalizedContext,
                embeddingLiteralCache,
                vectorCandidateLimit,
                titleMatchCandidateLimit,
                titleContainsCandidateLimit,
                titleKeywordCandidateLimit,
                titleTrigramCandidateLimit,
                titleFullTextCandidateLimit,
                contentFullTextCandidateLimit,
                vectorCandidateLimit
        )
                : List.of();

        List<RetrievalChannel> branches = new ArrayList<>();
        addBranch(branches, "dense-original", denseOriginal);
        addBranch(branches, "sparse-original", sparseOriginal);
        addBranch(branches, "expanded-query", expandedQuery);
        List<RagRetrievalResult> candidates = rrfFuseByRank(branches);

        if (normalizedContext.applyMode() == QueryRewriteResult.ContextApplyMode.HARD) {
            candidates = filterByContext(candidates, normalizedContext);
        }
        if (preserveQueryAnchors) {
            candidates = preserveQueryAnchors(candidates, normalizedOriginalQuery);
        }

        List<RagRetrievalResult> finalResults = disableRerank
                ? candidates
                : rerank(normalizedOriginalQuery, normalizedContext, candidates);

        return finalResults.stream()
                .limit(limit)
                .toList();
    }

    private List<RagRetrievalResult> preserveQueryAnchors(
            List<RagRetrievalResult> candidates,
            String normalizedQuery
    ) {
        List<RagRetrievalResult> ordered = new ArrayList<>(candidates);
        for (int index = 1; index < ordered.size(); index++) {
            int current = index;
            while (current > 0
                    && shouldPromoteAnchor(ordered.get(current), ordered.get(current - 1), normalizedQuery)) {
                RagRetrievalResult promoted = ordered.set(current - 1, ordered.get(current));
                ordered.set(current, promoted);
                current--;
            }
        }
        for (int index = 0; index < ordered.size(); index++) {
            ordered.get(index).setRank(index + 1);
        }
        return ordered;
    }

    private boolean shouldPromoteAnchor(
            RagRetrievalResult candidate,
            RagRetrievalResult incumbent,
            String normalizedQuery
    ) {
        if (!hasQueryAnchor(candidate, normalizedQuery) || hasQueryAnchor(incumbent, normalizedQuery)) {
            return false;
        }
        Set<String> candidateBranches = branchNames(candidate);
        if (candidateBranches.isEmpty() || !candidateBranches.equals(branchNames(incumbent))) {
            return false;
        }
        Double candidateRrf = candidate.getRrfScore();
        Double incumbentRrf = incumbent.getRrfScore();
        if (candidateRrf == null || incumbentRrf == null) {
            return false;
        }
        double adjacentRankGap = 1D / (RRF_K + 1D) - 1D / (RRF_K + 2D);
        return Math.abs(candidateRrf - incumbentRrf)
                <= candidateBranches.size() * adjacentRankGap + 1.0E-12D;
    }

    private Set<String> branchNames(RagRetrievalResult result) {
        Set<String> branches = new LinkedHashSet<>();
        if (result.getRetrievalProvenance() == null) {
            return branches;
        }
        for (String provenance : result.getRetrievalProvenance()) {
            int separator = provenance.indexOf(':');
            if (separator > 0) {
                branches.add(provenance.substring(0, separator));
            }
        }
        return branches;
    }

    private boolean hasQueryAnchor(RagRetrievalResult result, String normalizedQuery) {
        Integer queryPageNumber = pageNumber(normalizedQuery);
        if (queryPageNumber != null
                && queryPageNumber.equals(extractMetadataInt(result.getMetadata(), "pageNumber"))) {
            String sourceName = normalize(extractMetadataText(result.getMetadata(), "sourceName"));
            if (StringUtils.hasText(sourceName) && normalizedQuery.contains(sourceName)) {
                return true;
            }
        }

        String searchableText = normalize(extractRetrievableTitle(result.getMetadata()) + " " + result.getContent());
        for (String token : normalizedQuery.split("[^a-z0-9_]+")) {
            if (token.length() >= TECHNICAL_ANCHOR_MIN_LENGTH && searchableText.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private Integer pageNumber(String normalizedQuery) {
        Matcher matcher = PAGE_REFERENCE_PATTERN.matcher(normalizedQuery);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }

    private List<RagRetrievalResult> retrieveCurrentFlat(
            List<String> kbIds,
            QueryRewriteResult rewritten,
            int limit
    ) {
        String originalQuery = rewritten.getQuery();
        String normalizedOriginalQuery = normalize(originalQuery);
        List<String> retrievalQueries = disableQueryExpansion
                ? List.of(originalQuery)
                : rewritten.getRetrievalQueries() == null || rewritten.getRetrievalQueries().isEmpty()
                ? List.of(originalQuery)
                : rewritten.getRetrievalQueries();
        List<String> retrievalQuerySources = rewritten.getRetrievalQuerySources() == null
                || rewritten.getRetrievalQuerySources().size() != retrievalQueries.size()
                ? Collections.nCopies(retrievalQueries.size(), "original")
                : rewritten.getRetrievalQuerySources();
        Map<String, String> embeddingLiteralCache = new LinkedHashMap<>();

        int scopeMultiplier = scopeMultiplier(kbIds.size());
        int vectorCandidateLimit = scaledCandidateLimit(Math.max(limit, RERANK_CANDIDATE_LIMIT), scopeMultiplier);
        int titleMatchCandidateLimit = scaledCandidateLimit(TITLE_MATCH_CANDIDATE_LIMIT, scopeMultiplier);
        int titleContainsCandidateLimit = scaledCandidateLimit(TITLE_CONTAINS_CANDIDATE_LIMIT, scopeMultiplier);
        int titleKeywordCandidateLimit = scaledCandidateLimit(TITLE_KEYWORD_CANDIDATE_LIMIT, scopeMultiplier);
        int titleTrigramCandidateLimit = scaledCandidateLimit(TITLE_TRIGRAM_CANDIDATE_LIMIT, scopeMultiplier);
        int titleFullTextCandidateLimit = scaledCandidateLimit(TITLE_FULL_TEXT_CANDIDATE_LIMIT, scopeMultiplier);
        int contentFullTextCandidateLimit = scaledCandidateLimit(CONTENT_FULL_TEXT_CANDIDATE_LIMIT, scopeMultiplier);

        RetrievalContext normalizedContext = normalizeContext(rewritten.getContext(), rewritten.getContextApplyMode());
        List<String> candidateKbIds = scopedKbIds(kbIds, normalizedContext);
        if (candidateKbIds.isEmpty()) {
            return List.of();
        }
        List<RetrievalChannel> channels = new ArrayList<>();
        for (int i = 0; i < retrievalQueries.size(); i++) {
            String retrievalQuery = retrievalQueries.get(i);
            String queryEmbedding = embeddingLiteral(embeddingLiteralCache, retrievalQuery);
            channels.add(new RetrievalChannel(
                    "vector_" + retrievalQuerySources.get(i),
                    annotateVectorCandidates(findVectorCandidates(
                            candidateKbIds,
                            queryEmbedding,
                            normalizedContext,
                            vectorCandidateLimit
                    ))
            ));
        }
        String titleQueryEmbedding = embeddingLiteral(
                embeddingLiteralCache,
                StringUtils.hasText(originalQuery) ? originalQuery : normalizedOriginalQuery
        );
        List<RagRetrievalResult> titleCandidates = rewritten.isTitleQuery()
                ? findTitleExactCandidates(
                candidateKbIds,
                normalizedOriginalQuery,
                titleQueryEmbedding,
                normalizedContext,
                titleMatchCandidateLimit
        )
                : List.of();
        List<RagRetrievalResult> titleContainsCandidates = rewritten.isTitleQuery()
                ? findTitleContainsCandidates(
                candidateKbIds,
                normalizedOriginalQuery,
                normalizedContext,
                titleContainsCandidateLimit
        )
                : List.of();
        List<RagRetrievalResult> titleKeywordCandidates = rewritten.isTitleQuery()
                ? findTitleKeywordCandidates(
                candidateKbIds,
                normalizedOriginalQuery,
                normalizedContext,
                titleKeywordCandidateLimit
        )
                : List.of();
        List<RagRetrievalResult> titleTrigramCandidates = rewritten.isTitleQuery()
                ? findTitleTrigramCandidates(
                candidateKbIds,
                normalizedOriginalQuery,
                normalizedContext,
                titleTrigramCandidateLimit
        )
                : List.of();
        List<RagRetrievalResult> titleBm25Candidates = rewritten.isTitleQuery()
                ? findTitleBm25Candidates(candidateKbIds, normalizedOriginalQuery, normalizedContext, titleFullTextCandidateLimit)
                : List.of();
        List<RagRetrievalResult> contentBm25Candidates = findContentBm25Candidates(
                candidateKbIds,
                normalizedOriginalQuery,
                normalizedContext,
                contentFullTextCandidateLimit
        );

        addChannel(channels, new RetrievalChannel("title_exact", titleCandidates));
        addChannel(channels, new RetrievalChannel("title_contains", titleContainsCandidates));
        addChannel(channels, new RetrievalChannel("title_keyword", titleKeywordCandidates));
        addChannel(channels, new RetrievalChannel("title_trigram", titleTrigramCandidates));
        addChannel(channels, new RetrievalChannel("title_bm25", titleBm25Candidates));
        addChannel(channels, new RetrievalChannel("content_bm25", contentBm25Candidates));

        List<RagRetrievalResult> candidates = rrfFuse(channels);
        if (normalizedContext.applyMode() == QueryRewriteResult.ContextApplyMode.HARD) {
            candidates = filterByContext(candidates, normalizedContext);
        }
        List<RagRetrievalResult> finalResults = disableRerank
                ? candidates
                : rerank(normalizedOriginalQuery, normalizedContext, candidates);
        return finalResults.stream()
                .limit(limit)
                .toList();
    }

    private List<RagRetrievalResult> findVectorCandidates(
            List<String> kbIds,
            String queryEmbedding,
            RetrievalContext context,
            int candidateLimit
    ) {
        if (context.applyMode() == QueryRewriteResult.ContextApplyMode.HARD && context.hasContext()) {
            return chunkBgeM3Mapper.similaritySearchDetailedWithContext(
                    kbIds,
                    queryEmbedding,
                    context.sourceName(),
                    context.sourceType(),
                    context.contentPathPrefix(),
                    candidateLimit
            );
        }
        return chunkBgeM3Mapper.similaritySearchDetailed(kbIds, queryEmbedding, candidateLimit);
    }

    private List<RagRetrievalResult> findTitleExactCandidates(
            List<String> kbIds,
            String normalizedQuery,
            String queryEmbedding,
            RetrievalContext context,
            int candidateLimit
    ) {
        if (!shouldTryTitleExactLookup(normalizedQuery) || !StringUtils.hasText(queryEmbedding)) {
            return List.of();
        }
        if (context.applyMode() == QueryRewriteResult.ContextApplyMode.HARD && context.hasContext()) {
            return chunkBgeM3Mapper.searchByTitleExactWithContext(
                    kbIds,
                    normalizedQuery,
                    queryEmbedding,
                    context.sourceName(),
                    context.sourceType(),
                    context.contentPathPrefix(),
                    candidateLimit
            );
        }
        return chunkBgeM3Mapper.searchByTitleExact(
                kbIds,
                normalizedQuery,
                queryEmbedding,
                candidateLimit
        );
    }

    private List<RagRetrievalResult> findTitleContainsCandidates(
            List<String> kbIds,
            String normalizedQuery,
            RetrievalContext context,
            int candidateLimit
    ) {
        if (!shouldTryTitleContainsLookup(normalizedQuery)) {
            return List.of();
        }
        String containsPattern = "%" + normalizedQuery + "%";
        if (context.applyMode() == QueryRewriteResult.ContextApplyMode.HARD && context.hasContext()) {
            return chunkBgeM3Mapper.searchByTitleContainsWithContext(
                    kbIds,
                    normalizedQuery,
                    containsPattern,
                    context.sourceName(),
                    context.sourceType(),
                    context.contentPathPrefix(),
                    candidateLimit
            );
        }
        return chunkBgeM3Mapper.searchByTitleContains(
                kbIds,
                normalizedQuery,
                containsPattern,
                candidateLimit
        );
    }

    private List<RagRetrievalResult> findTitleKeywordCandidates(
            List<String> kbIds,
            String normalizedQuery,
            RetrievalContext context,
            int candidateLimit
    ) {
        if (!shouldTryTitleKeywordLookup(normalizedQuery)) {
            return List.of();
        }
        List<String> keywords = buildTitleKeywords(normalizedQuery);
        if (keywords.isEmpty()) {
            return List.of();
        }
        if (context.applyMode() == QueryRewriteResult.ContextApplyMode.HARD && context.hasContext()) {
            return chunkBgeM3Mapper.searchByTitleKeywordsWithContext(
                    kbIds,
                    keywords,
                    normalizedQuery.length(),
                    context.sourceName(),
                    context.sourceType(),
                    context.contentPathPrefix(),
                    candidateLimit
            );
        }
        return chunkBgeM3Mapper.searchByTitleKeywords(
                kbIds,
                keywords,
                normalizedQuery.length(),
                candidateLimit
        );
    }

    private List<RagRetrievalResult> findTitleTrigramCandidates(
            List<String> kbIds,
            String normalizedQuery,
            RetrievalContext context,
            int candidateLimit
    ) {
        if (!shouldTryTitleTrigramLookup(normalizedQuery)) {
            return List.of();
        }
        if (context.applyMode() == QueryRewriteResult.ContextApplyMode.HARD && context.hasContext()) {
            return chunkBgeM3Mapper.searchByTitleTrigramWithContext(
                    kbIds,
                    normalizedQuery,
                    TITLE_TRIGRAM_MIN_SCORE,
                    context.sourceName(),
                    context.sourceType(),
                    context.contentPathPrefix(),
                    candidateLimit
            );
        }
        return chunkBgeM3Mapper.searchByTitleTrigram(
                kbIds,
                normalizedQuery,
                TITLE_TRIGRAM_MIN_SCORE,
                candidateLimit
        );
    }

    private List<RagRetrievalResult> findTitleBm25Candidates(
            List<String> kbIds,
            String normalizedQuery,
            RetrievalContext context,
            int candidateLimit
    ) {
        if (!shouldTryTitleFullTextLookup(normalizedQuery)) {
            return List.of();
        }
        return vchordBm25QueryService.searchTitle(
                kbIds,
                normalizedQuery,
                hardContextValue(context, RetrievalContext::sourceName),
                hardContextValue(context, RetrievalContext::sourceType),
                hardContextValue(context, RetrievalContext::contentPathPrefix),
                candidateLimit
        );
    }

    private List<RagRetrievalResult> findContentBm25Candidates(
            List<String> kbIds,
            String normalizedQuery,
            RetrievalContext context,
            int candidateLimit
    ) {
        if (!StringUtils.hasText(normalizedQuery)) {
            return List.of();
        }
        return vchordBm25QueryService.searchContent(
                kbIds,
                normalizedQuery,
                hardContextValue(context, RetrievalContext::sourceName),
                hardContextValue(context, RetrievalContext::sourceType),
                hardContextValue(context, RetrievalContext::contentPathPrefix),
                candidateLimit
        );
    }

    private boolean shouldTryTitleExactLookup(String normalizedQuery) {
        return StringUtils.hasText(normalizedQuery);
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

    private String embeddingLiteral(Map<String, String> embeddingLiteralCache, String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        return embeddingLiteralCache.computeIfAbsent(text, key -> toPgVector(doEmbed(key)));
    }

    private List<RagRetrievalResult> annotateVectorCandidates(List<RagRetrievalResult> candidates) {
        for (RagRetrievalResult candidate : candidates) {
            candidate.setVectorRank(candidate.getRank());
            candidate.setVectorDistance(candidate.getDistance());
        }
        return candidates;
    }

    private List<RagRetrievalResult> rrfFuse(List<RetrievalChannel> channels) {
        return rrfFuse(channels, true);
    }

    private List<RagRetrievalResult> rrfFuseByRank(List<RetrievalChannel> channels) {
        return rrfFuse(channels, false);
    }

    private List<RagRetrievalResult> rrfFuse(
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
            comparator = comparator.thenComparing(this::primaryDistance, Comparator.nullsLast(Double::compareTo));
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

    private List<RetrievalChannel> collapseSameSourceChannels(List<RetrievalChannel> channels) {
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

    private LinkedHashSet<String> retrievalProvenance(RagRetrievalResult result, String fallback) {
        LinkedHashSet<String> provenance = new LinkedHashSet<>();
        if (result.getRetrievalProvenance() != null) {
            provenance.addAll(result.getRetrievalProvenance());
        }
        if (provenance.isEmpty()) {
            provenance.add(fallback);
        }
        return provenance;
    }

    private List<RagRetrievalResult> retrieveExpandedQueryBranch(
            QueryRewriteResult rewritten,
            String originalQuery,
            String normalizedOriginalQuery,
            List<String> kbIds,
            RetrievalContext context,
            Map<String, String> embeddingLiteralCache,
            int vectorCandidateLimit,
            int titleMatchCandidateLimit,
            int titleContainsCandidateLimit,
            int titleKeywordCandidateLimit,
            int titleTrigramCandidateLimit,
            int titleFullTextCandidateLimit,
            int contentFullTextCandidateLimit,
            int branchCandidateBudget
    ) {
        List<RetrievalChannel> queryBranches = new ArrayList<>();
        List<ExpandedQuery> expandedQueries = expandedQueries(rewritten, originalQuery, normalizedOriginalQuery);
        for (int i = 0; i < expandedQueries.size(); i++) {
            ExpandedQuery expandedQuery = expandedQueries.get(i);
            String normalizedQuery = normalize(expandedQuery.query());
            String queryEmbedding = embeddingLiteral(embeddingLiteralCache, expandedQuery.query());
            List<RetrievalChannel> queryChannels = new ArrayList<>();
            addChannel(queryChannels, retrievalChannel(
                    "expanded-query",
                    "vector",
                    expandedQuery.source(),
                    findVectorCandidates(kbIds, queryEmbedding, context, vectorCandidateLimit),
                    true
            ));
            queryChannels.addAll(sparseChannels(
                    "expanded-query",
                    expandedQuery.source(),
                    kbIds,
                    normalizedQuery,
                    queryEmbedding,
                    rewritten.isTitleQuery(),
                    context,
                    titleMatchCandidateLimit,
                    titleContainsCandidateLimit,
                    titleKeywordCandidateLimit,
                    titleTrigramCandidateLimit,
                    titleFullTextCandidateLimit,
                    contentFullTextCandidateLimit
            ));
            List<RagRetrievalResult> queryResults = fuseBranch(queryChannels);
            addBranch(queryBranches, "expanded-query-query-" + i, queryResults);
        }
        return fuseBranch(queryBranches, branchCandidateBudget);
    }

    private List<ExpandedQuery> expandedQueries(
            QueryRewriteResult rewritten,
            String originalQuery,
            String normalizedOriginalQuery
    ) {
        if (disableQueryExpansion
                || rewritten.getRetrievalQueries() == null
                || rewritten.getRetrievalQueries().isEmpty()
                || rewritten.getRetrievalQuerySources() == null
                || rewritten.getRetrievalQuerySources().size() != rewritten.getRetrievalQueries().size()) {
            return List.of();
        }
        Map<String, ExpandedQuery> uniqueQueries = new LinkedHashMap<>();
        for (int i = 0; i < rewritten.getRetrievalQueries().size(); i++) {
            String query = rewritten.getRetrievalQueries().get(i);
            String source = rewritten.getRetrievalQuerySources().get(i);
            String normalizedQuery = normalize(query);
            if (!StringUtils.hasText(query)
                    || !isExpandedQuerySource(source)
                    || normalizedQuery.equals(normalizedOriginalQuery)
                    || query.equals(originalQuery)) {
                continue;
            }
            uniqueQueries.putIfAbsent(normalizedQuery, new ExpandedQuery(query, source.trim()));
        }
        return List.copyOf(uniqueQueries.values());
    }

    private boolean isExpandedQuerySource(String source) {
        return StringUtils.hasText(source)
                && ("standalone".equalsIgnoreCase(source.trim()) || "llm".equalsIgnoreCase(source.trim()));
    }

    private int sparseCandidateBudget(
            boolean titleQuery,
            int titleMatchCandidateLimit,
            int titleContainsCandidateLimit,
            int titleKeywordCandidateLimit,
            int titleTrigramCandidateLimit,
            int titleFullTextCandidateLimit,
            int contentFullTextCandidateLimit
    ) {
        if (!titleQuery) {
            return contentFullTextCandidateLimit;
        }
        return titleMatchCandidateLimit
                + titleContainsCandidateLimit
                + titleKeywordCandidateLimit
                + titleTrigramCandidateLimit
                + titleFullTextCandidateLimit
                + contentFullTextCandidateLimit;
    }

    private List<RetrievalChannel> sparseChannels(
            String branch,
            String querySource,
            List<String> kbIds,
            String normalizedQuery,
            String queryEmbedding,
            boolean titleQuery,
            RetrievalContext context,
            int titleMatchCandidateLimit,
            int titleContainsCandidateLimit,
            int titleKeywordCandidateLimit,
            int titleTrigramCandidateLimit,
            int titleFullTextCandidateLimit,
            int contentFullTextCandidateLimit
    ) {
        List<RetrievalChannel> channels = new ArrayList<>();
        if (titleQuery) {
            addChannel(channels, retrievalChannel(
                    branch,
                    "title-exact",
                    querySource,
                    findTitleExactCandidates(kbIds, normalizedQuery, queryEmbedding, context, titleMatchCandidateLimit),
                    false
            ));
            addChannel(channels, retrievalChannel(
                    branch,
                    "title-contains",
                    querySource,
                    findTitleContainsCandidates(kbIds, normalizedQuery, context, titleContainsCandidateLimit),
                    false
            ));
            addChannel(channels, retrievalChannel(
                    branch,
                    "title-keyword",
                    querySource,
                    findTitleKeywordCandidates(kbIds, normalizedQuery, context, titleKeywordCandidateLimit),
                    false
            ));
            addChannel(channels, retrievalChannel(
                    branch,
                    "title-trigram",
                    querySource,
                    findTitleTrigramCandidates(kbIds, normalizedQuery, context, titleTrigramCandidateLimit),
                    false
            ));
            addChannel(channels, retrievalChannel(
                    branch,
                    "title-bm25",
                    querySource,
                    findTitleBm25Candidates(kbIds, normalizedQuery, context, titleFullTextCandidateLimit),
                    false
            ));
        }
        addChannel(channels, retrievalChannel(
                branch,
                "content-bm25",
                querySource,
                findContentBm25Candidates(kbIds, normalizedQuery, context, contentFullTextCandidateLimit),
                false
        ));
        return channels;
    }

    private RetrievalChannel retrievalChannel(
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
                .map(this::copyResult)
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

    private void addChannel(List<RetrievalChannel> channels, RetrievalChannel channel) {
        if (!channel.results().isEmpty()) {
            channels.add(channel);
        }
    }

    private void addBranch(List<RetrievalChannel> branches, String branch, List<RagRetrievalResult> results) {
        if (!results.isEmpty()) {
            branches.add(new RetrievalChannel(branch, results));
        }
    }

    private List<RagRetrievalResult> fuseBranch(List<RetrievalChannel> channels) {
        return fuseBranch(channels, Integer.MAX_VALUE);
    }

    private List<RagRetrievalResult> fuseBranch(List<RetrievalChannel> channels, int candidateBudget) {
        List<RagRetrievalResult> results = rrfFuseByRank(channels);
        List<RagRetrievalResult> cappedResults = results.stream()
                .limit(candidateBudget)
                .toList();
        for (RagRetrievalResult result : cappedResults) {
            result.setRrfScore(null);
        }
        return cappedResults;
    }

    private String retrievalGroup(String channelName) {
        if (channelName.startsWith("vector_")) {
            return "vector";
        }
        if (channelName.startsWith("title_")) {
            return "title";
        }
        return channelName;
    }

    private RagRetrievalResult copyResult(RagRetrievalResult source) {
        RagRetrievalResult copy = new RagRetrievalResult();
        copy.setChunkId(source.getChunkId());
        copy.setKbId(source.getKbId());
        copy.setDocId(source.getDocId());
        copy.setContent(source.getContent());
        copy.setMetadata(source.getMetadata());
        copy.setDistance(source.getDistance());
        copy.setRank(source.getRank());
        copy.setRrfScore(source.getRrfScore());
        copy.setVectorRank(source.getVectorRank());
        copy.setVectorDistance(source.getVectorDistance());
        copy.setTitleBm25Rank(source.getTitleBm25Rank());
        copy.setTitleBm25Score(source.getTitleBm25Score());
        copy.setContentBm25Rank(source.getContentBm25Rank());
        copy.setContentBm25Score(source.getContentBm25Score());
        copy.setRerankScore(source.getRerankScore());
        copy.setRetrievalProvenance(source.getRetrievalProvenance() == null
                ? null
                : List.copyOf(source.getRetrievalProvenance()));
        return copy;
    }

    private void mergeSignals(RagRetrievalResult merged, RagRetrievalResult incoming) {
        if (incoming.getRetrievalProvenance() != null) {
            LinkedHashSet<String> provenance = new LinkedHashSet<>();
            if (merged.getRetrievalProvenance() != null) {
                provenance.addAll(merged.getRetrievalProvenance());
            }
            provenance.addAll(incoming.getRetrievalProvenance());
            merged.setRetrievalProvenance(List.copyOf(provenance));
        }
        if (!StringUtils.hasText(merged.getContent()) && StringUtils.hasText(incoming.getContent())) {
            merged.setContent(incoming.getContent());
        }
        if (!StringUtils.hasText(merged.getMetadata()) && StringUtils.hasText(incoming.getMetadata())) {
            merged.setMetadata(incoming.getMetadata());
        }
        if (incoming.getVectorRank() != null
                && (merged.getVectorRank() == null || incoming.getVectorRank() < merged.getVectorRank())) {
            merged.setVectorRank(incoming.getVectorRank());
            merged.setVectorDistance(incoming.getVectorDistance());
            merged.setDistance(incoming.getVectorDistance());
        }
        if (incoming.getTitleBm25Rank() != null
                && (merged.getTitleBm25Rank() == null || incoming.getTitleBm25Rank() < merged.getTitleBm25Rank())) {
            merged.setTitleBm25Rank(incoming.getTitleBm25Rank());
            merged.setTitleBm25Score(incoming.getTitleBm25Score());
        }
        if (incoming.getContentBm25Rank() != null
                && (merged.getContentBm25Rank() == null || incoming.getContentBm25Rank() < merged.getContentBm25Rank())) {
            merged.setContentBm25Rank(incoming.getContentBm25Rank());
            merged.setContentBm25Score(incoming.getContentBm25Score());
        }
        if (isBetterPrimaryDistance(primaryDistance(incoming), primaryDistance(merged))) {
            merged.setDistance(primaryDistance(incoming));
        }
    }

    private Double primaryDistance(RagRetrievalResult result) {
        if (result.getVectorDistance() != null) {
            return result.getVectorDistance();
        }
        return result.getDistance();
    }

    private boolean isBetterPrimaryDistance(Double candidate, Double existing) {
        if (candidate == null) {
            return false;
        }
        if (existing == null) {
            return true;
        }
        return candidate < existing;
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
        if (StringUtils.hasText(context.kbId()) && !context.kbId().equals(result.getKbId())) {
            return false;
        }

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

    private List<RagRetrievalResult> rerank(
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

    private Integer extractMetadataInt(String metadata, String fieldName) {
        if (!StringUtils.hasText(metadata)) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(metadata);
            JsonNode node = root.get(fieldName);
            return node != null && node.canConvertToInt() ? node.asInt() : null;
        } catch (JsonProcessingException e) {
            return null;
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

    private Set<String> terms(String text) {
        return new LinkedHashSet<>(RetrievableTitleLexicalizer.tokenize(text));
    }

    private int safeRank(Integer rank) {
        return rank == null || rank <= 0 ? Integer.MAX_VALUE : rank;
    }

    private String normalize(String text) {
        return RetrievableTitleLexicalizer.normalize(text);
    }

    private RetrievalContext normalizeContext(
            RagRetrievalContext context,
            QueryRewriteResult.ContextApplyMode contextApplyMode
    ) {
        if (context == null || !context.hasContext()) {
            return RetrievalContext.empty(contextApplyMode == null ? QueryRewriteResult.ContextApplyMode.NONE : contextApplyMode);
        }
        String kbId = StringUtils.hasText(context.getKbId()) ? context.getKbId().trim() : null;
        String sourceType = StringUtils.hasText(context.getSourceType()) ? context.getSourceType().trim() : null;
        String sourceName = StringUtils.hasText(context.getSourceName()) ? context.getSourceName().trim() : null;
        String normalizedSourceName = normalize(sourceName);
        String contentPath = StringUtils.hasText(context.getContentPath()) ? context.getContentPath().trim() : null;
        String contentPathPrefix = normalize(contentPath);
        return new RetrievalContext(
                kbId,
                sourceType,
                sourceName,
                StringUtils.hasText(normalizedSourceName) ? normalizedSourceName : null,
                contentPath,
                StringUtils.hasText(contentPathPrefix) ? contentPathPrefix : null,
                contextApplyMode == null ? QueryRewriteResult.ContextApplyMode.NONE : contextApplyMode
        );
    }

    private String toPgVector(float[] vector) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            sb.append(vector[i]);
            if (i < vector.length - 1) {
                sb.append(",");
            }
        }
        sb.append("]");
        return sb.toString();
    }

    private List<String> sanitizeKbIds(List<String> kbIds) {
        if (CollectionUtils.isEmpty(kbIds)) {
            return List.of();
        }
        return kbIds.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
    }

    private int scopeMultiplier(int kbCount) {
        return Math.min(Math.max(kbCount, 1), MAX_SCOPE_MULTIPLIER);
    }

    private int scaledCandidateLimit(int baseLimit, int scopeMultiplier) {
        return Math.max(baseLimit, 1) * scopeMultiplier;
    }

    private List<String> scopedKbIds(List<String> kbIds, RetrievalContext context) {
        if (context.applyMode() != QueryRewriteResult.ContextApplyMode.HARD || !StringUtils.hasText(context.kbId())) {
            return kbIds;
        }
        return kbIds.contains(context.kbId()) ? List.of(context.kbId()) : List.of();
    }

    private String hardContextValue(
            RetrievalContext context,
            Function<RetrievalContext, String> field
    ) {
        return context.applyMode() == QueryRewriteResult.ContextApplyMode.HARD ? field.apply(context) : null;
    }

    @FunctionalInterface
    private interface AssetCandidateSearcher {
        List<RagRetrievalResult> search(
                List<String> kbIds,
                String vectorLiteral,
                String sourceName,
                String sourceType,
                String contentPathPrefix,
                int limit
        );
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

    private record RetrievalChannel(
            String name,
            List<RagRetrievalResult> results
    ) {
    }

    private record ExpandedQuery(
            String query,
            String source
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

    private record RetrievalContext(
            String kbId,
            String sourceType,
            String sourceName,
            String normalizedSourceName,
            String contentPath,
            String contentPathPrefix,
            QueryRewriteResult.ContextApplyMode applyMode
    ) {
        static RetrievalContext empty(QueryRewriteResult.ContextApplyMode applyMode) {
            return new RetrievalContext(null, null, null, null, null, null, applyMode);
        }

        boolean hasContext() {
            return StringUtils.hasText(kbId)
                    || StringUtils.hasText(sourceType)
                    || StringUtils.hasText(sourceName)
                    || StringUtils.hasText(contentPathPrefix);
        }
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
