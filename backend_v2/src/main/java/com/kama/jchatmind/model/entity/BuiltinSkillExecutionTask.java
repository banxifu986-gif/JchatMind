package com.kama.jchatmind.model.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class BuiltinSkillExecutionTask {

    private String taskId;
    private Long requesterUserId;
    private String taskType;
    private String agentId;
    private String sessionId;
    private String skillId;
    private String skillVersion;
    private String idempotencyKey;
    private String requestSha256;
    private String inputSnapshot;
    private String status;
    private short progress;
    private int attemptCount;
    private int maxAttempts;
    private int dispatchAttemptCount;
    private String errorSummary;
    private String failureType;
    private String resultJson;
    private String resultReference;
    private LocalDateTime nextAttemptAt;
    private LocalDateTime lastDispatchedAt;
    private LocalDateTime leaseExpiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
}
