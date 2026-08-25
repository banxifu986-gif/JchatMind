package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.KnowledgeBase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
* @author charon
* @description 针对表【knowledge_base】的数据库操作Mapper
* @createDate 2025-12-02 15:42:24
* @Entity com.kama.jchatmind.model.entity.KnowledgeBase
*/
@Mapper
public interface KnowledgeBaseMapper {
    int insert(KnowledgeBase knowledgeBase);

    KnowledgeBase selectById(String id);

    List<KnowledgeBase> selectAll();

    List<KnowledgeBase> selectByOwnerId(String ownerId);

    List<KnowledgeBase> selectByIdBatch(List<String> ids);

    int deleteById(String id);

    int deleteByIdAndOwnerId(@Param("id") String id, @Param("ownerId") String ownerId);

    int updateById(KnowledgeBase knowledgeBase);
}
