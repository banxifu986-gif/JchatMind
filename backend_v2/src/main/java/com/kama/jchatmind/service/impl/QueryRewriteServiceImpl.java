package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.dto.QueryRewriteResult;
import com.kama.jchatmind.model.dto.RagRetrievalContext;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.service.QueryRewriteService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Slf4j
public class QueryRewriteServiceImpl implements QueryRewriteService {
    private static final int MIN_PATH_SUBSTRING_LENGTH = 4;
    private static final int TITLE_LOOKUP_MAX_QUERY_LENGTH = 80;
    private static final int TITLE_EXPANSION_MAX_QUERY_LENGTH = 24;
    private static final int TITLE_EXPANSION_MAX_TERM_COUNT = 12;
    private static final int LOW_INFO_QUERY_MAX_LENGTH = 18;
    private static final int LOW_INFO_QUERY_MAX_TERM_COUNT = 4;
    private static final int LLM_REWRITE_MAX_TERM_COUNT = 10;
    private static final int LLM_REWRITE_MAX_OUTPUT_LENGTH = 160;
    private static final double AUTO_CONTEXT_MIN_SCORE = 0.52D;
    private static final double AUTO_CONTEXT_MIN_SCORE_GAP = 0.12D;
    private static final double AUTO_CONTEXT_TITLE_WEIGHT = 0.55D;
    private static final double AUTO_CONTEXT_PATH_WEIGHT = 0.30D;
    private static final double AUTO_CONTEXT_SOURCE_WEIGHT = 0.15D;
    private static final Set<String> ANALYTICAL_MARKERS = Set.of(
            "为什么", "原理", "区别", "设计", "思路", "流程", "架构", "如何设计", "怎么设计", "tradeoff"
    );
    private static final Set<String> FOLLOW_UP_MARKERS = Set.of(
            "这个", "这个呢", "它", "它呢", "这里", "这一块", "这部分", "上面", "上一个", "刚才", "继续", "展开", "详细说说", "怎么说", "怎么展开", "怎么处理"
    );
    private static final String LLM_REWRITE_SYSTEM_PROMPT = """
            你负责把 RAG 检索 query 改写成更容易召回的独立查询。
            只输出一行改写后的 query，不要解释，不要加引号，不要分点。
            必须保留用户原始问题里的关键实体和意图。
            只能利用给定上下文补全省略信息，不能编造上下文中没有的新事实。
            如果原问题已经足够完整，就原样输出。
            """;

    private final ChunkBgeM3Mapper chunkBgeM3Mapper;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final QueryRewriteLlmClient queryRewriteLlmClient;
    private final boolean llmRewriteEnabled;

    public QueryRewriteServiceImpl(ChunkBgeM3Mapper chunkBgeM3Mapper) {
        this(chunkBgeM3Mapper, null, false);
    }

    @Autowired
    public QueryRewriteServiceImpl(
            ChunkBgeM3Mapper chunkBgeM3Mapper,
            ObjectProvider<ChatClientRegistry> chatClientRegistryProvider,
            @Value("${rag.query-rewrite.llm.enabled:false}") boolean llmRewriteEnabled,
            @Value("${rag.query-rewrite.llm.model:deepseek-chat}") String llmRewriteModel
    ) {
        this(
                chunkBgeM3Mapper,
                createLlmClient(chatClientRegistryProvider.getIfAvailable(), llmRewriteModel),
                llmRewriteEnabled
        );
    }

    QueryRewriteServiceImpl(
            ChunkBgeM3Mapper chunkBgeM3Mapper,
            QueryRewriteLlmClient queryRewriteLlmClient,
            boolean llmRewriteEnabled
    ) {
        this.chunkBgeM3Mapper = chunkBgeM3Mapper;
        this.queryRewriteLlmClient = queryRewriteLlmClient;
        this.llmRewriteEnabled = llmRewriteEnabled;
    }

    @Override
    public QueryRewriteResult rewrite(List<String> kbIds, String query, RagRetrievalContext context) {
        String sanitizedQuery = sanitizeQuery(query);
        RagRetrievalContext normalizedContext = normalizeContext(context);
        if (!StringUtils.hasText(sanitizedQuery)) {
            return QueryRewriteResult.builder()
                    .query("")
                    .context(normalizedContext)
                    .titleQuery(false)
                    .intent(QueryRewriteResult.Intent.FACTOID)
                    .contextApplyMode(QueryRewriteResult.ContextApplyMode.NONE)
                    .retrievalQueries(List.of())
                    .build();
        }

        String normalizedQuery = normalize(sanitizedQuery);
        QueryRewriteResult.Intent intent = detectIntent(normalizedQuery, normalizedContext);
        boolean titleQuery = shouldExpandTitleCandidates(sanitizedQuery, intent);

        RagRetrievalContext effectiveContext = normalizedContext;
        if (!effectiveContext.hasContext() && shouldTryAutoContextSelection(normalizedQuery)) {
            effectiveContext = selectContextFromTitlePathCandidates(kbIds, sanitizedQuery);
        }

        boolean topicSwitchSignal = hasTopicSwitchSignal(normalizedQuery, effectiveContext);
        QueryRewriteResult.ContextApplyMode contextApplyMode = decideContextApplyMode(
                intent,
                effectiveContext,
                topicSwitchSignal
        );
        effectiveContext = adjustContextForRewrite(effectiveContext, contextApplyMode, topicSwitchSignal);

        List<String> retrievalQueries = buildRetrievalQueries(
                sanitizedQuery,
                effectiveContext,
                intent,
                contextApplyMode,
                topicSwitchSignal
        );

        return QueryRewriteResult.builder()
                .query(sanitizedQuery)
                .context(effectiveContext)
                .titleQuery(titleQuery)
                .intent(intent)
                .contextApplyMode(contextApplyMode)
                .retrievalQueries(retrievalQueries)
                .build();
    }

    private QueryRewriteResult.Intent detectIntent(String normalizedQuery, RagRetrievalContext context) {
        if (shouldTryAutoContextSelection(normalizedQuery)) {
            return QueryRewriteResult.Intent.NAVIGATION;
        }
        if (containsAnalyticalMarker(normalizedQuery)) {
            return QueryRewriteResult.Intent.ANALYTICAL;
        }
        if (context != null && context.hasContext() && isLowInformationFollowUp(normalizedQuery)) {
            return QueryRewriteResult.Intent.FOLLOW_UP;
        }
        return QueryRewriteResult.Intent.FACTOID;
    }

    private QueryRewriteResult.ContextApplyMode decideContextApplyMode(
            QueryRewriteResult.Intent intent,
            RagRetrievalContext context,
            boolean topicSwitchSignal
    ) {
        if (context == null || !context.hasContext()) {
            return QueryRewriteResult.ContextApplyMode.NONE;
        }
        if (intent == QueryRewriteResult.Intent.NAVIGATION) {
            return topicSwitchSignal
                    ? QueryRewriteResult.ContextApplyMode.SOFT
                    : QueryRewriteResult.ContextApplyMode.HARD;
        }
        if (intent == QueryRewriteResult.Intent.FOLLOW_UP && !topicSwitchSignal) {
            return QueryRewriteResult.ContextApplyMode.HARD;
        }
        return QueryRewriteResult.ContextApplyMode.SOFT;
    }

    private List<String> buildRetrievalQueries(
            String sanitizedQuery,
            RagRetrievalContext context,
            QueryRewriteResult.Intent intent,
            QueryRewriteResult.ContextApplyMode contextApplyMode,
            boolean topicSwitchSignal
    ) {
        List<String> queries = new ArrayList<>();
        String llmRewrittenQuery = maybeRewriteWithLlm(
                sanitizedQuery,
                context,
                intent,
                contextApplyMode,
                topicSwitchSignal
        );
        if (StringUtils.hasText(llmRewrittenQuery)) {
            queries.add(llmRewrittenQuery);
        }
        if (intent == QueryRewriteResult.Intent.FOLLOW_UP
                && contextApplyMode == QueryRewriteResult.ContextApplyMode.HARD
                && context != null
                && context.hasContext()) {
            String standalone = buildStandaloneFollowUpQuery(context, sanitizedQuery);
            if (StringUtils.hasText(standalone)) {
                queries.add(standalone);
            }
        }
        queries.add(sanitizedQuery);
        return queries.stream()
                .filter(StringUtils::hasText)
                .map(this::sanitizeQuery)
                .distinct()
                .toList();
    }

    private String maybeRewriteWithLlm(
            String sanitizedQuery,
            RagRetrievalContext context,
            QueryRewriteResult.Intent intent,
            QueryRewriteResult.ContextApplyMode contextApplyMode,
            boolean topicSwitchSignal
    ) {
        if (!shouldUseLlmRewrite(sanitizedQuery, context, intent, contextApplyMode, topicSwitchSignal)) {
            return null;
        }
        try {
            String rewritten = queryRewriteLlmClient.rewrite(sanitizedQuery, context, intent);
            String sanitizedRewritten = sanitizeLlmRewrittenQuery(rewritten);
            if (!StringUtils.hasText(sanitizedRewritten)) {
                return null;
            }
            if (normalize(sanitizedRewritten).equals(normalize(sanitizedQuery))) {
                return null;
            }
            return sanitizedRewritten;
        } catch (Exception e) {
            log.warn("LLM query rewrite failed, fallback to rule-based rewrite", e);
            return null;
        }
    }

    private boolean shouldUseLlmRewrite(
            String sanitizedQuery,
            RagRetrievalContext context,
            QueryRewriteResult.Intent intent,
            QueryRewriteResult.ContextApplyMode contextApplyMode,
            boolean topicSwitchSignal
    ) {
        if (!llmRewriteEnabled || queryRewriteLlmClient == null || !StringUtils.hasText(sanitizedQuery)) {
            return false;
        }
        if (context == null || !context.hasContext() || topicSwitchSignal) {
            return false;
        }
        if (intent == QueryRewriteResult.Intent.FOLLOW_UP) {
            return contextApplyMode == QueryRewriteResult.ContextApplyMode.HARD;
        }
        if (intent != QueryRewriteResult.Intent.ANALYTICAL
                || contextApplyMode == QueryRewriteResult.ContextApplyMode.NONE) {
            return false;
        }
        String normalizedQuery = normalize(sanitizedQuery);
        return !isPathAwareQuery(normalizedQuery)
                && terms(normalizedQuery).size() <= LLM_REWRITE_MAX_TERM_COUNT;
    }

    private RagRetrievalContext adjustContextForRewrite(
            RagRetrievalContext context,
            QueryRewriteResult.ContextApplyMode contextApplyMode,
            boolean topicSwitchSignal
    ) {
        if (context == null || !context.hasContext()) {
            return emptyContext();
        }
        if (!topicSwitchSignal || contextApplyMode == QueryRewriteResult.ContextApplyMode.HARD) {
            return context;
        }
        return RagRetrievalContext.builder()
                .kbId(context.getKbId())
                .sourceType(context.getSourceType())
                .sourceName(context.getSourceName())
                .build();
    }

    private String buildStandaloneFollowUpQuery(RagRetrievalContext context, String query) {
        List<String> parts = new ArrayList<>();
        if (StringUtils.hasText(context.getSourceName())) {
            parts.add(context.getSourceName().trim());
        }
        if (StringUtils.hasText(context.getContentPath())) {
            parts.add(context.getContentPath().trim());
        }
        if (StringUtils.hasText(query)) {
            parts.add(query.trim());
        }
        if (parts.isEmpty()) {
            return query;
        }
        return String.join(" ", parts);
    }

    private String sanitizeLlmRewrittenQuery(String rewrittenQuery) {
        if (!StringUtils.hasText(rewrittenQuery)) {
            return null;
        }
        String sanitized = sanitizeQuery(rewrittenQuery)
                .replaceFirst("^(改写后的?查询|改写后的?问题|改写|query)[:：]\\s*", "")
                .replaceAll("^[\"'“”‘’]+|[\"'“”‘’]+$", "");
        if (!StringUtils.hasText(sanitized) || sanitized.length() > LLM_REWRITE_MAX_OUTPUT_LENGTH) {
            return null;
        }
        return sanitized;
    }

    private boolean containsAnalyticalMarker(String normalizedQuery) {
        return ANALYTICAL_MARKERS.stream().anyMatch(normalizedQuery::contains);
    }

    private boolean isLowInformationFollowUp(String normalizedQuery) {
        if (!StringUtils.hasText(normalizedQuery)) {
            return false;
        }
        if (FOLLOW_UP_MARKERS.stream().anyMatch(normalizedQuery::contains)) {
            return true;
        }
        Set<String> queryTerms = terms(normalizedQuery);
        return normalizedQuery.length() <= LOW_INFO_QUERY_MAX_LENGTH
                && !isPathAwareQuery(normalizedQuery)
                && queryTerms.size() <= LOW_INFO_QUERY_MAX_TERM_COUNT;
    }

    private boolean hasTopicSwitchSignal(String normalizedQuery, RagRetrievalContext context) {
        if (!StringUtils.hasText(normalizedQuery) || context == null || !context.hasContext()) {
            return false;
        }
        if (shouldTryAutoContextSelection(normalizedQuery)) {
            String normalizedContentPath = normalize(context.getContentPath());
            if (StringUtils.hasText(normalizedContentPath)) {
                return !isSamePathBranch(normalizedQuery, normalizedContentPath);
            }
            return !matchesContextSignal(normalizedQuery, normalize(context.getSourceName()));
        }
        Set<String> queryTerms = terms(normalizedQuery);
        if (queryTerms.isEmpty()) {
            return false;
        }
        double pathOverlap = overlapRatio(queryTerms, terms(normalize(context.getContentPath())));
        double sourceOverlap = overlapRatio(queryTerms, terms(normalize(context.getSourceName())));
        return pathOverlap == 0D
                && sourceOverlap == 0D
                && !FOLLOW_UP_MARKERS.stream().anyMatch(normalizedQuery::contains);
    }

    private boolean isSamePathBranch(String normalizedQuery, String normalizedContextPath) {
        if (!StringUtils.hasText(normalizedQuery) || !StringUtils.hasText(normalizedContextPath)) {
            return false;
        }
        List<String> querySegments = pathSegments(normalizedQuery);
        List<String> contextSegments = pathSegments(normalizedContextPath);
        if (querySegments.isEmpty() || contextSegments.isEmpty()) {
            return containsMeaningfulPath(normalizedQuery, normalizedContextPath)
                    || containsMeaningfulPath(normalizedContextPath, normalizedQuery);
        }
        int shared = 0;
        int limit = Math.min(querySegments.size(), contextSegments.size());
        while (shared < limit && querySegments.get(shared).equals(contextSegments.get(shared))) {
            shared++;
        }
        return shared == contextSegments.size() || shared == querySegments.size();
    }

    private List<String> pathSegments(String text) {
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        return List.of(text.split(">")).stream()
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
    }

    private boolean matchesContextSignal(String normalizedQuery, String normalizedContextText) {
        if (!StringUtils.hasText(normalizedQuery) || !StringUtils.hasText(normalizedContextText)) {
            return false;
        }
        return containsMeaningfulPath(normalizedQuery, normalizedContextText)
                || containsMeaningfulPath(normalizedContextText, normalizedQuery)
                || overlapRatio(terms(normalizedQuery), terms(normalizedContextText)) > 0D;
    }

    private RagRetrievalContext selectContextFromTitlePathCandidates(List<String> kbIds, String query) {
        String normalizedQuery = normalize(query);
        if (CollectionUtils.isEmpty(kbIds)
                || !StringUtils.hasText(normalizedQuery)
                || normalizedQuery.length() > TITLE_LOOKUP_MAX_QUERY_LENGTH
                || !shouldTryAutoContextSelection(normalizedQuery)) {
            return emptyContext();
        }

        Set<String> queryTerms = terms(normalizedQuery);
        if (queryTerms.isEmpty()) {
            return emptyContext();
        }

        List<RagRetrievalResult> candidates = chunkBgeM3Mapper.selectTitlePathCandidatesByKbIds(kbIds);
        if (candidates.isEmpty()) {
            return emptyContext();
        }

        Map<String, ScoredContextCandidate> bestCandidateByContext = new LinkedHashMap<>();
        for (RagRetrievalResult candidate : candidates) {
            ScoredContextCandidate scoredCandidate = scoreContextCandidate(normalizedQuery, queryTerms, candidate);
            if (scoredCandidate.score() <= 0D || !scoredCandidate.context().hasContext()) {
                continue;
            }

            String contextKey = contextKey(scoredCandidate.context());
            ScoredContextCandidate current = bestCandidateByContext.get(contextKey);
            if (current == null || scoredCandidate.score() > current.score()) {
                bestCandidateByContext.put(contextKey, scoredCandidate);
            }
        }

        List<ScoredContextCandidate> scoredCandidates = bestCandidateByContext.values().stream()
                .sorted(Comparator
                        .comparingDouble(ScoredContextCandidate::score)
                        .reversed()
                        .thenComparing(candidate -> candidate.context().getContentPath()))
                .toList();
        if (scoredCandidates.isEmpty()) {
            return emptyContext();
        }

        ScoredContextCandidate best = scoredCandidates.get(0);
        double nextScore = scoredCandidates.size() > 1 ? scoredCandidates.get(1).score() : 0D;
        if (best.score() < AUTO_CONTEXT_MIN_SCORE || best.score() - nextScore < AUTO_CONTEXT_MIN_SCORE_GAP) {
            return emptyContext();
        }
        return best.context();
    }

    private ScoredContextCandidate scoreContextCandidate(
            String normalizedQuery,
            Set<String> queryTerms,
            RagRetrievalResult candidate
    ) {
        String metadata = candidate.getMetadata();
        String title = normalize(extractRetrievableTitle(metadata));
        String contentPath = extractMetadataText(metadata, "contentPath");
        String normalizedContentPath = normalize(contentPath);
        String parentContentPath = parentContentPath(contentPath);
        String normalizedParentContentPath = normalize(parentContentPath);
        String sourceName = extractMetadataText(metadata, "sourceName");
        String normalizedSourceName = normalize(sourceName);
        String sourceType = extractMetadataText(metadata, "sourceType");

        double titleScore = title.equals(normalizedQuery)
                ? 1D
                : overlapRatio(queryTerms, terms(title));
        double pathScore = scoreParentPath(normalizedQuery, queryTerms, normalizedContentPath, normalizedParentContentPath);
        double sourceScore = overlapRatio(queryTerms, terms(normalizedSourceName));
        double score = titleScore * AUTO_CONTEXT_TITLE_WEIGHT
                + pathScore * AUTO_CONTEXT_PATH_WEIGHT
                + sourceScore * AUTO_CONTEXT_SOURCE_WEIGHT;

        RagRetrievalContext context = RagRetrievalContext.builder()
                .kbId(StringUtils.hasText(candidate.getKbId()) ? candidate.getKbId() : null)
                .sourceType(StringUtils.hasText(sourceType) ? sourceType : null)
                .sourceName(StringUtils.hasText(sourceName) ? sourceName : null)
                .contentPath(StringUtils.hasText(parentContentPath) ? parentContentPath : contentPath)
                .build();
        return new ScoredContextCandidate(context, score);
    }

    private double scoreParentPath(
            String normalizedQuery,
            Set<String> queryTerms,
            String normalizedContentPath,
            String normalizedParentContentPath
    ) {
        if (StringUtils.hasText(normalizedParentContentPath)) {
            if (containsMeaningfulPath(normalizedQuery, normalizedParentContentPath)
                    || containsMeaningfulPath(normalizedParentContentPath, normalizedQuery)) {
                return 1D;
            }
            return overlapRatio(queryTerms, terms(normalizedParentContentPath));
        }
        if (!StringUtils.hasText(normalizedContentPath)) {
            return 0D;
        }
        return overlapRatio(queryTerms, terms(normalizedContentPath));
    }

    private boolean shouldTryAutoContextSelection(String normalizedQuery) {
        if (!StringUtils.hasText(normalizedQuery)) {
            return false;
        }
        return isPathAwareQuery(normalizedQuery)
                || normalizedQuery.contains(".md")
                || normalizedQuery.contains(".markdown");
    }

    private boolean shouldExpandTitleCandidates(String query, QueryRewriteResult.Intent intent) {
        String normalizedQuery = normalize(query);
        if (!StringUtils.hasText(normalizedQuery) || normalizedQuery.length() > TITLE_LOOKUP_MAX_QUERY_LENGTH) {
            return false;
        }
        boolean indexedHeading = looksLikeIndexedHeading(query);
        if (containsSentencePunctuation(normalizedQuery) && !indexedHeading) {
            return false;
        }
        if (intent == QueryRewriteResult.Intent.NAVIGATION) {
            return true;
        }
        if (indexedHeading) {
            return true;
        }
        if (intent == QueryRewriteResult.Intent.FOLLOW_UP) {
            return false;
        }
        Set<String> queryTerms = terms(normalizedQuery);
        return normalizedQuery.length() <= TITLE_EXPANSION_MAX_QUERY_LENGTH
                && !queryTerms.isEmpty()
                && queryTerms.size() <= TITLE_EXPANSION_MAX_TERM_COUNT;
    }

    private boolean looksLikeIndexedHeading(String query) {
        if (!StringUtils.hasText(query)) {
            return false;
        }
        String trimmed = query.trim();
        return trimmed.matches("^\\d+(?:\\.\\d+)*[.、]\\s*.+$")
                || trimmed.matches("^[一二三四五六七八九十]+、.+$");
    }

    private boolean containsSentencePunctuation(String normalizedQuery) {
        return normalizedQuery.contains("?")
                || normalizedQuery.contains("\uFF1F")
                || normalizedQuery.contains("\u3002")
                || normalizedQuery.contains("\uFF01")
                || normalizedQuery.contains(";")
                || normalizedQuery.contains("\uFF1B");
    }

    private boolean isPathAwareQuery(String normalizedQuery) {
        return normalizedQuery.contains(">")
                || normalizedQuery.contains("/")
                || normalizedQuery.contains("\\");
    }

    private boolean containsMeaningfulPath(String text, String query) {
        return StringUtils.hasText(text)
                && StringUtils.hasText(query)
                && query.length() >= MIN_PATH_SUBSTRING_LENGTH
                && text.contains(query);
    }

    private String sanitizeQuery(String query) {
        if (!StringUtils.hasText(query)) {
            return "";
        }
        return query.replace("\r", " ")
                .replace("\n", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private RagRetrievalContext normalizeContext(RagRetrievalContext context) {
        if (context == null || !context.hasContext()) {
            return emptyContext();
        }
        return RagRetrievalContext.builder()
                .kbId(trimToNull(context.getKbId()))
                .sourceType(trimToNull(context.getSourceType()))
                .sourceName(trimToNull(context.getSourceName()))
                .contentPath(trimToNull(context.getContentPath()))
                .build();
    }

    private RagRetrievalContext emptyContext() {
        return RagRetrievalContext.builder().build();
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String contextKey(RagRetrievalContext context) {
        return String.join(
                "|",
                context.getKbId() == null ? "" : context.getKbId(),
                context.getSourceType() == null ? "" : context.getSourceType(),
                context.getSourceName() == null ? "" : context.getSourceName(),
                context.getContentPath() == null ? "" : normalize(context.getContentPath())
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

    private String normalize(String text) {
        return RetrievableTitleLexicalizer.normalize(text);
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

    private static QueryRewriteLlmClient createLlmClient(ChatClientRegistry chatClientRegistry, String llmRewriteModel) {
        if (chatClientRegistry == null || !StringUtils.hasText(llmRewriteModel)) {
            return null;
        }
        ChatClient chatClient = chatClientRegistry.get(llmRewriteModel);
        if (chatClient == null) {
            return null;
        }
        return (query, context, intent) -> chatClient.prompt()
                .system(LLM_REWRITE_SYSTEM_PROMPT)
                .user(buildLlmRewriteUserPrompt(query, context, intent))
                .call()
                .content();
    }

    private static String buildLlmRewriteUserPrompt(
            String query,
            RagRetrievalContext context,
            QueryRewriteResult.Intent intent
    ) {
        List<String> lines = new ArrayList<>();
        lines.add("intent: " + intent.name());
        lines.add("query: " + query);
        lines.add("sourceName: " + nullSafeValue(context == null ? null : context.getSourceName()));
        lines.add("contentPath: " + nullSafeValue(context == null ? null : context.getContentPath()));
        lines.add("要求: 输出更适合检索的 standalone query。");
        if (intent == QueryRewriteResult.Intent.FOLLOW_UP) {
            lines.add("要求: 补全代词、省略信息，保留原问题语义。");
        }
        if (intent == QueryRewriteResult.Intent.ANALYTICAL) {
            lines.add("要求: 保留原理、流程、区别、设计等分析意图。");
        }
        return String.join("\n", lines);
    }

    private static String nullSafeValue(String value) {
        return StringUtils.hasText(value) ? value.trim() : "(empty)";
    }

    private record ScoredContextCandidate(
            RagRetrievalContext context,
            double score
    ) {
    }

    @FunctionalInterface
    interface QueryRewriteLlmClient {
        String rewrite(String query, RagRetrievalContext context, QueryRewriteResult.Intent intent);
    }
}
