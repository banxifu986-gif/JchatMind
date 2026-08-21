package com.kama.jchatmind.model.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class IngestionTask {

    private String id;
    private Long ownerId;
    private String kbId;
    private String documentId;
    private String idempotencyKey;
    private String taskType;
    private String status;
    private Integer attemptCount;
    private Integer maxAttempts;
    private String errorSummary;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
}
