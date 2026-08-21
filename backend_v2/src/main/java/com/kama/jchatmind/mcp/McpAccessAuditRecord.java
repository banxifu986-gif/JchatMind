package com.kama.jchatmind.mcp;

public record McpAccessAuditRecord(
        Long principalId,
        Long userId,
        String action,
        String decision,
        String targetKbIdsJson,
        String correlationId,
        String reasonCode,
        String requestMetadataJson
) {
}
