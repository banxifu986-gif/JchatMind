package com.kama.jchatmind.webhook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class InboundWebhookVerifier {

    private static final Duration MAX_TIMESTAMP_OFFSET = Duration.ofMinutes(5);
    private static final byte SIGNATURE_SCHEMA_VERSION = 1;

    private final Clock clock;
    private final WebhookEventIdRegistry eventIdRegistry;

    public InboundWebhookVerifier(Clock clock, WebhookEventIdRegistry eventIdRegistry) {
        this.clock = clock;
        this.eventIdRegistry = eventIdRegistry;
    }

    public WebhookVerificationResult verify(InboundWebhookEvent event, WebhookSource source) {
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
        if (Duration.between(event.timestamp(), Instant.now(clock)).abs().compareTo(MAX_TIMESTAMP_OFFSET) > 0) {
            return WebhookVerificationResult.rejected(WebhookVerificationStatus.TIMESTAMP_OUT_OF_WINDOW);
        }
        if (!hasValidSignature(event, signingKey)) {
            return WebhookVerificationResult.rejected(WebhookVerificationStatus.INVALID_SIGNATURE);
        }
        if (!eventIdRegistry.reserve(event.sourceId(), event.eventId())) {
            return WebhookVerificationResult.rejected(WebhookVerificationStatus.DUPLICATE_EVENT);
        }
        return WebhookVerificationResult.accepted();
    }

    private boolean hasValidSignature(InboundWebhookEvent event, byte[] signingKey) {
        byte[] signatureInput = signatureInput(event);
        String expectedSignature = calculateSignature(signatureInput, signingKey);
        return MessageDigest.isEqual(
                expectedSignature.getBytes(StandardCharsets.US_ASCII),
                event.signature().getBytes(StandardCharsets.US_ASCII)
        );
    }

    private byte[] signatureInput(InboundWebhookEvent event) {
        byte[] sourceId = event.sourceId().getBytes(StandardCharsets.UTF_8);
        byte[] eventId = event.eventId().getBytes(StandardCharsets.UTF_8);
        byte[] payload = event.payload().getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(
                        Byte.BYTES + Integer.BYTES + sourceId.length + Integer.BYTES + eventId.length
                                + Long.BYTES + Integer.BYTES + payload.length
                )
                .order(ByteOrder.BIG_ENDIAN);
        buffer.put(SIGNATURE_SCHEMA_VERSION);
        putLengthPrefixedText(buffer, sourceId);
        putLengthPrefixedText(buffer, eventId);
        buffer.putLong(event.timestamp().getEpochSecond());
        putLengthPrefixedText(buffer, payload);
        return buffer.array();
    }

    private void putLengthPrefixedText(ByteBuffer buffer, byte[] value) {
        buffer.putInt(value.length);
        buffer.put(value);
    }

    private String calculateSignature(byte[] signatureInput, byte[] signingKey) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(signatureInput));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("无法初始化 Webhook 签名验证器", exception);
        }
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
    boolean reserve(String sourceId, String eventId);
}

final class InMemoryWebhookEventIdRegistry implements WebhookEventIdRegistry {

    private final Set<WebhookEventKey> reservedEventIds = ConcurrentHashMap.newKeySet();

    @Override
    public boolean reserve(String sourceId, String eventId) {
        return reservedEventIds.add(new WebhookEventKey(sourceId, eventId));
    }

    private record WebhookEventKey(String sourceId, String eventId) {
    }
}
