package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.KnowledgeBaseDeletionAuditRecord;
import com.kama.jchatmind.model.entity.KnowledgeBaseDeletionTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface KnowledgeBaseDeletionTaskMapper {

    int insert(KnowledgeBaseDeletionTask task);

    KnowledgeBaseDeletionTask selectById(String taskId);

    KnowledgeBaseDeletionTask selectByOwnerIdAndIdempotencyKey(
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
            @Param("attemptCount") int attemptCount,
            @Param("errorSummary") String errorSummary
    );

    int insertAudit(KnowledgeBaseDeletionAuditRecord record);
}
