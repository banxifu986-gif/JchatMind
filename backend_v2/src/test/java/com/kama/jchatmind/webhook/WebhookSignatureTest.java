package com.kama.jchatmind.webhook;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WebhookSignatureTest {

    @Test
    void shouldMatchTheSharedInboundAndOutboundSignatureVector() {
        String signature = WebhookSignature.sign(
                "build-system",
                "event-vector",
                Instant.parse("2026-08-25T09:00:00Z"),
                "payload",
                "contract-test-signing-key".getBytes(StandardCharsets.UTF_8)
        );

        assertThat(signature).isEqualTo("9bf26f4f93cf781ba3cba92464aa546d7dd676047530a788a69d58b8943de62c");
    }
}
