package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserMapper {
    int insert(User user);

    User findById(@Param("userId") Long userId);

    User findByAccount(@Param("account") String account);

    User findByEmail(@Param("email") String email);

    int updateLastLoginAt(@Param("userId") Long userId);

    boolean existsByEmail(@Param("email") String email);
}
