package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.UserMemoryConsolidationProposal;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface UserMemoryConsolidationProposalMapper {
    int insert(UserMemoryConsolidationProposal proposal);

    List<UserMemoryConsolidationProposal> selectPendingByUserId(String userId);

    UserMemoryConsolidationProposal selectPendingByIdAndUserId(
            @Param("id") String id,
            @Param("userId") String userId
    );

    int markStatus(
            @Param("id") String id,
            @Param("userId") String userId,
            @Param("status") String status,
            @Param("appliedMemoryId") String appliedMemoryId
    );
}
