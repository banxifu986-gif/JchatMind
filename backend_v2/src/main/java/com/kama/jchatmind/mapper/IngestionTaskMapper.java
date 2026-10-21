package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.IngestionTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.UUID;

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

    int insertWebhookDeliveryEvent(
            @Param("taskId") String taskId,
            @Param("eventType") String eventType,
            @Param("callbackEventId") UUID callbackEventId,
            @Param("eventPayload") String eventPayload,
            @Param("payloadSha256") String payloadSha256
    );
}
