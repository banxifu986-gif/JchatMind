package com.kama.jchatmind.model.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class KnowledgeBaseDeletionTask {

    private String id;
    private Long ownerId;
    private String knowledgeBaseId;
    private String taskType;
    private String idempotencyKey;
    private String inputSnapshot;
    private String skillVersion;
    private String status;
    private Integer progress;
    private Integer attemptCount;
    private Integer maxAttempts;
    private String errorSummary;
    private String resultRef;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
}
