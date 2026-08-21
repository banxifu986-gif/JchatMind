package com.kama.jchatmind.ingestion;

import lombok.Builder;

@Builder
public record IngestionTaskProgressEvent(
        String taskId,
        long sequence,
        String kbId,
        String documentId,
        String status,
        Integer attemptCount,
        Integer maxAttempts,
        String errorSummary
) {
}
