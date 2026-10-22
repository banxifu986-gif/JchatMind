package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.BuiltinSkillExecutionTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface BuiltinSkillTaskMapper {

    int insertIfAbsent(BuiltinSkillExecutionTask task);

    BuiltinSkillExecutionTask selectByRequesterAndIdempotencyKey(
            @Param("requesterUserId") Long requesterUserId,
            @Param("idempotencyKey") String idempotencyKey
    );

    BuiltinSkillExecutionTask selectByIdAndRequester(
            @Param("taskId") String taskId,
            @Param("requesterUserId") Long requesterUserId
    );

    List<String> selectDispatchableTaskIds(@Param("limit") int limit);

    int acquireDispatchLease(
            @Param("taskId") String taskId,
            @Param("leaseSeconds") int leaseSeconds
    );

    BuiltinSkillExecutionTask selectByTaskId(@Param("taskId") String taskId);

    int claimExecutionTask(
            @Param("taskId") String taskId,
            @Param("leaseSeconds") int leaseSeconds
    );

    int completeExecutionTask(
            @Param("taskId") String taskId,
            @Param("attemptCount") int attemptCount,
            @Param("resultJson") String resultJson
    );

    int retryExecutionTask(
            @Param("taskId") String taskId,
            @Param("attemptCount") int attemptCount,
            @Param("failureType") String failureType,
            @Param("errorSummary") String errorSummary,
            @Param("retryDelaySeconds") int retryDelaySeconds
    );

    int deadLetterExecutionTask(
            @Param("taskId") String taskId,
            @Param("attemptCount") int attemptCount,
            @Param("failureType") String failureType,
            @Param("errorSummary") String errorSummary
    );

    int failExecutionTask(
            @Param("taskId") String taskId,
            @Param("attemptCount") int attemptCount,
            @Param("failureType") String failureType,
            @Param("errorSummary") String errorSummary
    );

    int recoverExpiredExecutionTasks(@Param("retryDelaySeconds") int retryDelaySeconds);
}
