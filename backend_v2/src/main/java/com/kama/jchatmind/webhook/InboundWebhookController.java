package com.kama.jchatmind.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.model.entity.IngestionTask;
import com.kama.jchatmind.model.vo.IngestionTaskVO;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@RestController
public class InboundWebhookController {

    private static final int MAX_BODY_BYTES = 16_384;
    private static final int MAX_EVENT_ID_LENGTH = 256;
    private static final Set<String> PAYLOAD_FIELDS = Set.of("kbId", "documentId");

    private final WebhookProperties webhookProperties;
    private final InboundWebhookVerifier webhookVerifier;
    private final WebhookIngressService webhookIngressService;
    private final ObjectMapper objectMapper;

    public InboundWebhookController(
            WebhookProperties webhookProperties,
            InboundWebhookVerifier webhookVerifier,
            WebhookIngressService webhookIngressService,
            ObjectMapper objectMapper
    ) {
        this.webhookProperties = webhookProperties;
        this.webhookVerifier = webhookVerifier;
        this.webhookIngressService = webhookIngressService;
        this.objectMapper = objectMapper;
    }

    @PostMapping(path = "/api/webhooks/inbound/{sourceId}", consumes = "application/json")
    public ResponseEntity<ApiResponse<IngestionTaskVO>> receive(
            @PathVariable String sourceId,
            @RequestHeader("X-Webhook-Event-Id") String eventId,
            @RequestHeader("X-Webhook-Timestamp") String timestamp,
            @RequestHeader("X-Webhook-Signature") String signature,
            @RequestBody byte[] rawBody
    ) {
        WebhookProperties.Source source;
        try {
            source = webhookProperties.requireSource(sourceId);
        } catch (IllegalStateException exception) {
            HttpStatus status = "Webhook source is not configured".equals(exception.getMessage())
                    ? HttpStatus.NOT_FOUND
                    : HttpStatus.SERVICE_UNAVAILABLE;
            return error(status, "Webhook source unavailable");
        }
        if (rawBody == null || rawBody.length == 0 || rawBody.length > MAX_BODY_BYTES
                || eventId == null || eventId.length() > MAX_EVENT_ID_LENGTH
                || signature == null || signature.length() > 128) {
            return error(rawBody != null && rawBody.length > MAX_BODY_BYTES
                    ? HttpStatus.PAYLOAD_TOO_LARGE
                    : HttpStatus.BAD_REQUEST, "Webhook request is invalid");
        }

        String payload;
        Instant eventTimestamp;
        try {
            payload = decodeUtf8(rawBody);
            eventTimestamp = Instant.ofEpochSecond(Long.parseLong(timestamp));
        } catch (CharacterCodingException | NumberFormatException | DateTimeException exception) {
            return error(HttpStatus.BAD_REQUEST, "Webhook request is invalid");
        }

        InboundWebhookEvent event = new InboundWebhookEvent(sourceId, eventId, eventTimestamp, signature, payload);
        WebhookVerificationResult verification = webhookVerifier.verifySignature(
                event,
                source.toWebhookSource(sourceId)
        );
        if (verification.status() != WebhookVerificationStatus.ACCEPTED) {
            return error(statusFor(verification.status()), "Webhook signature verification failed");
        }

        WebhookIngestionRequest taskRequest;
        try {
            taskRequest = parseTaskRequest(payload);
        } catch (JsonProcessingException | IllegalArgumentException exception) {
            return error(HttpStatus.BAD_REQUEST, "Webhook payload is invalid");
        }
        if (!source.allowsKnowledgeBase(taskRequest.kbId())) {
            return error(HttpStatus.FORBIDDEN, "Webhook source cannot access this knowledge base");
        }

        IngestionTask task;
        try {
            task = webhookIngressService.submit(
                    sourceId,
                    source.getOwnerId(),
                    taskRequest.kbId(),
                    taskRequest.documentId(),
                    payload,
                    event
            );
        } catch (BizException exception) {
            return error(HttpStatus.UNPROCESSABLE_ENTITY, "Webhook task mapping was rejected");
        }

        return ResponseEntity.accepted().body(ApiResponse.success(toVO(task), "accepted"));
    }

    private String decodeUtf8(byte[] rawBody) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(rawBody))
                .toString();
    }

    private WebhookIngestionRequest parseTaskRequest(String payload) throws JsonProcessingException {
        JsonNode root = objectMapper.readTree(payload);
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("Webhook payload must be a JSON object");
        }
        Set<String> fields = new HashSet<>();
        root.fieldNames().forEachRemaining(fields::add);
        String kbId = root.path("kbId").isTextual() ? root.path("kbId").asText() : null;
        String documentId = root.path("documentId").isTextual() ? root.path("documentId").asText() : null;
        if (!fields.equals(PAYLOAD_FIELDS)
                || kbId == null || kbId.isBlank()
                || documentId == null || documentId.isBlank()) {
            throw new IllegalArgumentException("Webhook payload fields are invalid");
        }
        return new WebhookIngestionRequest(kbId, documentId);
    }

    private HttpStatus statusFor(WebhookVerificationStatus status) {
        return switch (status) {
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case SOURCE_MISMATCH, INVALID_SIGNATURE, TIMESTAMP_OUT_OF_WINDOW, DUPLICATE_EVENT ->
                    HttpStatus.UNAUTHORIZED;
            case ACCEPTED -> HttpStatus.ACCEPTED;
        };
    }

    private ResponseEntity<ApiResponse<IngestionTaskVO>> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(ApiResponse.error(message));
    }

    private IngestionTaskVO toVO(IngestionTask task) {
        return IngestionTaskVO.builder()
                .taskId(task.getId())
                .kbId(task.getKbId())
                .documentId(task.getDocumentId())
                .taskType(task.getTaskType())
                .status(task.getStatus())
                .attemptCount(task.getAttemptCount())
                .maxAttempts(task.getMaxAttempts())
                .errorSummary(task.getErrorSummary())
                .createdAt(task.getCreatedAt())
                .updatedAt(task.getUpdatedAt())
                .startedAt(task.getStartedAt())
                .completedAt(task.getCompletedAt())
                .build();
    }

    private record WebhookIngestionRequest(String kbId, String documentId) {
    }
}
