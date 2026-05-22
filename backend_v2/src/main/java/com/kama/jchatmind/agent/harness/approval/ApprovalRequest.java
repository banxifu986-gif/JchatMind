package com.kama.jchatmind.agent.harness.approval;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class ApprovalRequest {
    private String id;
    private String sessionId;
    private String toolName;
    private String toolInput;
    private int callCount;
    private ApprovalStatus status;
    private Instant createdAt;
    private Instant expiresAt;
}
