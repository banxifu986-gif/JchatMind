package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.DocumentAssetRelation;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DocumentAssetRelationMapper {
    int insert(DocumentAssetRelation relation);
}