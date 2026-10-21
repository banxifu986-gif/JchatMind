package com.kama.jchatmind.webhook;

import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.model.entity.IngestionTask;
import com.kama.jchatmind.service.impl.IngestionTaskServiceImpl;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class WebhookIngressService {

    private static final String INSERT_INBOX_SQL = """
            INSERT INTO webhook_inbox_event (
                inbox_event_id, source_id, source_event_sha256, owner_id, task_id, payload_sha256
            ) VALUES (?, ?, ?, ?, CAST(? AS UUID), ?)
            ON CONFLICT (source_id, source_event_sha256) DO UPDATE
            SET received_at = webhook_inbox_event.received_at
            WHERE webhook_inbox_event.owner_id = EXCLUDED.owner_id
              AND webhook_inbox_event.task_id = EXCLUDED.task_id
            """;

    private final IngestionTaskServiceImpl ingestionTaskService;
    private final JdbcTemplate jdbcTemplate;
    private final InboundWebhookVerifier webhookVerifier;

    public WebhookIngressService(
            IngestionTaskServiceImpl ingestionTaskService,
            JdbcTemplate jdbcTemplate,
            InboundWebhookVerifier webhookVerifier
    ) {
        this.ingestionTaskService = ingestionTaskService;
        this.jdbcTemplate = jdbcTemplate;
        this.webhookVerifier = webhookVerifier;
    }

    @Transactional
    public IngestionTask submit(
            String sourceId,
            Long ownerId,
            String kbId,
            String documentId,
            String payload,
            InboundWebhookEvent event
    ) {
        String eventHash = RedisWebhookEventIdRegistry.eventIdentityHash(sourceId, event.eventId());
        IngestionTask task = ingestionTaskService.submitDocumentIngestionForOwner(
                ownerId,
                kbId,
                documentId,
                "webhook:" + eventHash
        );
        int inboxRows = jdbcTemplate.update(
                INSERT_INBOX_SQL,
                UUID.randomUUID(),
                sourceId,
                eventHash,
                ownerId,
                task.getId(),
                sha256(payload)
        );
        if (inboxRows != 1) {
            throw new BizException("Webhook event mapping conflicts with an existing event");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                webhookVerifier.reserveVerifiedEventId(event);
            }
        });
        return task;
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
