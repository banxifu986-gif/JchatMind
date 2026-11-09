package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.DocumentAssetEntity;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DocumentAssetEntityMapper {
    int insert(DocumentAssetEntity entity);
}