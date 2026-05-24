package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.model.entity.ChunkBgeM3;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @author charon
 * @description 针对表【chunk_bge_m3】的数据库操作Mapper
 * @createDate 2025-12-02 15:44:34
 * @Entity com.kama.jchatmind.model.entity.ChunkBgeM3
 */
@Mapper
public interface ChunkBgeM3Mapper {
    int insert(ChunkBgeM3 chunkBgeM3);

    ChunkBgeM3 selectById(String id);

    int deleteById(String id);

    int updateById(ChunkBgeM3 chunkBgeM3);

    List<ChunkBgeM3> similaritySearch(
            @Param("kbIds") List<String> kbIds,
            @Param("vectorLiteral") String vectorLiteral,
            @Param("limit") int limit
    );

    List<RagRetrievalResult> similaritySearchDetailed(
            @Param("kbIds") List<String> kbIds,
            @Param("vectorLiteral") String vectorLiteral,
            @Param("limit") int limit
    );

    List<RagRetrievalResult> similaritySearchDetailedWithContext(
            @Param("kbIds") List<String> kbIds,
            @Param("vectorLiteral") String vectorLiteral,
            @Param("sourceName") String sourceName,
            @Param("sourceType") String sourceType,
            @Param("contentPathPrefix") String contentPathPrefix,
            @Param("limit") int limit
    );

    List<RagRetrievalResult> searchByTitleExact(
            @Param("kbIds") List<String> kbIds,
            @Param("normalizedTitle") String normalizedTitle,
            @Param("vectorLiteral") String vectorLiteral,
            @Param("limit") int limit
    );

    List<RagRetrievalResult> searchByTitleExactWithContext(
            @Param("kbIds") List<String> kbIds,
            @Param("normalizedTitle") String normalizedTitle,
            @Param("vectorLiteral") String vectorLiteral,
            @Param("sourceName") String sourceName,
            @Param("sourceType") String sourceType,
            @Param("contentPathPrefix") String contentPathPrefix,
            @Param("limit") int limit
    );

    List<RagRetrievalResult> searchByTitleContains(
            @Param("kbIds") List<String> kbIds,
            @Param("normalizedTitle") String normalizedTitle,
            @Param("containsPattern") String containsPattern,
            @Param("limit") int limit
    );

    List<RagRetrievalResult> searchByTitleKeywords(
            @Param("kbIds") List<String> kbIds,
            @Param("keywords") List<String> keywords,
            @Param("queryLength") int queryLength,
            @Param("limit") int limit
    );

    List<RagRetrievalResult> searchByTitleTrigram(
            @Param("kbIds") List<String> kbIds,
            @Param("normalizedTitle") String normalizedTitle,
            @Param("minScore") double minScore,
            @Param("limit") int limit
    );

    List<RagRetrievalResult> selectLexicalCandidatesByKbIds(@Param("kbIds") List<String> kbIds);

    List<RagRetrievalResult> selectContentLexicalCandidatesByKbIds(@Param("kbIds") List<String> kbIds);

    List<RagRetrievalResult> selectTitlePathCandidatesByKbIds(@Param("kbIds") List<String> kbIds);

    List<ChunkBgeM3> selectByDocId(@Param("docId") String docId);
}
