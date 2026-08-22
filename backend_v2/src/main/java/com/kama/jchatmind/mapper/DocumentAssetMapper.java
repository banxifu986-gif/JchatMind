package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.DocumentAsset;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface DocumentAssetMapper {
    int insert(DocumentAsset documentAsset);

    int deleteByDocumentId(String documentId);

    int insertChunkRelation(
            @Param("assetId") String assetId,
            @Param("chunkId") String chunkId,
            @Param("assetDocumentId") String assetDocumentId,
            @Param("chunkDocumentId") String chunkDocumentId
    );
}
