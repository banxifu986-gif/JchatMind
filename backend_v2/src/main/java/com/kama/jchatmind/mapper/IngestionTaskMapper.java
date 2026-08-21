package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.IngestionTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface IngestionTaskMapper {

    int insert(IngestionTask task);

    IngestionTask selectById(@Param("taskId") String taskId);

    IngestionTask selectByOwnerIdAndIdempotencyKey(
            @Param("ownerId") Long ownerId,
            @Param("idempotencyKey") String idempotencyKey
    );

    Integer lockOwnerIdempotencyKey(
            @Param("ownerId") Long ownerId,
            @Param("idempotencyKey") String idempotencyKey
    );

    int updateStatusIfCurrent(
            @Param("taskId") String taskId,
            @Param("currentStatus") String currentStatus,
            @Param("nextStatus") String nextStatus,
            @Param("attemptCount") Integer attemptCount,
            @Param("errorSummary") String errorSummary
    );
}
