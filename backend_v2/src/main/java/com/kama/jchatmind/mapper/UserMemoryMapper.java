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

    int deleteById(@Param("id") String id);
}
