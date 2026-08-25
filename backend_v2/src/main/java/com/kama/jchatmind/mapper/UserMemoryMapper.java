package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.UserMemory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface UserMemoryMapper {
    int insert(UserMemory userMemory);

    UserMemory selectByIdAndUserId(@Param("id") String id, @Param("userId") String userId);

    UserMemory selectByUserIdAndContent(@Param("userId") String userId, @Param("content") String content);

    List<UserMemory> selectByUserId(String userId);

    List<UserMemory> selectActiveByUserId(String userId);

    int deleteById(@Param("id") String id);

    int deleteByUserId(@Param("userId") String userId);

    int updateContentAndEmbedding(
            @Param("id") String id,
            @Param("userId") String userId,
            @Param("content") String content,
            @Param("embedding") float[] embedding
    );

    int updateContentEmbeddingAndExpiration(
            @Param("id") String id,
            @Param("userId") String userId,
            @Param("content") String content,
            @Param("embedding") float[] embedding,
            @Param("expiresAt") java.time.LocalDateTime expiresAt
    );

    int updateExpiration(
            @Param("id") String id,
            @Param("userId") String userId,
            @Param("expiresAt") java.time.LocalDateTime expiresAt
    );

    int markSupersededById(
            @Param("id") String id,
            @Param("supersededByMemoryId") String supersededByMemoryId
    );

    int updateEmbedding(@Param("id") String id, @Param("embedding") float[] embedding);

    List<UserMemory> similaritySearch(
            @Param("userId") String userId,
            @Param("vectorLiteral") String vectorLiteral,
            @Param("limit") int limit
    );
}
