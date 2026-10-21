package com.kama.jchatmind.webhook;

import com.kama.jchatmind.exception.BizException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class WebhookApprovalOutboxService {

    private static final String INSERT_SQL = """
            INSERT INTO webhook_delivery_outbox (
                outbox_id, callback_event_id, source_id, event_type, aggregate_id,
                event_payload, payload_sha256
            ) VALUES (?, ?, ?, 'APPROVAL_PENDING', CAST(? AS UUID), ?, ?)
            ON CONFLICT (source_id, aggregate_id, event_type) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;
    private final WebhookProperties webhookProperties;

    public WebhookApprovalOutboxService(
            JdbcTemplate jdbcTemplate,
            WebhookProperties webhookProperties
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.webhookProperties = webhookProperties;
    }

    public void enqueue(String sourceId, Long ownerId, String approvalId, String sessionId) {
        WebhookProperties.Source source;
        try {
            source = webhookProperties.requireSource(sourceId);
        } catch (IllegalStateException exception) {
            throw new BizException("无权访问 Webhook 来源");
        }
        if (ownerId == null || !ownerId.equals(source.getOwnerId())) {
            throw new BizException("无权访问 Webhook 来源");
        }
        WebhookDeliveryEventPayloadFactory.Payload payload =
                WebhookDeliveryEventPayloadFactory.createApprovalPending(approvalId, ownerId, sessionId);
        jdbcTemplate.update(
                INSERT_SQL,
                UUID.randomUUID(),
                payload.callbackEventId(),
                sourceId,
                approvalId,
                payload.json(),
                payload.sha256()
        );
    }
}
