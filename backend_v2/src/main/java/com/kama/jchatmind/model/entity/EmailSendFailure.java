package com.kama.jchatmind.model.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class EmailSendFailure {

    private Long id;
    private String taskId;
    private String email;
    private String type;
    private int retryCount;
    private String failureReason;
    private String traceId;
    private int expiredFlag;
    private LocalDateTime createdAt;
    private LocalDateTime failedAt;
}
