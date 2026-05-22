package com.kama.jchatmind.agent.harness;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class HarnessDecision {

    public enum Status {
        ALLOW,
        PENDING_APPROVAL,
        REJECTED,
        EXPIRED,
        CIRCUIT_OPEN
    }

    private Status status;
    private String message;
    private String approvalRequestId;

    public static HarnessDecision allow() {
        return HarnessDecision.builder()
                .status(Status.ALLOW)
                .build();
    }

    public static HarnessDecision pending(String approvalRequestId) {
        return HarnessDecision.builder()
                .status(Status.PENDING_APPROVAL)
                .approvalRequestId(approvalRequestId)
                .build();
    }

    public static HarnessDecision rejected(String message) {
        return HarnessDecision.builder()
                .status(Status.REJECTED)
                .message(message)
                .build();
    }

    public static HarnessDecision expired(String message) {
        return HarnessDecision.builder()
                .status(Status.EXPIRED)
                .message(message)
                .build();
    }

    public static HarnessDecision circuitOpen(String message) {
        return HarnessDecision.builder()
                .status(Status.CIRCUIT_OPEN)
                .message(message)
                .build();
    }
}
