package com.kama.jchatmind.webhook;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Component
public class WebhookDeliveryDispatcher {

    private static final int CLAIM_BATCH_SIZE = 10;
    private static final Duration CLAIM_LEASE = Duration.ofSeconds(30);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);
    private static final String CLAIM_SQL = """
            WITH candidate AS (
                SELECT outbox_id
                FROM webhook_delivery_outbox
                WHERE delivery_status IN ('PENDING', 'RETRYING')
                  AND next_attempt_at <= CURRENT_TIMESTAMP
                  AND attempt_count < max_attempts
                ORDER BY next_attempt_at ASC, created_at ASC
                FOR UPDATE SKIP LOCKED
                LIMIT ?
            )
            UPDATE webhook_delivery_outbox delivery
            SET delivery_status = 'RETRYING',
                next_attempt_at = CURRENT_TIMESTAMP + INTERVAL '30 seconds',
                updated_at = CURRENT_TIMESTAMP
            FROM candidate
            WHERE delivery.outbox_id = candidate.outbox_id
            RETURNING delivery.outbox_id, delivery.callback_event_id, delivery.source_id,
                      delivery.event_type, delivery.aggregate_id, delivery.event_payload,
                      delivery.payload_sha256, delivery.attempt_count, delivery.max_attempts
            """;
    private static final String MARK_DELIVERED_SQL = """
            UPDATE webhook_delivery_outbox
            SET delivery_status = 'DELIVERED',
                attempt_count = attempt_count + 1,
                last_http_status = ?,
                last_error_code = NULL,
                delivered_at = CURRENT_TIMESTAMP,
                updated_at = CURRENT_TIMESTAMP
            WHERE outbox_id = ?
              AND delivery_status = 'RETRYING'
              AND attempt_count = ?
            """;
    private static final String MARK_FAILED_SQL = """
            UPDATE webhook_delivery_outbox
            SET delivery_status = ?,
                attempt_count = ?,
                next_attempt_at = ?,
                last_http_status = ?,
                last_error_code = ?,
                dead_lettered_at = ?,
                updated_at = CURRENT_TIMESTAMP
            WHERE outbox_id = ?
              AND delivery_status = 'RETRYING'
              AND attempt_count = ?
            """;

    private final JdbcTemplate jdbcTemplate;
    private final WebhookProperties webhookProperties;
    private final HttpClient httpClient;
    private final Clock clock;

    @Autowired
    public WebhookDeliveryDispatcher(JdbcTemplate jdbcTemplate, WebhookProperties webhookProperties) {
        this(
                jdbcTemplate,
                webhookProperties,
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(3))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build(),
                Clock.systemUTC()
        );
    }

    WebhookDeliveryDispatcher(
            JdbcTemplate jdbcTemplate,
            WebhookProperties webhookProperties,
            HttpClient httpClient,
            Clock clock
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.webhookProperties = webhookProperties;
        this.httpClient = httpClient;
        this.clock = clock;
    }

    @Scheduled(fixedDelay = 1000)
    public void dispatchDueCallbacks() {
        List<ClaimedWebhookDelivery> deliveries = jdbcTemplate.query(
                CLAIM_SQL,
                (resultSet, rowNumber) -> new ClaimedWebhookDelivery(
                        UUID.fromString(resultSet.getString("outbox_id")),
                        UUID.fromString(resultSet.getString("callback_event_id")),
                        resultSet.getString("source_id"),
                        resultSet.getString("event_type"),
                        UUID.fromString(resultSet.getString("aggregate_id")),
                        resultSet.getString("event_payload"),
                        resultSet.getString("payload_sha256"),
                        resultSet.getInt("attempt_count"),
                        resultSet.getInt("max_attempts")
                ),
                CLAIM_BATCH_SIZE
        );
        deliveries.forEach(this::deliverClaimed);
    }

    void deliverClaimed(ClaimedWebhookDelivery delivery) {
        if (!hasValidPayloadHash(delivery)) {
            markFailed(delivery, "INVALID_PAYLOAD_HASH", null);
            return;
        }
        WebhookProperties.Source source;
        try {
            source = webhookProperties.requireSource(delivery.sourceId());
        } catch (RuntimeException exception) {
            markFailed(delivery, "SOURCE_CONFIG", null);
            return;
        }

        Instant timestamp = Instant.now(clock).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        String signature = WebhookSignature.sign(
                delivery.sourceId(),
                delivery.callbackEventId().toString(),
                timestamp,
                delivery.eventPayload(),
                source.outboundSigningKey()
        );
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(source.getCallbackUrl()))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("X-Webhook-Event-Id", delivery.callbackEventId().toString())
                    .header("X-Webhook-Timestamp", Long.toString(timestamp.getEpochSecond()))
                    .header("X-Webhook-Signature", signature)
                    .POST(HttpRequest.BodyPublishers.ofString(delivery.eventPayload(), StandardCharsets.UTF_8))
                    .build();
        } catch (RuntimeException exception) {
            markFailed(delivery, "SOURCE_CONFIG", null);
            return;
        }

        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                jdbcTemplate.update(MARK_DELIVERED_SQL, response.statusCode(), delivery.outboxId(), delivery.attemptCount());
            } else {
                markFailed(delivery, "HTTP_STATUS", response.statusCode());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            markFailed(delivery, "INTERRUPTED", null);
        } catch (java.net.http.HttpTimeoutException exception) {
            markFailed(delivery, "TIMEOUT", null);
        } catch (java.io.IOException | RuntimeException exception) {
            markFailed(delivery, "NETWORK_ERROR", null);
        }
    }

    private void markFailed(ClaimedWebhookDelivery delivery, String errorCode, Integer httpStatus) {
        int nextAttempt = delivery.attemptCount() + 1;
        boolean deadLetter = nextAttempt >= delivery.maxAttempts();
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), clock.getZone());
        long backoffMinutes = Math.min(60L, 1L << Math.min(nextAttempt - 1, 6));
        LocalDateTime nextAttemptAt = deadLetter ? now : now.plusMinutes(backoffMinutes);
        jdbcTemplate.update(
                MARK_FAILED_SQL,
                deadLetter ? "DEAD_LETTER" : "RETRYING",
                nextAttempt,
                nextAttemptAt,
                httpStatus,
                errorCode,
                deadLetter ? now : null,
                delivery.outboxId(),
                delivery.attemptCount()
        );
    }

    private boolean hasValidPayloadHash(ClaimedWebhookDelivery delivery) {
        String expected = sha256(delivery.eventPayload());
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                delivery.payloadSha256().getBytes(StandardCharsets.US_ASCII)
        );
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }
}

record ClaimedWebhookDelivery(
        UUID outboxId,
        UUID callbackEventId,
        String sourceId,
        String eventType,
        UUID aggregateId,
        String eventPayload,
        String payloadSha256,
        int attemptCount,
        int maxAttempts
) {
}
