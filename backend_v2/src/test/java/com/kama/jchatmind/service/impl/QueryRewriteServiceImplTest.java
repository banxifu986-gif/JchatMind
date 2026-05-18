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
                .sourceName("resume.md")
                .contentPath("面试 > 回答")
                .build();

        QueryRewriteResult result = service.rewrite("kb-1", "回答 面试怎么回答", context);

        assertEquals("回答 面试怎么回答", result.getQuery());
        assertNotNull(result.getContext());
        assertEquals("resume.md", result.getContext().getSourceName());
        assertEquals("面试 > 回答", result.getContext().getContentPath());
        assertTrue(result.isTitleQuery());
    }

    @Test
    void shouldAutoSelectParentPathForPathAwareQuery() {
        QueryRewriteServiceImpl service = new QueryRewriteServiceImpl(new StubChunkBgeM3Mapper(List.of(
                candidate(
                        "chunk-1",
                        "{\"retrievableTitle\":\"回答 面试怎么回答\",\"contentPath\":\"面试 > 行为面试 > 回答 面试怎么回答\",\"sourceName\":\"resume.md\",\"sourceType\":\"md\"}"
                ),
                candidate(
                        "chunk-2",
                        "{\"retrievableTitle\":\"回答 面试怎么回答\",\"contentPath\":\"其他 > 路径 > 回答 面试怎么回答\",\"sourceName\":\"other.md\",\"sourceType\":\"md\"}"
                )
        )));

        QueryRewriteResult result = service.rewrite("kb-1", "面试 > 行为面试 > 回答 面试怎么回答", null);

        assertTrue(result.isTitleQuery());
        assertNotNull(result.getContext());
        assertEquals("resume.md", result.getContext().getSourceName());
        assertEquals("md", result.getContext().getSourceType());
        assertEquals("面试 > 行为面试", result.getContext().getContentPath());
    }

    @Test
    void shouldNotTreatNaturalQuestionAsTitleQuery() {
        QueryRewriteServiceImpl service = new QueryRewriteServiceImpl(new StubChunkBgeM3Mapper(List.of()));

        QueryRewriteResult result = service.rewrite("kb-1", "面试时如何回答自己的优缺点？", null);

        assertEquals("面试时如何回答自己的优缺点？", result.getQuery());
        assertFalse(result.isTitleQuery());
        assertNotNull(result.getContext());
        assertNull(result.getContext().getSourceName());
        assertNull(result.getContext().getContentPath());
    }

    private static RagRetrievalResult candidate(String chunkId, String metadata) {
        RagRetrievalResult result = new RagRetrievalResult();
        result.setChunkId(chunkId);
        result.setMetadata(metadata);
        return result;
    }

    private static class StubChunkBgeM3Mapper implements ChunkBgeM3Mapper {
        private final List<RagRetrievalResult> titlePathCandidates;

        private StubChunkBgeM3Mapper(List<RagRetrievalResult> titlePathCandidates) {
            this.titlePathCandidates = titlePathCandidates;
        }

        @Override
        public List<RagRetrievalResult> selectTitlePathCandidatesByKbId(String kbId) {
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
        public List<com.kama.jchatmind.model.entity.ChunkBgeM3> similaritySearch(String kbId, String vectorLiteral, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RagRetrievalResult> similaritySearchDetailed(String kbId, String vectorLiteral, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RagRetrievalResult> similaritySearchDetailedWithContext(
                String kbId,
                String vectorLiteral,
                String sourceName,
                String sourceType,
                String contentPathPrefix,
                int limit
        ) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RagRetrievalResult> searchByTitleExact(String kbId, String normalizedTitle, String vectorLiteral, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RagRetrievalResult> searchByTitleExactWithContext(
                String kbId,
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
        public List<RagRetrievalResult> searchByTitleContains(String kbId, String normalizedTitle, String containsPattern, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RagRetrievalResult> searchByTitleKeywords(String kbId, List<String> keywords, int queryLength, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RagRetrievalResult> searchByTitleTrigram(String kbId, String normalizedTitle, double minScore, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<RagRetrievalResult> selectLexicalCandidatesByKbId(String kbId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<com.kama.jchatmind.model.entity.ChunkBgeM3> selectByDocId(String docId) {
            throw new UnsupportedOperationException();
        }
    }
}
