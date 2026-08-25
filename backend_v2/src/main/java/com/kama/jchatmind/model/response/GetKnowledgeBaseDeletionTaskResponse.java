package com.kama.jchatmind.model.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class GetKnowledgeBaseDeletionTaskResponse {

    private String deletionTaskId;
    private String status;
    private Integer progress;
    private Integer attemptCount;
    private Integer maxAttempts;
    private String errorSummary;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
}
