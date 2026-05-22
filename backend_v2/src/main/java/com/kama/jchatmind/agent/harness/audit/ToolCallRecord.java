package com.kama.jchatmind.agent.harness.audit;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
public class ToolCallRecord {

    public enum Outcome {
        SUCCESS,
        ERROR,
        REJECTED,
        EXPIRED,
        CIRCUIT_OPEN
    }

    private String id;
    private String sessionId;
    private String agentId;
    private String toolCallId;
    private String toolName;
    private String toolInput;
    private String toolResult;
    private boolean success;
    private Outcome outcome;
    private String errorMessage;
    private long durationMs;
    private Instant timestamp;
    private int stepNumber;
}
