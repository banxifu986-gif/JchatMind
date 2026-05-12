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
            @Param("kbId") String kbId,
            @Param("vectorLiteral") String vectorLiteral,
            @Param("limit") int limit
    );

    List<RagRetrievalResult> similaritySearchDetailed(
            @Param("kbId") String kbId,
            @Param("vectorLiteral") String vectorLiteral,
            @Param("limit") int limit
    );

    List<RagRetrievalResult> searchByTitleExact(
            @Param("kbId") String kbId,
            @Param("normalizedTitle") String normalizedTitle,
            @Param("limit") int limit
    );

    List<RagRetrievalResult> searchByTitleContains(
            @Param("kbId") String kbId,
            @Param("normalizedTitle") String normalizedTitle,
            @Param("containsPattern") String containsPattern,
            @Param("limit") int limit
    );

    List<RagRetrievalResult> searchByTitleKeywords(
            @Param("kbId") String kbId,
            @Param("keywords") List<String> keywords,
            @Param("queryLength") int queryLength,
            @Param("limit") int limit
    );

    List<RagRetrievalResult> searchByTitleTrigram(
            @Param("kbId") String kbId,
            @Param("normalizedTitle") String normalizedTitle,
            @Param("minScore") double minScore,
            @Param("limit") int limit
    );

    List<ChunkBgeM3> selectByDocId(@Param("docId") String docId);
}
