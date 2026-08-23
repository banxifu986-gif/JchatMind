package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.ChunkBgeM3;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface VchordBm25BackfillMapper {
    List<ChunkBgeM3> selectLegacyBm25ChunksForUpdate(@Param("limit") int limit);

    int updateBm25Projection(
            @Param("id") String id,
            @Param("titleBm25Vector") String titleBm25Vector,
            @Param("contentBm25Vector") String contentBm25Vector,
            @Param("bm25IndexVersion") int bm25IndexVersion
    );
}
