package com.kama.jchatmind.webhook;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
    void shouldAllowSignatureVerificationBeforeReservingTheEventId() {
        InMemoryWebhookEventIdRegistry registry = new InMemoryWebhookEventIdRegistry();
        InboundWebhookVerifier redisVerifier = new InboundWebhookVerifier(CLOCK, registry);
        InboundWebhookEvent event = validEvent("event-deferred-reservation", NOW);

        assertThat(redisVerifier.verifySignature(event, SOURCE).status())
                .isEqualTo(WebhookVerificationStatus.ACCEPTED);
        assertThat(redisVerifier.verifySignature(event, SOURCE).status())
                .isEqualTo(WebhookVerificationStatus.ACCEPTED);
        assertThat(redisVerifier.reserveVerifiedEventId(event)).isTrue();
        assertThat(redisVerifier.reserveVerifiedEventId(event)).isFalse();
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
    void shouldRejectAnEventIdAlreadyReservedByAnotherVerifierInstance() throws Exception {
        assumeTrue(Boolean.getBoolean("g4.webhook.redis.l2"));
        int port = Integer.getInteger("g4.webhook.redis.port", 0);
        assumeTrue(port >= 49152 && port <= 65535);

        LettuceConnectionFactory firstFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", port)
        );
        LettuceConnectionFactory secondFactory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration("127.0.0.1", port)
        );
        firstFactory.afterPropertiesSet();
        secondFactory.afterPropertiesSet();
        StringRedisTemplate firstTemplate = new StringRedisTemplate(firstFactory);
        StringRedisTemplate secondTemplate = new StringRedisTemplate(secondFactory);
        firstTemplate.afterPropertiesSet();
        secondTemplate.afterPropertiesSet();
        String eventId = "event-shared-" + UUID.randomUUID();
        Set<String> redisKeys = null;

        try {
            InboundWebhookVerifier firstInstance = new InboundWebhookVerifier(
                    CLOCK,
                    new RedisWebhookEventIdRegistry(firstTemplate)
            );
            InboundWebhookVerifier secondInstance = new InboundWebhookVerifier(
                    CLOCK,
                    new RedisWebhookEventIdRegistry(secondTemplate)
            );
            InboundWebhookEvent event = validEvent(eventId, NOW);

            assertThat(firstInstance.verify(event, SOURCE).status()).isEqualTo(WebhookVerificationStatus.ACCEPTED);
            assertThat(secondInstance.verify(event, SOURCE).status()).isEqualTo(WebhookVerificationStatus.DUPLICATE_EVENT);
            assertThat(secondInstance.verify(validEvent(eventId + "-other", NOW), SOURCE).status())
                    .isEqualTo(WebhookVerificationStatus.ACCEPTED);

            redisKeys = firstTemplate.keys("jchatmind:webhook:*:event-id");
            assertThat(redisKeys).hasSize(2);
            for (String redisKey : redisKeys) {
                assertThat(firstTemplate.getExpire(redisKey)).isBetween(1L, 301L);
            }
        } finally {
            if (redisKeys != null && !redisKeys.isEmpty()) {
                firstTemplate.delete(redisKeys);
            } else {
                Set<String> keys = firstTemplate.keys("jchatmind:webhook:*:event-id");
                if (keys != null && !keys.isEmpty()) {
                    firstTemplate.delete(keys);
                }
            }
            firstFactory.destroy();
            secondFactory.destroy();
        }
    }

    @Test
    void shouldKeepReservedEventIdsAfterRedisAofRestart() throws Exception {
        assumeTrue(Boolean.getBoolean("g4.webhook.redis.restart.l2"));

        String suffix = UUID.randomUUID().toString().replace("-", "");
        String containerName = "jchatmind-g4-webhook-aof-" + suffix;
        String volumeName = "jchatmind-g4-webhook-aof-" + suffix;
        int port = findAvailablePort();
        ProcessResult volumeResult = runDocker(
                "volume", "create", "--label", "org.jchatmind.test=g4-webhook-aof", volumeName
        );
        assertThat(volumeResult.exitCode()).withFailMessage(volumeResult.output()).isZero();

        try {
            ProcessResult startResult = runDocker(
                    "run", "--detach", "--name", containerName,
                    "--label", "org.jchatmind.test=g4-webhook-aof",
                    "--publish", "127.0.0.1:" + port + ":6379",
                    "--mount", "type=volume,source=" + volumeName + ",target=/data",
                    "redis:7-alpine",
                    "redis-server", "--appendonly", "yes", "--appendfsync", "always",
                    "--dir", "/data", "--save", ""
            );
            assertThat(startResult.exitCode()).withFailMessage(startResult.output()).isZero();

            ProcessResult portResult = runDocker("port", containerName, "6379/tcp");
            assertThat(portResult.exitCode()).withFailMessage(portResult.output()).isZero();
            assertThat(portResult.output().trim()).isEqualTo("127.0.0.1:" + port);
            awaitRedisPort(port);

            LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(
                    new RedisStandaloneConfiguration("127.0.0.1", port)
            );
            connectionFactory.afterPropertiesSet();
            StringRedisTemplate redisTemplate = new StringRedisTemplate(connectionFactory);
            redisTemplate.afterPropertiesSet();

            try {
                InboundWebhookVerifier redisVerifier = new InboundWebhookVerifier(
                        CLOCK,
                        new RedisWebhookEventIdRegistry(redisTemplate)
                );
                InboundWebhookEvent event = validEvent("event-aof-" + suffix, NOW);

                assertThat(redisVerifier.verify(event, SOURCE).status())
                        .isEqualTo(WebhookVerificationStatus.ACCEPTED);

                ProcessResult restartResult = runDocker("restart", "--timeout", "5", containerName);
                assertThat(restartResult.exitCode()).withFailMessage(restartResult.output()).isZero();
                ProcessResult restartedPortResult = runDocker("port", containerName, "6379/tcp");
                assertThat(restartedPortResult.exitCode()).withFailMessage(restartedPortResult.output()).isZero();
                assertThat(restartedPortResult.output().trim()).isEqualTo("127.0.0.1:" + port);
                awaitRedisPort(port);

                assertThat(redisVerifier.verify(event, SOURCE).status())
                        .isEqualTo(WebhookVerificationStatus.DUPLICATE_EVENT);
                InboundWebhookEvent eventAcceptedAfterGracefulRestart = validEvent(
                        "event-aof-new-" + suffix,
                        NOW
                );
                assertThat(redisVerifier.verify(eventAcceptedAfterGracefulRestart, SOURCE).status())
                        .isEqualTo(WebhookVerificationStatus.ACCEPTED);

                ProcessResult killResult = runDocker("kill", "--signal", "KILL", containerName);
                assertThat(killResult.exitCode()).withFailMessage(killResult.output()).isZero();
                ProcessResult startAfterKillResult = runDocker("start", containerName);
                assertThat(startAfterKillResult.exitCode()).withFailMessage(startAfterKillResult.output()).isZero();
                ProcessResult afterKillPortResult = runDocker("port", containerName, "6379/tcp");
                assertThat(afterKillPortResult.exitCode()).withFailMessage(afterKillPortResult.output()).isZero();
                assertThat(afterKillPortResult.output().trim()).isEqualTo("127.0.0.1:" + port);
                awaitRedisPort(port);

                assertThat(redisVerifier.verify(event, SOURCE).status())
                        .isEqualTo(WebhookVerificationStatus.DUPLICATE_EVENT);
                assertThat(redisVerifier.verify(eventAcceptedAfterGracefulRestart, SOURCE).status())
                        .isEqualTo(WebhookVerificationStatus.DUPLICATE_EVENT);
                assertThat(redisVerifier.verify(validEvent("event-aof-after-kill-" + suffix, NOW), SOURCE).status())
                        .isEqualTo(WebhookVerificationStatus.ACCEPTED);
            } finally {
                connectionFactory.destroy();
            }
        } finally {
            ProcessResult containerCleanup = runDocker("rm", "--force", containerName);
            assertThat(containerCleanup.exitCode()).withFailMessage(containerCleanup.output()).isZero();
            ProcessResult volumeCleanup = runDocker("volume", "rm", volumeName);
            assertThat(volumeCleanup.exitCode()).withFailMessage(volumeCleanup.output()).isZero();
        }
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

    private static ProcessResult runDocker(String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Docker command did not finish within 30 seconds");
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new ProcessResult(process.exitValue(), output);
    }

    private static void awaitRedisPort(int port) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
                return;
            } catch (IOException exception) {
                Thread.sleep(100);
            }
        }
        throw new IllegalStateException("Isolated Redis did not become ready on port " + port);
    }

    private static int findAvailablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private record ProcessResult(int exitCode, String output) {
    }
}
