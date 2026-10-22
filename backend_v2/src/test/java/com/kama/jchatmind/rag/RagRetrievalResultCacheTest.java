package com.kama.jchatmind.rag;

import com.kama.jchatmind.model.dto.RagRetrievalContext;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class RagRetrievalResultCacheTest {

    @Test
    void shouldEvictLeastRecentlyUsedEntriesAtCapacity() {
        RagRetrievalResultCache cache = new RagRetrievalResultCache(1);
        AtomicInteger loads = new AtomicInteger();
        RagRetrievalResultCache.Key firstKey = key("7", "kb-1", 1L, "query-1", "config-1");
        RagRetrievalResultCache.Key secondKey = key("7", "kb-1", 1L, "query-2", "config-1");

        cache.getOrCompute(firstKey, () -> loadResult(loads));
        cache.getOrCompute(secondKey, () -> loadResult(loads));
        cache.getOrCompute(firstKey, () -> loadResult(loads));

        assertThat(loads).hasValue(3);
    }

    @Test
    void shouldBypassCachingWhenCapacityIsZero() {
        RagRetrievalResultCache cache = new RagRetrievalResultCache(0);
        AtomicInteger loads = new AtomicInteger();
        RagRetrievalResultCache.Key key = key("7", "kb-1", 1L, "query", "config-1");

        cache.getOrCompute(key, () -> loadResult(loads));
        cache.getOrCompute(key, () -> loadResult(loads));

        assertThat(loads).hasValue(2);
    }

    @Test
    void shouldCopyMutableResultsReturnedFromCache() {
        RagRetrievalResultCache cache = new RagRetrievalResultCache(2);
        AtomicInteger loads = new AtomicInteger();
        RagRetrievalResultCache.Key key = key("7", "kb-1", 1L, "query", "config-1");

        List<RagRetrievalResult> first = cache.getOrCompute(key, () -> loadResult(loads));
        first.get(0).setContent("mutated by caller");
        List<RagRetrievalResult> second = cache.getOrCompute(key, () -> loadResult(loads));

        assertThat(second.get(0).getContent()).isEqualTo("result-1");
        assertThat(loads).hasValue(1);
    }

    @Test
    void shouldCanonicalizeKbScopeAndSeparateSecurityIndexAndConfigDimensions() {
        RagRetrievalResultCache.Key canonical = RagRetrievalResultCache.Key.of(
                "7",
                List.of("kb-2", "kb-1", "kb-1"),
                Map.of("kb-1", 4L, "kb-2", 9L),
                "query",
                null,
                "PRIVATE_RAG",
                "route-v1",
                3,
                "config-v1"
        );
        RagRetrievalResultCache.Key reordered = RagRetrievalResultCache.Key.of(
                "7",
                List.of("kb-1", "kb-2"),
                Map.of("kb-2", 9L, "kb-1", 4L),
                "query",
                null,
                "PRIVATE_RAG",
                "route-v1",
                3,
                "config-v1"
        );

        assertThat(canonical).isEqualTo(reordered);
        assertThat(key("8", "kb-1", 4L, "query", "config-v1")).isNotEqualTo(canonical);
        assertThat(key("7", "kb-1", 5L, "query", "config-v1")).isNotEqualTo(canonical);
        assertThat(key("7", "kb-1", 4L, "query", "config-v2")).isNotEqualTo(canonical);
        assertThat(RagRetrievalResultCache.Key.of(
                "7",
                List.of("kb-1"),
                Map.of("kb-1", 4L),
                "query",
                RagRetrievalContext.builder().kbId("kb-1").sourceName("source-a").build(),
                "PRIVATE_RAG",
                "route-v1",
                3,
                "config-v1"
        )).isNotEqualTo(RagRetrievalResultCache.Key.of(
                "7",
                List.of("kb-1"),
                Map.of("kb-1", 4L),
                "query",
                RagRetrievalContext.builder().kbId("kb-1").sourceName("source-b").build(),
                "PRIVATE_RAG",
                "route-v1",
                3,
                "config-v1"
        ));
    }

    private static RagRetrievalResultCache.Key key(
            String requesterId,
            String kbId,
            long revision,
            String query,
            String config
    ) {
        return RagRetrievalResultCache.Key.of(
                requesterId,
                List.of(kbId),
                Map.of(kbId, revision),
                query,
                null,
                "PRIVATE_RAG",
                "route-v1",
                3,
                config
        );
    }

    private static List<RagRetrievalResult> loadResult(AtomicInteger loads) {
        int call = loads.incrementAndGet();
        RagRetrievalResult result = new RagRetrievalResult();
        result.setContent("result-" + call);
        return List.of(result);
    }
}
