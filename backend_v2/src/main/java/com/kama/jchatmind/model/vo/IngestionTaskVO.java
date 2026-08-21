package com.kama.jchatmind.model.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class IngestionTaskVO {

    private String taskId;
    private String kbId;
    private String documentId;
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
