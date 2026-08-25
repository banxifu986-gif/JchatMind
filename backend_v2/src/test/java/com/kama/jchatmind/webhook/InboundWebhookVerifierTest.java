package com.kama.jchatmind.webhook;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class InboundWebhookVerifierTest {

    private static final Instant NOW = Instant.parse("2026-08-25T09:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final WebhookSource SOURCE = new WebhookSource(
            "build-system",
            "contract-test-signing-key".getBytes(StandardCharsets.UTF_8)
    );

    private final InboundWebhookVerifier verifier = new InboundWebhookVerifier(
            CLOCK,
            new InMemoryWebhookEventIdRegistry()
    );

    @Test
    void shouldAcceptAnEventWithTheConfiguredSourceAndValidSignature() {
        WebhookVerificationResult result = verifier.verify(validEvent("event-1", NOW), SOURCE);

        assertThat(result.status()).isEqualTo(WebhookVerificationStatus.ACCEPTED);
    }

    @Test
    void shouldAcceptThePublishedHmacSignatureVector() {
        InboundWebhookEvent event = new InboundWebhookEvent(
                SOURCE.sourceId(),
                "event-vector",
                NOW,
                "9bf26f4f93cf781ba3cba92464aa546d7dd676047530a788a69d58b8943de62c",
                "payload"
        );

        WebhookVerificationResult result = verifier.verify(event, SOURCE);

        assertThat(result.status()).isEqualTo(WebhookVerificationStatus.ACCEPTED);
    }

    @Test
    void shouldRejectAnEventWhoseSourceDoesNotMatchTheConfiguredSource() {
        InboundWebhookEvent event = signedEvent("other-system", "event-1", NOW, "payload");

        WebhookVerificationResult result = verifier.verify(event, SOURCE);

        assertThat(result.status()).isEqualTo(WebhookVerificationStatus.SOURCE_MISMATCH);
    }

    @Test
    void shouldNotReserveAnEventIdWhenTheSignatureIsInvalid() {
        InboundWebhookEvent invalidEvent = new InboundWebhookEvent(
                SOURCE.sourceId(),
                "event-1",
                NOW,
                "invalid-signature",
                "payload"
        );

        WebhookVerificationResult rejected = verifier.verify(invalidEvent, SOURCE);
        WebhookVerificationResult accepted = verifier.verify(validEvent("event-1", NOW), SOURCE);

        assertThat(rejected.status()).isEqualTo(WebhookVerificationStatus.INVALID_SIGNATURE);
        assertThat(accepted.status()).isEqualTo(WebhookVerificationStatus.ACCEPTED);
    }

    @Test
    void shouldRejectARepeatedVerifiedEventId() {
        InboundWebhookEvent event = validEvent("event-1", NOW);

        verifier.verify(event, SOURCE);
        WebhookVerificationResult result = verifier.verify(event, SOURCE);

        assertThat(result.status()).isEqualTo(WebhookVerificationStatus.DUPLICATE_EVENT);
    }

    @Test
    void shouldRejectADifferentEventWhoseFieldsReinterpretAnExistingSignature() {
        long nowEpochSecond = NOW.getEpochSecond();
        InboundWebhookEvent originalEvent = signedEvent(
                SOURCE.sourceId(),
                "event",
                NOW,
                (nowEpochSecond + 1) + ".payload"
        );
        InboundWebhookEvent reinterpretedEvent = new InboundWebhookEvent(
                SOURCE.sourceId(),
                "event." + nowEpochSecond,
                NOW.plusSeconds(1),
                originalEvent.signature(),
                "payload"
        );

        WebhookVerificationResult originalResult = verifier.verify(originalEvent, SOURCE);
        WebhookVerificationResult reinterpretedResult = verifier.verify(reinterpretedEvent, SOURCE);

        assertThat(originalResult.status()).isEqualTo(WebhookVerificationStatus.ACCEPTED);
        assertThat(reinterpretedResult.status()).isEqualTo(WebhookVerificationStatus.INVALID_SIGNATURE);
    }

    @Test
    void shouldRejectANonSecondTimestampWhoseSignatureWasGeneratedForTheSameEpochSecond() {
        InboundWebhookEvent signedEvent = validEvent("event-1", NOW);
        InboundWebhookEvent alteredEvent = new InboundWebhookEvent(
                signedEvent.sourceId(),
                signedEvent.eventId(),
                signedEvent.timestamp().plusNanos(1),
                signedEvent.signature(),
                signedEvent.payload()
        );

        WebhookVerificationResult result = verifier.verify(alteredEvent, SOURCE);

        assertThat(result.status()).isEqualTo(WebhookVerificationStatus.INVALID_REQUEST);
    }

    @Test
    void shouldAcceptATimestampAtTheFiveMinuteWindowBoundary() {
        WebhookVerificationResult result = verifier.verify(
                validEvent("event-1", NOW.minus(Duration.ofMinutes(5))),
                SOURCE
        );

        assertThat(result.status()).isEqualTo(WebhookVerificationStatus.ACCEPTED);
    }

    @Test
    void shouldAcceptATimestampAtTheFutureFiveMinuteWindowBoundary() {
        WebhookVerificationResult result = verifier.verify(
                validEvent("event-1", NOW.plus(Duration.ofMinutes(5))),
                SOURCE
        );

        assertThat(result.status()).isEqualTo(WebhookVerificationStatus.ACCEPTED);
    }

    @Test
    void shouldRejectATimestampOlderThanTheFiveMinuteWindow() {
        WebhookVerificationResult result = verifier.verify(
                validEvent("event-1", NOW.minus(Duration.ofMinutes(5)).minusSeconds(1)),
                SOURCE
        );

        assertThat(result.status()).isEqualTo(WebhookVerificationStatus.TIMESTAMP_OUT_OF_WINDOW);
    }

    @Test
    void shouldRejectATimestampFurtherThanFiveMinutesInTheFuture() {
        WebhookVerificationResult result = verifier.verify(
                validEvent("event-1", NOW.plus(Duration.ofMinutes(5)).plusSeconds(1)),
                SOURCE
        );

        assertThat(result.status()).isEqualTo(WebhookVerificationStatus.TIMESTAMP_OUT_OF_WINDOW);
    }

    private InboundWebhookEvent validEvent(String eventId, Instant timestamp) {
        return signedEvent(SOURCE.sourceId(), eventId, timestamp, "payload");
    }

    private InboundWebhookEvent signedEvent(String sourceId, String eventId, Instant timestamp, String payload) {
        return new InboundWebhookEvent(
                sourceId,
                eventId,
                timestamp,
                sign(signatureInput(sourceId, eventId, timestamp, payload)),
                payload
        );
    }

    private byte[] signatureInput(String sourceId, String eventId, Instant timestamp, String payload) {
        byte[] sourceBytes = sourceId.getBytes(StandardCharsets.UTF_8);
        byte[] eventBytes = eventId.getBytes(StandardCharsets.UTF_8);
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(
                        Byte.BYTES + Integer.BYTES + sourceBytes.length + Integer.BYTES + eventBytes.length
                                + Long.BYTES + Integer.BYTES + payloadBytes.length
                )
                .order(ByteOrder.BIG_ENDIAN);
        buffer.put((byte) 1);
        putLengthPrefixedText(buffer, sourceBytes);
        putLengthPrefixedText(buffer, eventBytes);
        buffer.putLong(timestamp.getEpochSecond());
        putLengthPrefixedText(buffer, payloadBytes);
        return buffer.array();
    }

    private void putLengthPrefixedText(ByteBuffer buffer, byte[] value) {
        buffer.putInt(value.length);
        buffer.put(value);
    }

    private String sign(byte[] signatureInput) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SOURCE.signingKey(), "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(
                    mac.doFinal(signatureInput)
            );
        } catch (GeneralSecurityException exception) {
            throw new AssertionError(exception);
        }
    }
}
