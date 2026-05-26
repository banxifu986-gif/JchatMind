package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.dto.QueryRewriteResult;
import com.kama.jchatmind.model.dto.RagRetrievalContext;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryRewriteServiceImplTest {

    @Test
    void shouldKeepExplicitContextAndBuildFollowUpStandaloneQuery() {
        QueryRewriteServiceImpl service = new QueryRewriteServiceImpl(new StubChunkBgeM3Mapper(List.of()));

        RagRetrievalContext context = RagRetrievalContext.builder()
                .kbId("kb-1")
                .sourceName("resume.md")
                .contentPath("interview > answer")
                .build();

        QueryRewriteResult result = service.rewrite(List.of("kb-1"), "answer", context);

        assertEquals("answer", result.getQuery());
        assertNotNull(result.getContext());
        assertEquals("kb-1", result.getContext().getKbId());
        assertEquals("resume.md", result.getContext().getSourceName());
        assertEquals("interview > answer", result.getContext().getContentPath());
        assertEquals(QueryRewriteResult.Intent.FOLLOW_UP, result.getIntent());
        assertEquals(QueryRewriteResult.ContextApplyMode.HARD, result.getContextApplyMode());
        assertEquals(List.of("resume.md interview > answer answer", "answer"), result.getRetrievalQueries());
        assertFalse(result.isTitleQuery());
    }

    @Test
    void shouldNotResolveChatClientRegistryDuringConstruction() {
        AtomicInteger resolutionCount = new AtomicInteger();
        ObjectProvider<ChatClientRegistry> chatClientRegistryProvider = new CountingObjectProvider<>(resolutionCount, null);

        QueryRewriteServiceImpl service = new QueryRewriteServiceImpl(
                new StubChunkBgeM3Mapper(List.of()),
                chatClientRegistryProvider,
                false,
                "deepseek-chat"
        );

        assertNotNull(service);
        assertEquals(0, resolutionCount.get());
    }

    @Test
    void shouldNotResolveChatClientWhenLlmRewriteDisabled() {
        AtomicInteger resolutionCount = new AtomicInteger();
        ObjectProvider<ChatClientRegistry> chatClientRegistryProvider = new CountingObjectProvider<>(resolutionCount, null);

        QueryRewriteServiceImpl service = new QueryRewriteServiceImpl(
                new StubChunkBgeM3Mapper(List.of()),
                chatClientRegistryProvider,
                false,
                "deepseek-chat"
        );

        RagRetrievalContext context = RagRetrievalContext.builder()
                .kbId("kb-1")
                .sourceName("resume.md")
                .contentPath("interview > answer")
                .build();

        QueryRewriteResult result = service.rewrite(List.of("kb-1"), "answer", context);

        assertEquals(List.of("resume.md interview > answer answer", "answer"), result.getRetrievalQueries());
        assertEquals(0, resolutionCount.get());
    }

    @Test
    void shouldDowngradeToSoftContextWhenQueryShowsTopicSwitch() {
        QueryRewriteServiceImpl service = new QueryRewriteServiceImpl(new StubChunkBgeM3Mapper(List.of()));

        RagRetrievalContext context = RagRetrievalContext.builder()
                .kbId("kb-1")
                .sourceName("resume.md")
                .contentPath("interview > intro")
                .build();

        QueryRewriteResult result = service.rewrite(List.of("kb-1"), "tradeoff auth module", context);

        assertEquals(QueryRewriteResult.Intent.ANALYTICAL, result.getIntent());
        assertEquals(QueryRewriteResult.ContextApplyMode.SOFT, result.getContextApplyMode());
        assertEquals(List.of("tradeoff auth module"), result.getRetrievalQueries());
        assertTrue(result.isTitleQuery());
        assertNotNull(result.getContext());
        assertEquals("resume.md", result.getContext().getSourceName());
        assertNull(result.getContext().getContentPath());
    }

    @Test
    void shouldUseSoftContextWhenNavigationQueryClearlyLeavesCurrentContext() {
        QueryRewriteServiceImpl service = new QueryRewriteServiceImpl(new StubChunkBgeM3Mapper(List.of()));

        RagRetrievalContext context = RagRetrievalContext.builder()
                .kbId("kb-1")
                .sourceName("resume.md")
                .contentPath("interview > intro")
                .build();

        QueryRewriteResult result = service.rewrite(List.of("kb-1"), "system design > cache", context);

        assertEquals(QueryRewriteResult.Intent.NAVIGATION, result.getIntent());
        assertEquals(QueryRewriteResult.ContextApplyMode.SOFT, result.getContextApplyMode());
        assertTrue(result.isTitleQuery());
        assertEquals(List.of("system design > cache"), result.getRetrievalQueries());
        assertNotNull(result.getContext());
        assertEquals("kb-1", result.getContext().getKbId());
        assertEquals("resume.md", result.getContext().getSourceName());
        assertNull(result.getContext().getContentPath());
    }

    @Test
    void shouldTreatDifferentPathBranchAsTopicSwitchEvenWithSharedTerms() {
        QueryRewriteServiceImpl service = new QueryRewriteServiceImpl(new StubChunkBgeM3Mapper(List.of()));

        RagRetrievalContext context = RagRetrievalContext.builder()
                .kbId("kb-1")
                .sourceName("notes.md")
                .contentPath("project > sql tuning > aggregate")
                .build();

        QueryRewriteResult result = service.rewrite(
                List.of("kb-1"),
                "project > sql coverage > strong",
                context
        );

        assertEquals(QueryRewriteResult.Intent.NAVIGATION, result.getIntent());
        assertEquals(QueryRewriteResult.ContextApplyMode.SOFT, result.getContextApplyMode());
        assertTrue(result.isTitleQuery());
        assertEquals(List.of("project > sql coverage > strong"), result.getRetrievalQueries());
        assertNotNull(result.getContext());
        assertEquals("notes.md", result.getContext().getSourceName());
        assertNull(result.getContext().getContentPath());
    }

    @Test
    void shouldAutoSelectParentPathForPathAwareQueryAcrossKbs() {
        QueryRewriteServiceImpl service = new QueryRewriteServiceImpl(new StubChunkBgeM3Mapper(List.of(
                candidate(
                        "chunk-1",
                        "kb-1",
                        "{\"retrievableTitle\":\"answer how\",\"contentPath\":\"interview > behavior > answer how\",\"sourceName\":\"resume.md\",\"sourceType\":\"md\"}"
                ),
                candidate(
                        "chunk-2",
                        "kb-2",
                        "{\"retrievableTitle\":\"answer how\",\"contentPath\":\"other > path > answer how\",\"sourceName\":\"other.md\",\"sourceType\":\"md\"}"
                )
        )));

        QueryRewriteResult result = service.rewrite(
                List.of("kb-1", "kb-2"),
                "interview > behavior > answer how",
                null
        );

        assertTrue(result.isTitleQuery());
        assertEquals(QueryRewriteResult.Intent.NAVIGATION, result.getIntent());
        assertEquals(QueryRewriteResult.ContextApplyMode.HARD, result.getContextApplyMode());
        assertNotNull(result.getContext());
        assertEquals("kb-1", result.getContext().getKbId());
        assertEquals("resume.md", result.getContext().getSourceName());
        assertEquals("md", result.getContext().getSourceType());
        assertEquals("interview > behavior", result.getContext().getContentPath());
    }

    @Test
    void shouldTreatCompactKeywordQueryAsTitleQuery() {
        QueryRewriteServiceImpl service = new QueryRewriteServiceImpl(new StubChunkBgeM3Mapper(List.of()));

        QueryRewriteResult result = service.rewrite(List.of("kb-1"), "Redis persistence", null);

        assertEquals(QueryRewriteResult.Intent.FACTOID, result.getIntent());
        assertEquals(QueryRewriteResult.ContextApplyMode.NONE, result.getContextApplyMode());
        assertTrue(result.isTitleQuery());
        assertEquals(List.of("Redis persistence"), result.getRetrievalQueries());
    }

    @Test
    void shouldNotTreatNaturalQuestionAsTitleQuery() {
        QueryRewriteServiceImpl service = new QueryRewriteServiceImpl(new StubChunkBgeM3Mapper(List.of()));

        QueryRewriteResult result = service.rewrite(
                List.of("kb-1"),
                "How should I answer strengths and weaknesses?",
                null
        );

        assertEquals("How should I answer strengths and weaknesses?", result.getQuery());
        assertFalse(result.isTitleQuery());
        assertEquals(QueryRewriteResult.Intent.FACTOID, result.getIntent());
        assertEquals(QueryRewriteResult.ContextApplyMode.NONE, result.getContextApplyMode());
        assertEquals(List.of("How should I answer strengths and weaknesses?"), result.getRetrievalQueries());
        assertNotNull(result.getContext());
        assertNull(result.getContext().getSourceName());
        assertNull(result.getContext().getContentPath());
    }

    @Test
    void shouldTreatIndexedQuestionHeadingAsTitleQuery() {
        QueryRewriteServiceImpl service = new QueryRewriteServiceImpl(new StubChunkBgeM3Mapper(List.of()));

        QueryRewriteResult result = service.rewrite(
                List.of("kb-1"),
                "1. How is auth implemented?",
                null
        );

        assertEquals(QueryRewriteResult.Intent.FACTOID, result.getIntent());
        assertEquals(QueryRewriteResult.ContextApplyMode.NONE, result.getContextApplyMode());
        assertTrue(result.isTitleQuery());
        assertEquals(List.of("1. How is auth implemented?"), result.getRetrievalQueries());
    }

    @Test
    void shouldAppendLlmRewriteForHardFollowUpWhenEnabled() {
        QueryRewriteServiceImpl service = new QueryRewriteServiceImpl(
                new StubChunkBgeM3Mapper(List.of()),
                (query, context, intent) -> "resume.md interview answer detail",
                null,
                true,
                "deepseek-chat"
        );

        RagRetrievalContext context = RagRetrievalContext.builder()
                .kbId("kb-1")
                .sourceName("resume.md")
                .contentPath("interview > answer")
                .build();

        QueryRewriteResult result = service.rewrite(List.of("kb-1"), "answer", context);

        assertEquals(QueryRewriteResult.Intent.FOLLOW_UP, result.getIntent());
        assertEquals(QueryRewriteResult.ContextApplyMode.HARD, result.getContextApplyMode());
        assertEquals(
                List.of(
                        "resume.md interview answer detail",
                        "resume.md interview > answer answer",
                        "answer"
                ),
                result.getRetrievalQueries()
        );
    }

    @Test
    void shouldFallbackToRuleRewriteWhenLlmRewriteFails() {
        QueryRewriteServiceImpl service = new QueryRewriteServiceImpl(
                new StubChunkBgeM3Mapper(List.of()),
                (query, context, intent) -> {
                    throw new IllegalStateException("boom");
                },
                null,
                true,
                "deepseek-chat"
        );

        RagRetrievalContext context = RagRetrievalContext.builder()
                .kbId("kb-1")
                .sourceName("resume.md")
                .contentPath("interview > answer")
                .build();

        QueryRewriteResult result = service.rewrite(List.of("kb-1"), "answer", context);

        assertEquals(
                List.of("resume.md interview > answer answer", "answer"),
                result.getRetrievalQueries()
        );
    }

    @Test
    void shouldNotUseLlmRewriteForNavigationQuery() {
        QueryRewriteServiceImpl service = new QueryRewriteServiceImpl(
                new StubChunkBgeM3Mapper(List.of()),
                (query, context, intent) -> "ignored rewrite",
                null,
                true,
                "deepseek-chat"
        );

        RagRetrievalContext context = RagRetrievalContext.builder()
                .kbId("kb-1")
                .sourceName("resume.md")
                .contentPath("interview > strengths")
                .build();

        QueryRewriteResult result = service.rewrite(List.of("kb-1"), "system design > cache", context);

        assertEquals(QueryRewriteResult.Intent.NAVIGATION, result.getIntent());
        assertEquals(List.of("system design > cache"), result.getRetrievalQueries());
    }

    private static RagRetrievalResult candidate(String chunkId, String kbId, String metadata) {
        RagRetrievalResult result = new RagRetrievalResult();
        result.setChunkId(chunkId);
        result.setKbId(kbId);
        result.setMetadata(metadata);
        return result;
    }

    private static class StubChunkBgeM3Mapper implements ChunkBgeM3Mapper {
        private final List<RagRetrievalResult> titlePathCandidates;

        private StubChunkBgeM3Mapper(List<RagRetrievalResult> titlePathCandidates) {
            this.titlePathCandidates = titlePathCandidates;
        }

        @Override
        public List<RagRetrievalResult> selectTitlePathCandidatesByKbIds(List<String> kbIds) {
            return titlePathCandidates;
        }

        @Override
        public int insert(com.kama.jchatmind.model.entity.ChunkBgeM3 chunkBgeM3) {
            throw new UnsupportedOperationException();
        }

        @Override
        public com.kama.jchatmind.model.entity.ChunkBgeM3 selectById(String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int deleteById(String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int updateById(com.kama.jchatmind.model.entity.ChunkBgeM3 chunkBgeM3) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<com.kama.jchatmind.model.entity.ChunkBgeM3> similaritySearch(List<String> kbIds, String vectorLiteral, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RagRetrievalResult> similaritySearchDetailed(List<String> kbIds, String vectorLiteral, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RagRetrievalResult> similaritySearchDetailedWithContext(
                List<String> kbIds,
                String vectorLiteral,
                String sourceName,
                String sourceType,
                String contentPathPrefix,
                int limit
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RagRetrievalResult> searchByTitleExact(List<String> kbIds, String normalizedTitle, String vectorLiteral, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RagRetrievalResult> searchByTitleExactWithContext(
                List<String> kbIds,
                String normalizedTitle,
                String vectorLiteral,
                String sourceName,
                String sourceType,
                String contentPathPrefix,
                int limit
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RagRetrievalResult> searchByTitleContains(List<String> kbIds, String normalizedTitle, String containsPattern, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RagRetrievalResult> searchByTitleKeywords(List<String> kbIds, List<String> keywords, int queryLength, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RagRetrievalResult> searchByTitleTrigram(List<String> kbIds, String normalizedTitle, double minScore, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RagRetrievalResult> selectLexicalCandidatesByKbIds(List<String> kbIds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RagRetrievalResult> selectContentLexicalCandidatesByKbIds(List<String> kbIds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<com.kama.jchatmind.model.entity.ChunkBgeM3> selectByDocId(String docId) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class CountingObjectProvider<T> implements ObjectProvider<T> {
        private final AtomicInteger resolutionCount;
        private final T value;

        private CountingObjectProvider(AtomicInteger resolutionCount, T value) {
            this.resolutionCount = resolutionCount;
            this.value = value;
        }

        @Override
        public T getObject(Object... args) {
            resolutionCount.incrementAndGet();
            return value;
        }

        @Override
        public T getIfAvailable() {
            resolutionCount.incrementAndGet();
            return value;
        }
    }
}
