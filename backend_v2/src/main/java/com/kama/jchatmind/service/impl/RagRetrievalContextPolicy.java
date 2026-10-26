package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.model.dto.QueryRewriteResult;
import com.kama.jchatmind.model.dto.RagRetrievalContext;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import org.springframework.util.StringUtils;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.function.Function;

import static com.kama.jchatmind.service.impl.RagRetrievalMetadata.*;

final class RagRetrievalContextPolicy {
    static List<RagRetrievalResult> filterByContext(List<RagRetrievalResult> candidates, RetrievalContext context) {
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

    static boolean matchesContext(RagRetrievalResult result, RetrievalContext context) {
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

    static Set<String> terms(String text) {
        return new LinkedHashSet<>(RetrievableTitleLexicalizer.tokenize(text));
    }

    static String normalize(String text) {
        return RetrievableTitleLexicalizer.normalize(text);
    }

    static RetrievalContext normalizeContext(
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

    static List<String> scopedKbIds(List<String> kbIds, RetrievalContext context) {
        if (context.applyMode() != QueryRewriteResult.ContextApplyMode.HARD || !StringUtils.hasText(context.kbId())) {
            return kbIds;
        }
        return kbIds.contains(context.kbId()) ? List.of(context.kbId()) : List.of();
    }

    static String hardContextValue(
            RetrievalContext context,
            Function<RetrievalContext, String> field
    ) {
        return context.applyMode() == QueryRewriteResult.ContextApplyMode.HARD ? field.apply(context) : null;
    }

    record RetrievalContext(
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

}
