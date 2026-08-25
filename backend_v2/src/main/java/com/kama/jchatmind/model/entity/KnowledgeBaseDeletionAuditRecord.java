package com.kama.jchatmind.model.entity;

import java.time.LocalDateTime;

public record KnowledgeBaseDeletionAuditRecord(
        String id,
        String taskId,
        Long ownerId,
        String knowledgeBaseId,
        String action,
        String taskStatus,
        LocalDateTime createdAt
) {
}
