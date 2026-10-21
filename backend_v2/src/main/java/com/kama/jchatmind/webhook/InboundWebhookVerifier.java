package com.kama.jchatmind.webhook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class InboundWebhookVerifier {

    private static final Duration MAX_TIMESTAMP_OFFSET = Duration.ofMinutes(5);
    private static final Duration EVENT_ID_RETENTION_GRACE = Duration.ofSeconds(1);

    private final Clock clock;
    private final WebhookEventIdRegistry eventIdRegistry;

    public InboundWebhookVerifier(Clock clock, WebhookEventIdRegistry eventIdRegistry) {
        this.clock = clock;
        this.eventIdRegistry = eventIdRegistry;
    }

    public WebhookVerificationResult verify(InboundWebhookEvent event, WebhookSource source) {
        WebhookVerificationResult result = verifySignature(event, source);
        if (result.status() != WebhookVerificationStatus.ACCEPTED) {
            return result;
        }
        if (!reserveVerifiedEventId(event)) {
            return WebhookVerificationResult.rejected(WebhookVerificationStatus.DUPLICATE_EVENT);
        }
        return result;
    }

    WebhookVerificationResult verifySignature(InboundWebhookEvent event, WebhookSource source) {
        if (event == null || source == null) {
            return WebhookVerificationResult.rejected(WebhookVerificationStatus.INVALID_REQUEST);
        }
        byte[] signingKey = source.signingKey();
        if (!hasText(event.sourceId())
                || !hasText(event.eventId())
                || event.timestamp() == null
                || event.timestamp().getNano() != 0
                || !hasText(event.signature())
                || event.payload() == null
                || !hasText(source.sourceId())
                || signingKey == null
                || signingKey.length == 0) {
            return WebhookVerificationResult.rejected(WebhookVerificationStatus.INVALID_REQUEST);
        }
        if (!event.sourceId().equals(source.sourceId())) {
            return WebhookVerificationResult.rejected(WebhookVerificationStatus.SOURCE_MISMATCH);
        }
        Instant now = Instant.now(clock);
        if (Duration.between(event.timestamp(), now).abs().compareTo(MAX_TIMESTAMP_OFFSET) > 0) {
            return WebhookVerificationResult.rejected(WebhookVerificationStatus.TIMESTAMP_OUT_OF_WINDOW);
        }
        if (!hasValidSignature(event, signingKey)) {
            return WebhookVerificationResult.rejected(WebhookVerificationStatus.INVALID_SIGNATURE);
        }
        return WebhookVerificationResult.accepted();
    }

    boolean reserveVerifiedEventId(InboundWebhookEvent event) {
        if (event == null
                || !hasText(event.sourceId())
                || !hasText(event.eventId())
                || event.timestamp() == null
                || event.timestamp().getNano() != 0) {
            return false;
        }
        Instant now = Instant.now(clock);
        Duration retention = Duration.between(now, event.timestamp().plus(MAX_TIMESTAMP_OFFSET))
                .plus(EVENT_ID_RETENTION_GRACE);
        if (retention.isNegative() || retention.isZero()) {
            return true;
        }
        if (!eventIdRegistry.reserve(event.sourceId(), event.eventId(), retention)) {
            return false;
        }
        return true;
    }

    private boolean hasValidSignature(InboundWebhookEvent event, byte[] signingKey) {
        String expectedSignature = WebhookSignature.sign(
                event.sourceId(),
                event.eventId(),
                event.timestamp(),
                event.payload(),
                signingKey
        );
        return MessageDigest.isEqual(
                expectedSignature.getBytes(StandardCharsets.US_ASCII),
                event.signature().getBytes(StandardCharsets.US_ASCII)
        );
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}

record InboundWebhookEvent(
        String sourceId,
        String eventId,
        Instant timestamp,
        String signature,
        String payload
) {
}

record WebhookSource(String sourceId, byte[] signingKey) {
    WebhookSource {
        signingKey = signingKey == null ? null : signingKey.clone();
    }

    @Override
    public byte[] signingKey() {
        return signingKey == null ? null : signingKey.clone();
    }
}

record WebhookVerificationResult(WebhookVerificationStatus status) {
    static WebhookVerificationResult accepted() {
        return new WebhookVerificationResult(WebhookVerificationStatus.ACCEPTED);
    }

    static WebhookVerificationResult rejected(WebhookVerificationStatus status) {
        return new WebhookVerificationResult(status);
    }
}

enum WebhookVerificationStatus {
    ACCEPTED,
    INVALID_REQUEST,
    SOURCE_MISMATCH,
    INVALID_SIGNATURE,
    TIMESTAMP_OUT_OF_WINDOW,
    DUPLICATE_EVENT
}

interface WebhookEventIdRegistry {
    boolean reserve(String sourceId, String eventId, Duration retention);
}

final class InMemoryWebhookEventIdRegistry implements WebhookEventIdRegistry {

    private final Set<WebhookEventKey> reservedEventIds = ConcurrentHashMap.newKeySet();

    @Override
    public boolean reserve(String sourceId, String eventId, Duration retention) {
        return reservedEventIds.add(new WebhookEventKey(sourceId, eventId));
    }

    private record WebhookEventKey(String sourceId, String eventId) {
    }
}
