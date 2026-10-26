package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.dto.QueryRewriteResult;
import com.kama.jchatmind.model.dto.RagKbIndexRevision;
import com.kama.jchatmind.model.dto.RagRetrievalContext;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.service.QueryRewriteService;
import com.kama.jchatmind.service.RagService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.kama.jchatmind.service.impl.RagRetrievalSignals.*;
import static com.kama.jchatmind.service.impl.RagFusion.*;

import static com.kama.jchatmind.service.impl.RagRetrievalMetadata.*;
import static com.kama.jchatmind.service.impl.RagRetrievalContextPolicy.*;

import static com.kama.jchatmind.service.impl.RagReranker.RERANK_CANDIDATE_LIMIT;

@Service
public class RagServiceImpl implements RagService {
    private static final String CACHE_ALGORITHM_VERSION = "rag-retrieval-v1";
    private static final int TITLE_MATCH_CANDIDATE_LIMIT = 20;
    private static final int TITLE_CONTAINS_CANDIDATE_LIMIT = 10;
    private static final int TITLE_KEYWORD_CANDIDATE_LIMIT = 10;
    private static final int TITLE_TRIGRAM_CANDIDATE_LIMIT = 10;
    private static final int TITLE_FULL_TEXT_CANDIDATE_LIMIT = 10;
    private static final int CONTENT_FULL_TEXT_CANDIDATE_LIMIT = 20;
    private static final int MAX_SCOPE_MULTIPLIER = 5;
    private static final double TITLE_TRIGRAM_MIN_SCORE = 0.18D;
    private static final int TITLE_CONTAINS_MIN_QUERY_LENGTH = 2;
    private static final int TITLE_KEYWORD_MIN_LENGTH = 2;
    private static final int TITLE_KEYWORD_MAX_COUNT = 6;
    private static final int TECHNICAL_ANCHOR_MIN_LENGTH = 16;
    private static final Pattern PAGE_REFERENCE_PATTERN = Pattern.compile("第\\s*(\\d+)\\s*页");

    private final RagEmbeddingClient embeddingClient;
    private final RagReranker reranker;
    private final ChunkBgeM3Mapper chunkBgeM3Mapper;
    private final QueryRewriteService queryRewriteService;
    private final VchordBm25QueryService vchordBm25QueryService;
    private final BgeRerankerService bgeRerankerService;
    private final String embeddingModel;
    private final boolean rerankDebug;
    private final boolean disableQueryExpansion;
    private final boolean disableRerank;

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
        this.embeddingClient = new RagEmbeddingClient(builder, ollamaBaseUrl, embeddingModel, embeddingCacheMaxEntries);
        this.chunkBgeM3Mapper = chunkBgeM3Mapper;
        this.queryRewriteService = queryRewriteService;
        this.vchordBm25QueryService = vchordBm25QueryService;
        this.bgeRerankerService = bgeRerankerService;
        this.reranker = new RagReranker(bgeRerankerService, rerankDebug);
        this.embeddingModel = embeddingModel;
        this.rerankDebug = rerankDebug;
        this.disableQueryExpansion = disableQueryExpansion;
        this.disableRerank = disableRerank;
    }

    @Override
    public float[] embed(String text) {
        return embeddingClient.embed(text);
    }

    @Override
    public Map<String, Long> indexRevisions(List<String> kbIds) {
        List<String> effectiveKbIds = sanitizeKbIds(kbIds);
        if (effectiveKbIds.isEmpty()) {
            return Map.of();
        }

        Map<String, Long> revisions = new TreeMap<>();
        for (String kbId : effectiveKbIds) {
            revisions.put(kbId, 0L);
        }
        List<RagKbIndexRevision> storedRevisions = chunkBgeM3Mapper.selectKbIndexRevisions(effectiveKbIds);
        if (storedRevisions != null) {
            for (RagKbIndexRevision revision : storedRevisions) {
                if (revision != null && StringUtils.hasText(revision.getKbId()) && revision.getRevision() != null
                        && revisions.containsKey(revision.getKbId())) {
                    revisions.put(revision.getKbId(), revision.getRevision());
                }
            }
        }
        return Map.copyOf(revisions);
    }

    @Override
    public String retrievalConfigurationFingerprint() {
        return CACHE_ALGORITHM_VERSION + "|embeddingModel=" + embeddingModel
                + "|rerankDebug=" + rerankDebug
                + "|disableQueryExpansion=" + disableQueryExpansion
                + "|disableRerank=" + disableRerank
                + "|queryRewrite=" + queryRewriteService.cacheConfigurationFingerprint()
                + "|reranker=" + bgeRerankerService.cacheConfigurationFingerprint();
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
                : reranker.rerank(normalizedOriginalQuery, normalizedContext, candidates);

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
                : reranker.rerank(normalizedOriginalQuery, normalizedContext, candidates);
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
        return embeddingLiteralCache.computeIfAbsent(text, key -> toPgVector(embeddingClient.embed(key)));
    }

    private List<RagRetrievalResult> annotateVectorCandidates(List<RagRetrievalResult> candidates) {
        for (RagRetrievalResult candidate : candidates) {
            candidate.setVectorRank(candidate.getRank());
            candidate.setVectorDistance(candidate.getDistance());
        }
        return candidates;
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

    private record ExpandedQuery(
            String query,
            String source
    ) {
    }


}
