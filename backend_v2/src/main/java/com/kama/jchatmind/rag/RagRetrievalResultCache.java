package com.kama.jchatmind.rag;

import com.kama.jchatmind.model.dto.RagRetrievalContext;
import com.kama.jchatmind.model.dto.RagRetrievalResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

public final class RagRetrievalResultCache {

    public static final int DEFAULT_MAX_ENTRIES = 256;

    private final int maxEntries;
    private final Map<Key, List<RagRetrievalResult>> entries;

    public RagRetrievalResultCache(int maxEntries) {
        this.maxEntries = Math.max(maxEntries, 0);
        this.entries = this.maxEntries == 0
                ? Map.of()
                : Collections.synchronizedMap(new LinkedHashMap<>(this.maxEntries, 0.75F, true) {
                    @Override
                    protected boolean removeEldestEntry(Map.Entry<Key, List<RagRetrievalResult>> eldest) {
                        return size() > RagRetrievalResultCache.this.maxEntries;
                    }
                });
    }

    public List<RagRetrievalResult> getOrCompute(Key key, Supplier<List<RagRetrievalResult>> loader) {
        if (maxEntries == 0 || key == null || key.requesterId() == null) {
            return copyResults(loader.get());
        }

        List<RagRetrievalResult> cached = entries.get(key);
        if (cached != null) {
            return copyResults(cached);
        }

        List<RagRetrievalResult> loaded = loader.get();
        List<RagRetrievalResult> snapshot = copyResults(loaded);
        entries.put(key, snapshot);
        return copyResults(snapshot);
    }

    private List<RagRetrievalResult> copyResults(List<RagRetrievalResult> results) {
        if (results == null || results.isEmpty()) {
            return List.of();
        }
        List<RagRetrievalResult> copies = new ArrayList<>(results.size());
        for (RagRetrievalResult result : results) {
            copies.add(copyResult(result));
        }
        return List.copyOf(copies);
    }

    private RagRetrievalResult copyResult(RagRetrievalResult result) {
        if (result == null) {
            return null;
        }
        return new RagRetrievalResult(
                result.getChunkId(),
                result.getKbId(),
                result.getDocId(),
                result.getContent(),
                result.getMetadata(),
                result.getDistance(),
                result.getRank(),
                result.getRrfScore(),
                result.getVectorRank(),
                result.getVectorDistance(),
                result.getTitleBm25Rank(),
                result.getTitleBm25Score(),
                result.getContentBm25Rank(),
                result.getContentBm25Score(),
                result.getRerankScore(),
                result.getRetrievalProvenance() == null ? null : List.copyOf(result.getRetrievalProvenance())
        );
    }

    public record Key(
            String requesterId,
            List<String> knowledgeBaseIds,
            Map<String, Long> indexRevisions,
            String query,
            RetrievalContextKey context,
            String retrievalVariant,
            String routeFingerprint,
            int limit,
            String configurationFingerprint
    ) {
        public Key {
            requesterId = normalize(requesterId);
            knowledgeBaseIds = knowledgeBaseIds == null ? List.of() : knowledgeBaseIds.stream()
                    .filter(id -> id != null && !id.isBlank())
                    .map(String::trim)
                    .distinct()
                    .sorted()
                    .toList();
            indexRevisions = indexRevisions == null
                    ? Map.of()
                    : Collections.unmodifiableMap(new TreeMap<>(indexRevisions));
            query = query == null ? "" : query;
            retrievalVariant = normalize(retrievalVariant);
            routeFingerprint = routeFingerprint == null ? "" : routeFingerprint;
            configurationFingerprint = configurationFingerprint == null ? "" : configurationFingerprint;
        }

        public static Key of(
                String requesterId,
                List<String> knowledgeBaseIds,
                Map<String, Long> indexRevisions,
                String query,
                RagRetrievalContext context,
                String retrievalVariant,
                String routeFingerprint,
                int limit,
                String configurationFingerprint
        ) {
            return new Key(
                    requesterId,
                    knowledgeBaseIds,
                    indexRevisions,
                    query,
                    RetrievalContextKey.from(context),
                    retrievalVariant,
                    routeFingerprint,
                    limit,
                    configurationFingerprint
            );
        }

        private static String normalize(String value) {
            return value == null || value.isBlank() ? null : value.trim();
        }
    }

    public record RetrievalContextKey(
            String kbId,
            String sourceType,
            String sourceName,
            String contentPath,
            boolean sessionContextBias
    ) {
        private static RetrievalContextKey from(RagRetrievalContext context) {
            if (context == null) {
                return null;
            }
            return new RetrievalContextKey(
                    context.getKbId(),
                    context.getSourceType(),
                    context.getSourceName(),
                    context.getContentPath(),
                    context.isSessionContextBias()
            );
        }
    }
}
