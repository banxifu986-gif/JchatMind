package com.kama.jchatmind.model.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AgentExecutionTask {
    private String id;
    private String sessionId;
    private String userId;
    private String agentId;
    private String userMessageId;
    private long sequenceNo;
    private String status;
    private int attemptCount;
    private LocalDateTime createdAt;
}
