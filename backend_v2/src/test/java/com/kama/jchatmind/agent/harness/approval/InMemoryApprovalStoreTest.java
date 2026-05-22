package com.kama.jchatmind.agent.harness.approval;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InMemoryApprovalStoreTest {

    private final InMemoryApprovalStore store = new InMemoryApprovalStore();

    @Test
    void shouldCreatePendingRequest() {
        ApprovalRequest request = store.createRequest("session-1", "sendEmail", "{\"to\":\"a\"}", 2, 30);

        assertEquals(ApprovalStatus.PENDING, request.getStatus());
        assertEquals("session-1", request.getSessionId());
        assertEquals("sendEmail", request.getToolName());
        assertEquals(1, store.getPendingBySession("session-1").size());
    }

    @Test
    void shouldApproveRequest() {
        ApprovalRequest request = store.createRequest("session-1", "sendEmail", "{}", 1, 30);

        ApprovalStatus status = store.approve(request.getId());

        assertEquals(ApprovalStatus.APPROVED, status);
        assertEquals(ApprovalStatus.APPROVED, store.getRequest(request.getId()).orElseThrow().getStatus());
        assertTrue(store.getPendingBySession("session-1").isEmpty());
    }

    @Test
    void shouldRejectRequest() {
        ApprovalRequest request = store.createRequest("session-1", "sendEmail", "{}", 1, 30);

        ApprovalStatus status = store.reject(request.getId());

        assertEquals(ApprovalStatus.REJECTED, status);
        assertEquals(ApprovalStatus.REJECTED, store.getRequest(request.getId()).orElseThrow().getStatus());
    }

    @Test
    void shouldExpireWhenAwaitTimeout() {
        ApprovalRequest request = store.createRequest("session-1", "sendEmail", "{}", 1, 0);

        ApprovalStatus status = store.awaitDecision(request.getId(), 0);

        assertEquals(ApprovalStatus.EXPIRED, status);
        assertEquals(ApprovalStatus.EXPIRED, store.getRequest(request.getId()).orElseThrow().getStatus());
    }

    @Test
    void shouldReturnApprovedWhenAwaitCompleted() {
        ApprovalRequest request = store.createRequest("session-1", "sendEmail", "{}", 1, 30);

        CompletableFuture.runAsync(() -> store.approve(request.getId()));
        ApprovalStatus status = store.awaitDecision(request.getId(), 5);

        assertEquals(ApprovalStatus.APPROVED, status);
    }

    @Test
    void shouldKeepConsistentStatusWhenApproveAndTimeoutRace() throws Exception {
        ApprovalRequest request = store.createRequest("session-1", "sendEmail", "{}", 1, 1);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<ApprovalStatus> awaitFuture = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return store.awaitDecision(request.getId(), 1);
            });
            Future<ApprovalStatus> approveFuture = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return store.approve(request.getId());
            });
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();

            ApprovalStatus awaitStatus = awaitFuture.get(5, TimeUnit.SECONDS);
            ApprovalStatus approveStatus = approveFuture.get(5, TimeUnit.SECONDS);
            ApprovalStatus finalStatus = store.getRequest(request.getId()).orElseThrow().getStatus();

            assertEquals(finalStatus, awaitStatus);
            assertEquals(finalStatus, approveStatus);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void shouldKeepConsistentStatusWhenRejectAndTimeoutRace() throws Exception {
        ApprovalRequest request = store.createRequest("session-1", "sendEmail", "{}", 1, 1);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Future<ApprovalStatus> awaitFuture = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return store.awaitDecision(request.getId(), 1);
            });
            Future<ApprovalStatus> rejectFuture = executor.submit(() -> {
                ready.countDown();
                start.await(5, TimeUnit.SECONDS);
                return store.reject(request.getId());
            });
            ready.await(5, TimeUnit.SECONDS);
            start.countDown();

            ApprovalStatus awaitStatus = awaitFuture.get(5, TimeUnit.SECONDS);
            ApprovalStatus rejectStatus = rejectFuture.get(5, TimeUnit.SECONDS);
            ApprovalStatus finalStatus = store.getRequest(request.getId()).orElseThrow().getStatus();

            assertEquals(finalStatus, awaitStatus);
            assertEquals(finalStatus, rejectStatus);
        } finally {
            executor.shutdownNow();
        }
    }
}
