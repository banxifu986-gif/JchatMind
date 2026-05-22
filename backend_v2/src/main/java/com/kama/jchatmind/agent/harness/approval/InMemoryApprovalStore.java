package com.kama.jchatmind.agent.harness.approval;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

@Component
public class InMemoryApprovalStore implements ApprovalStore {

    private final ConcurrentMap<String, ApprovalRequest> requests = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CompletableFuture<ApprovalStatus>> futures = new ConcurrentHashMap<>();

    @Override
    public ApprovalRequest createRequest(
            String sessionId,
            String toolName,
            String toolInput,
            int callCount,
            int timeoutSeconds
    ) {
        String requestId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        ApprovalRequest request = ApprovalRequest.builder()
                .id(requestId)
                .sessionId(sessionId)
                .toolName(toolName)
                .toolInput(toolInput)
                .callCount(callCount)
                .status(ApprovalStatus.PENDING)
                .createdAt(now)
                .expiresAt(now.plusSeconds(timeoutSeconds))
                .build();
        requests.put(requestId, request);
        futures.put(requestId, new CompletableFuture<>());
        return request;
    }

    @Override
    public Optional<ApprovalRequest> getRequest(String requestId) {
        return Optional.ofNullable(requests.get(requestId));
    }

    @Override
    public ApprovalStatus approve(String requestId) {
        return complete(requestId, ApprovalStatus.APPROVED);
    }

    @Override
    public ApprovalStatus reject(String requestId) {
        return complete(requestId, ApprovalStatus.REJECTED);
    }

    @Override
    public ApprovalStatus expire(String requestId) {
        return complete(requestId, ApprovalStatus.EXPIRED);
    }

    @Override
    public ApprovalStatus awaitDecision(String requestId, int timeoutSeconds) {
        ApprovalRequest request = requests.get(requestId);
        if (request == null) {
            return ApprovalStatus.EXPIRED;
        }
        if (request.getStatus() != ApprovalStatus.PENDING) {
            return request.getStatus();
        }

        CompletableFuture<ApprovalStatus> future = futures.get(requestId);
        if (future == null) {
            return ApprovalStatus.EXPIRED;
        }

        try {
            future.get(timeoutSeconds, TimeUnit.SECONDS);
            return getRequest(requestId)
                    .map(ApprovalRequest::getStatus)
                    .orElse(ApprovalStatus.EXPIRED);
        } catch (Exception ignored) {
            return expire(requestId);
        }
    }

    @Override
    public List<ApprovalRequest> getPendingBySession(String sessionId) {
        return requests.values().stream()
                .filter(request -> request.getSessionId().equals(sessionId))
                .filter(request -> request.getStatus() == ApprovalStatus.PENDING)
                .sorted((left, right) -> left.getCreatedAt().compareTo(right.getCreatedAt()))
                .toList();
    }

    private ApprovalStatus complete(String requestId, ApprovalStatus status) {
        CompletableFuture<ApprovalStatus> future = futures.get(requestId);
        ApprovalStatus finalStatus = requests.compute(requestId, (key, request) -> {
            if (request == null) {
                return null;
            }
            if (request.getStatus() != ApprovalStatus.PENDING) {
                return request;
            }
            request.setStatus(status);
            if (future != null && !future.isDone()) {
                future.complete(status);
            }
            return request;
        }) == null ? ApprovalStatus.EXPIRED : requests.get(requestId).getStatus();

        if (future != null && future.isDone()) {
            try {
                return future.getNow(finalStatus);
            } catch (Exception ignored) {
                return finalStatus;
            }
        }
        return finalStatus;
    }
}
