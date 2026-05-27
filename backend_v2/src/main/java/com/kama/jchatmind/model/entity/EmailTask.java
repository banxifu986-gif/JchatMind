package com.kama.jchatmind.model.entity;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class EmailTask {

    private String taskId;
    private String email;
    private String code;
    private String type;
    private int retryCount;
    private LocalDateTime createdAt;
    private LocalDateTime expireAt;
    private String traceId;
    private String requestIp;
    private String failureReason;
}
