package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.UserMemory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface UserMemoryMapper {
    String selectAutomationMode(String userId);

    int upsertAutomationMode(@Param("userId") String userId, @Param("mode") String mode);

    int selectLastExtractedUserMessageCount(@Param("userId") String userId, @Param("sessionId") String sessionId);

    int markExtractionAttempt(@Param("userId") String userId, @Param("sessionId") String sessionId);

    int markExtractionSuccess(
            @Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("messageCount") int messageCount
    );

    int markExtractionFailure(
            @Param("userId") String userId,
            @Param("sessionId") String sessionId,
            @Param("failureType") String failureType
    );

    int insert(UserMemory userMemory);

    UserMemory selectByIdAndUserId(@Param("id") String id, @Param("userId") String userId);

    UserMemory selectByUserIdAndContent(@Param("userId") String userId, @Param("content") String content);

    List<UserMemory> selectByUserId(String userId);

    List<UserMemory> selectActiveByUserId(String userId);

    List<UserMemory> selectActiveByIdsForUpdate(
            @Param("userId") String userId,
            @Param("ids") List<String> ids
    );

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

    int markSupersededByIds(
            @Param("ids") List<String> ids,
            @Param("userId") String userId,
            @Param("supersededByMemoryId") String supersededByMemoryId
    );

    int updateEmbedding(@Param("id") String id, @Param("embedding") float[] embedding);

    List<UserMemory> similaritySearch(
            @Param("userId") String userId,
            @Param("vectorLiteral") String vectorLiteral,
            @Param("limit") int limit
    );
}
