package com.kama.jchatmind.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.model.entity.IngestionTask;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class WebhookDeliveryEventPayloadFactory {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> EVENT_TYPES = Set.of(
            "INGESTION_SUCCEEDED",
            "INGESTION_FAILED",
            "APPROVAL_PENDING"
    );

    private WebhookDeliveryEventPayloadFactory() {
    }

    public static Payload create(IngestionTask task, String eventType) {
        if (task == null || !EVENT_TYPES.contains(eventType)) {
            throw new IllegalArgumentException("Webhook delivery event is invalid");
        }
        UUID callbackEventId = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", callbackEventId.toString());
        payload.put("eventType", eventType);
        payload.put("taskId", task.getId());
        payload.put("status", task.getStatus());
        payload.put("ownerId", task.getOwnerId());
        payload.put("kbId", task.getKbId());
        payload.put("documentId", task.getDocumentId());
        String json;
        try {
            json = OBJECT_MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法生成 Webhook delivery metadata", exception);
        }
        return new Payload(callbackEventId, json, sha256(json));
    }

    public static Payload createApprovalPending(String approvalId, Long ownerId, String sessionId) {
        UUID approvalUuid;
        try {
            approvalUuid = UUID.fromString(approvalId);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new IllegalArgumentException("Webhook approval event is invalid");
        }
        if (ownerId == null || ownerId <= 0 || sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("Webhook approval event is invalid");
        }
        UUID callbackEventId = UUID.randomUUID();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("eventId", callbackEventId.toString());
        payload.put("eventType", "APPROVAL_PENDING");
        payload.put("approvalId", approvalUuid.toString());
        payload.put("ownerId", ownerId);
        payload.put("sessionId", sessionId);
        try {
            String json = OBJECT_MAPPER.writeValueAsString(payload);
            return new Payload(callbackEventId, json, sha256(json));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法生成 Webhook delivery metadata", exception);
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
    public record Payload(UUID callbackEventId, String json, String sha256) {
    }
}
