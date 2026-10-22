package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.BuiltinSkillExecutionTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface BuiltinSkillExecutionTaskMapper {

    int insertIfAbsent(BuiltinSkillExecutionTask task);

    BuiltinSkillExecutionTask selectByRequesterAndIdempotencyKey(
            @Param("requesterUserId") Long requesterUserId,
            @Param("idempotencyKey") String idempotencyKey
    );

    BuiltinSkillExecutionTask selectByIdAndRequester(
            @Param("taskId") String taskId,
            @Param("requesterUserId") Long requesterUserId
    );
}
