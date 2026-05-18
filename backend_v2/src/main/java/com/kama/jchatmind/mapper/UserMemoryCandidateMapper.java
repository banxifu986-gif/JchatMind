package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.UserMemoryCandidate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface UserMemoryCandidateMapper {
    int insert(UserMemoryCandidate candidate);

    List<UserMemoryCandidate> selectByUserId(String userId);

    UserMemoryCandidate selectByIdAndUserId(@Param("id") String id, @Param("userId") String userId);

    UserMemoryCandidate selectByUserIdAndContent(@Param("userId") String userId, @Param("content") String content);

    int deleteById(@Param("id") String id);
}
