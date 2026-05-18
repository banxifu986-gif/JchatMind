package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.dto.QueryRewriteResult;
import com.kama.jchatmind.model.dto.RagRetrievalContext;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.service.QueryRewriteService;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class QueryRewriteServiceImpl implements QueryRewriteService {
    private static final int MIN_PATH_SUBSTRING_LENGTH = 4;
    private static final int TITLE_LOOKUP_MAX_QUERY_LENGTH = 80;
    private static final int TITLE_EXPANSION_MAX_QUERY_LENGTH = 24;
    private static final int TITLE_EXPANSION_MAX_TERM_COUNT = 12;
    private static final double AUTO_CONTEXT_MIN_SCORE = 0.52D;
    private static final double AUTO_CONTEXT_MIN_SCORE_GAP = 0.12D;
    private static final double AUTO_CONTEXT_TITLE_WEIGHT = 0.55D;
    private static final double AUTO_CONTEXT_PATH_WEIGHT = 0.30D;
    private static final double AUTO_CONTEXT_SOURCE_WEIGHT = 0.15D;

    private final ChunkBgeM3Mapper chunkBgeM3Mapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public QueryRewriteServiceImpl(ChunkBgeM3Mapper chunkBgeM3Mapper) {
        this.chunkBgeM3Mapper = chunkBgeM3Mapper;
    }

    @Override
    public QueryRewriteResult rewrite(String kbId, String query, RagRetrievalContext context) {
        String sanitizedQuery = sanitizeQuery(query);
        RagRetrievalContext normalizedContext = normalizeContext(context);
        if (!StringUtils.hasText(sanitizedQuery)) {
            return QueryRewriteResult.builder()
                    .query("")
                    .context(normalizedContext)
                    .titleQuery(false)
                    .build();
        }

        if (normalizedContext.hasContext()) {
            return buildResult(sanitizedQuery, normalizedContext);
        }

        RagRetrievalContext selectedContext = selectContextFromTitlePathCandidates(kbId, sanitizedQuery);
        return buildResult(sanitizedQuery, selectedContext);
    }

    private QueryRewriteResult buildResult(String sanitizedQuery, RagRetrievalContext context) {
        return QueryRewriteResult.builder()
                .query(sanitizedQuery)
                .context(context)
                .titleQuery(shouldExpandTitleCandidates(sanitizedQuery))
                .build();
    }

    private RagRetrievalContext selectContextFromTitlePathCandidates(String kbId, String query) {
        String normalizedQuery = normalize(query);
        if (!StringUtils.hasText(kbId)
                || !StringUtils.hasText(normalizedQuery)
                || normalizedQuery.length() > TITLE_LOOKUP_MAX_QUERY_LENGTH
                || !shouldTryAutoContextSelection(normalizedQuery)) {
            return emptyContext();
        }

        Set<String> queryTerms = terms(normalizedQuery);
        if (queryTerms.isEmpty()) {
            return emptyContext();
        }

        List<RagRetrievalResult> candidates = chunkBgeM3Mapper.selectTitlePathCandidatesByKbId(kbId);
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

    private boolean shouldExpandTitleCandidates(String query) {
        String normalizedQuery = normalize(query);
        if (!StringUtils.hasText(normalizedQuery) || normalizedQuery.length() > TITLE_LOOKUP_MAX_QUERY_LENGTH) {
            return false;
        }
        if (containsSentencePunctuation(normalizedQuery)) {
            return false;
        }
        if (shouldTryAutoContextSelection(normalizedQuery)) {
            return true;
        }
        Set<String> queryTerms = terms(normalizedQuery);
        return normalizedQuery.length() <= TITLE_EXPANSION_MAX_QUERY_LENGTH
                && !queryTerms.isEmpty()
                && queryTerms.size() <= TITLE_EXPANSION_MAX_TERM_COUNT;
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

    private record ScoredContextCandidate(
            RagRetrievalContext context,
            double score
    ) {
    }
}
