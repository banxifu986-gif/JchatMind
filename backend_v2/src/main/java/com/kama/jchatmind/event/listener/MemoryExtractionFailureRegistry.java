package com.kama.jchatmind.event.listener;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class MemoryExtractionFailureRegistry {

    private final ConcurrentHashMap<FailureKey, MemoryExtractionFailure> failures = new ConcurrentHashMap<>();

    public void recordFailure(String userId, String sessionId, Exception exception) {
        FailureKey key = new FailureKey(userId, sessionId);
        String errorType = exception.getClass().getName();
        Instant failedAt = Instant.now();
        failures.compute(key, (ignored, previous) -> new MemoryExtractionFailure(
                errorType,
                previous == null ? 1 : previous.failureCount() + 1,
                failedAt
        ));
    }

    public Optional<MemoryExtractionFailure> getFailure(String userId, String sessionId) {
        return Optional.ofNullable(failures.get(new FailureKey(userId, sessionId)));
    }

    public void clear(String userId, String sessionId) {
        failures.remove(new FailureKey(userId, sessionId));
    }

    public record MemoryExtractionFailure(String errorType, int failureCount, Instant lastFailedAt) {
    }

    private record FailureKey(String userId, String sessionId) {
    }
}
