package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.dto.QueryRewriteResult;
import com.kama.jchatmind.model.dto.RagRetrievalContext;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryRewriteServiceImplTest {

    @Test
    void shouldKeepExplicitContext() {
        QueryRewriteServiceImpl service = new QueryRewriteServiceImpl(new StubChunkBgeM3Mapper(List.of()));

        RagRetrievalContext context = RagRetrievalContext.builder()
                .kbId("kb-1")
                .sourceName("resume.md")
                .contentPath("面试 > 回答")
                .build();

        QueryRewriteResult result = service.rewrite(List.of("kb-1"), "回答 面试怎么回答", context);

        assertEquals("回答 面试怎么回答", result.getQuery());
        assertNotNull(result.getContext());
        assertEquals("kb-1", result.getContext().getKbId());
        assertEquals("resume.md", result.getContext().getSourceName());
        assertEquals("面试 > 回答", result.getContext().getContentPath());
        assertTrue(result.isTitleQuery());
    }

    @Test
    void shouldAutoSelectParentPathForPathAwareQueryAcrossKbs() {
        QueryRewriteServiceImpl service = new QueryRewriteServiceImpl(new StubChunkBgeM3Mapper(List.of(
                candidate(
                        "chunk-1",
                        "kb-1",
                        "{\"retrievableTitle\":\"回答 面试怎么回答\",\"contentPath\":\"面试 > 行为面试 > 回答 面试怎么回答\",\"sourceName\":\"resume.md\",\"sourceType\":\"md\"}"
                ),
                candidate(
                        "chunk-2",
                        "kb-2",
                        "{\"retrievableTitle\":\"回答 面试怎么回答\",\"contentPath\":\"其他 > 路径 > 回答 面试怎么回答\",\"sourceName\":\"other.md\",\"sourceType\":\"md\"}"
                )
        )));

        QueryRewriteResult result = service.rewrite(
                List.of("kb-1", "kb-2"),
                "面试 > 行为面试 > 回答 面试怎么回答",
                null
        );

        assertTrue(result.isTitleQuery());
        assertNotNull(result.getContext());
        assertEquals("kb-1", result.getContext().getKbId());
        assertEquals("resume.md", result.getContext().getSourceName());
        assertEquals("md", result.getContext().getSourceType());
        assertEquals("面试 > 行为面试", result.getContext().getContentPath());
    }

    @Test
    void shouldNotTreatNaturalQuestionAsTitleQuery() {
        QueryRewriteServiceImpl service = new QueryRewriteServiceImpl(new StubChunkBgeM3Mapper(List.of()));

        QueryRewriteResult result = service.rewrite(List.of("kb-1"), "面试时如何回答自己的优缺点？", null);

        assertEquals("面试时如何回答自己的优缺点？", result.getQuery());
        assertFalse(result.isTitleQuery());
        assertNotNull(result.getContext());
        assertNull(result.getContext().getSourceName());
        assertNull(result.getContext().getContentPath());
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
        public List<com.kama.jchatmind.model.entity.ChunkBgeM3> selectByDocId(String docId) {
            throw new UnsupportedOperationException();
        }
    }
}
