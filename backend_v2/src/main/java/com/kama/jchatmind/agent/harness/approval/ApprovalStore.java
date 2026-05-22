package com.kama.jchatmind.agent.harness.approval;

import java.util.List;
import java.util.Optional;

public interface ApprovalStore {
    ApprovalRequest createRequest(String sessionId, String toolName, String toolInput, int callCount, int timeoutSeconds);

    Optional<ApprovalRequest> getRequest(String requestId);

    ApprovalStatus approve(String requestId);

    ApprovalStatus reject(String requestId);

    ApprovalStatus expire(String requestId);

    ApprovalStatus awaitDecision(String requestId, int timeoutSeconds);

    List<ApprovalRequest> getPendingBySession(String sessionId);
}
