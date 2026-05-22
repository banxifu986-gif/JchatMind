package com.kama.jchatmind.controller;

import com.kama.jchatmind.agent.harness.HarnessRunner;
import com.kama.jchatmind.agent.harness.approval.ApprovalRequest;
import com.kama.jchatmind.agent.harness.approval.ApprovalStatus;
import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.service.ChatSessionFacadeService;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/harness")
@AllArgsConstructor
public class HarnessController {

    private final HarnessRunner harnessRunner;
    private final ChatSessionFacadeService chatSessionFacadeService;

    @PostMapping("/approve/{requestId}")
    public ApiResponse<ApprovalActionResponse> approve(
            @RequestParam String userId,
            @PathVariable String requestId
    ) {
        ApprovalRequest request = requireOwnedRequest(userId, requestId);
        ApprovalStatus status = harnessRunner.approve(requestId);
        return ApiResponse.success(ApprovalActionResponse.builder()
                .requestId(requestId)
                .status(status.name())
                .build());
    }

    @PostMapping("/reject/{requestId}")
    public ApiResponse<ApprovalActionResponse> reject(
            @RequestParam String userId,
            @PathVariable String requestId
    ) {
        ApprovalRequest request = requireOwnedRequest(userId, requestId);
        ApprovalStatus status = harnessRunner.reject(requestId);
        return ApiResponse.success(ApprovalActionResponse.builder()
                .requestId(requestId)
                .status(status.name())
                .build());
    }

    @GetMapping("/pending/{sessionId}")
    public ApiResponse<PendingApprovalsResponse> getPending(
            @RequestParam String userId,
            @PathVariable String sessionId
    ) {
        chatSessionFacadeService.getChatSession(userId, sessionId);
        List<PendingApprovalVO> approvals = harnessRunner.getPendingApprovals(sessionId).stream()
                .map(request -> PendingApprovalVO.builder()
                        .id(request.getId())
                        .sessionId(request.getSessionId())
                        .toolName(request.getToolName())
                        .toolInput(request.getToolInput())
                        .callCount(request.getCallCount())
                        .status(request.getStatus().name())
                        .createdAt(request.getCreatedAt().toEpochMilli())
                        .expiresAt(request.getExpiresAt().toEpochMilli())
                        .build())
                .toList();
        return ApiResponse.success(PendingApprovalsResponse.builder()
                .approvals(approvals)
                .build());
    }

    private ApprovalRequest requireOwnedRequest(String userId, String requestId) {
        ApprovalRequest request = harnessRunner.getApprovalRequest(requestId);
        if (request == null) {
            throw new IllegalArgumentException("审批请求不存在: " + requestId);
        }
        chatSessionFacadeService.getChatSession(userId, request.getSessionId());
        return request;
    }

    @Data
    @Builder
    public static class ApprovalActionResponse {
        private String requestId;
        private String status;
    }

    @Data
    @Builder
    public static class PendingApprovalsResponse {
        private List<PendingApprovalVO> approvals;
    }

    @Data
    @Builder
    public static class PendingApprovalVO {
        private String id;
        private String sessionId;
        private String toolName;
        private String toolInput;
        private int callCount;
        private String status;
        private long createdAt;
        private long expiresAt;
    }
}
