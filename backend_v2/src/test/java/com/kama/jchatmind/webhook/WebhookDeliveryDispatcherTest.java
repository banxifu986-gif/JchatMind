package com.kama.jchatmind.webhook;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebhookDeliveryDispatcherTest {

    @Test
    void shouldPostSignedCallbackAndMarkOutboxRowDelivered() throws Exception {
        AtomicReference<String> signatureHeader = new AtomicReference<>();
        AtomicReference<String> timestampHeader = new AtomicReference<>();
        AtomicReference<String> eventIdHeader = new AtomicReference<>();
        AtomicReference<String> postedBody = new AtomicReference<>();
        HttpServer server = server(exchange -> {
            signatureHeader.set(exchange.getRequestHeaders().getFirst("X-Webhook-Signature"));
            timestampHeader.set(exchange.getRequestHeaders().getFirst("X-Webhook-Timestamp"));
            eventIdHeader.set(exchange.getRequestHeaders().getFirst("X-Webhook-Event-Id"));
            postedBody.set(new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        try {
            JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
            String callbackUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/callback";
            WebhookProperties properties = properties(callbackUrl);
            Clock clock = Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"), ZoneOffset.UTC);
            WebhookDeliveryDispatcher dispatcher = new WebhookDeliveryDispatcher(
                    jdbcTemplate, properties, HttpClient.newHttpClient(), clock
            );
            ClaimedWebhookDelivery delivery = delivery(0, 5);

            dispatcher.deliverClaimed(delivery);

            assertThat(eventIdHeader.get()).isEqualTo(delivery.callbackEventId().toString());
            assertThat(timestampHeader.get()).isEqualTo("1792540800");
            assertThat(postedBody.get()).isEqualTo(delivery.eventPayload());
            assertThat(signatureHeader.get()).isEqualTo(WebhookSignature.sign(
                    delivery.sourceId(),
                    delivery.callbackEventId().toString(),
                    clock.instant(),
                    delivery.eventPayload(),
                    "outbound-secret".getBytes(StandardCharsets.UTF_8)
            ));
            var sqlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(jdbcTemplate).update(sqlCaptor.capture(), any(Object[].class));
            assertThat(sqlCaptor.getValue()).contains("DELIVERED");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldMoveFinalFailedAttemptToDeadLetter() throws Exception {
        HttpServer server = server(exchange -> {
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        try {
            JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
            WebhookDeliveryDispatcher dispatcher = new WebhookDeliveryDispatcher(
                    jdbcTemplate,
                    properties("http://127.0.0.1:" + server.getAddress().getPort() + "/callback"),
                    HttpClient.newHttpClient(),
                    Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"), ZoneOffset.UTC)
            );

            dispatcher.deliverClaimed(delivery(0, 1));

            var sqlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
            var argsCaptor = org.mockito.ArgumentCaptor.forClass(Object[].class);
            verify(jdbcTemplate).update(sqlCaptor.capture(), argsCaptor.capture());
            assertThat(sqlCaptor.getValue()).contains("delivery_status = ?");
            assertThat(argsCaptor.getValue()[0]).isEqualTo("DEAD_LETTER");
            assertThat(argsCaptor.getValue()[3]).isEqualTo(503);
            assertThat(argsCaptor.getValue()[4]).isEqualTo("HTTP_STATUS");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldScheduleFiniteRetryWithStableCallbackEventIdAndBoundedBackoff() throws Exception {
        AtomicReference<String> callbackEventId = new AtomicReference<>();
        HttpServer server = server(exchange -> {
            callbackEventId.set(exchange.getRequestHeaders().getFirst("X-Webhook-Event-Id"));
            exchange.sendResponseHeaders(503, -1);
            exchange.close();
        });
        try {
            JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
            when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
            Clock clock = Clock.fixed(Instant.parse("2026-10-21T00:00:00Z"), ZoneOffset.UTC);
            WebhookDeliveryDispatcher dispatcher = new WebhookDeliveryDispatcher(
                    jdbcTemplate,
                    properties("http://127.0.0.1:" + server.getAddress().getPort() + "/callback"),
                    HttpClient.newHttpClient(),
                    clock
            );
            ClaimedWebhookDelivery first = delivery(0, 5);

            dispatcher.deliverClaimed(first);
            dispatcher.deliverClaimed(new ClaimedWebhookDelivery(
                    first.outboxId(), first.callbackEventId(), first.sourceId(), first.eventType(),
                    first.aggregateId(), first.eventPayload(), first.payloadSha256(), 1, 5
            ));

            var sqlCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
            var argsCaptor = org.mockito.ArgumentCaptor.forClass(Object[].class);
            verify(jdbcTemplate, org.mockito.Mockito.times(2)).update(sqlCaptor.capture(), argsCaptor.capture());
            assertThat(sqlCaptor.getAllValues()).allSatisfy(sql -> assertThat(sql).contains("delivery_status = ?"));
            assertThat(argsCaptor.getAllValues()).extracting(args -> args[0]).containsExactly("RETRYING", "RETRYING");
            assertThat(argsCaptor.getAllValues()).extracting(args -> args[1]).containsExactly(1, 2);
            assertThat((java.time.LocalDateTime) argsCaptor.getAllValues().get(0)[2])
                    .isEqualTo(java.time.LocalDateTime.of(2026, 10, 21, 0, 1));
            assertThat((java.time.LocalDateTime) argsCaptor.getAllValues().get(1)[2])
                    .isEqualTo(java.time.LocalDateTime.of(2026, 10, 21, 0, 2));
            assertThat(callbackEventId.get()).isEqualTo(first.callbackEventId().toString());
        } finally {
            server.stop(0);
        }
    }

    private HttpServer server(com.sun.net.httpserver.HttpHandler handler) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/callback", handler);
        server.start();
        return server;
    }

    private WebhookProperties properties(String callbackUrl) {
        WebhookProperties properties = new WebhookProperties();
        WebhookProperties.Source source = new WebhookProperties.Source();
        source.setOwnerId(7L);
        source.setAllowedKbIds(Set.of("kb-1"));
        source.setInboundSigningKey("inbound-secret");
        source.setCallbackUrl(callbackUrl);
        source.setOutboundSigningKey("outbound-secret");
        properties.getSources().put("build-system", source);
        return properties;
    }

    private ClaimedWebhookDelivery delivery(int attempts, int maxAttempts) throws Exception {
        return new ClaimedWebhookDelivery(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "build-system",
                "INGESTION_SUCCEEDED",
                UUID.randomUUID(),
                "{\"eventType\":\"INGESTION_SUCCEEDED\"}",
                sha256("{\"eventType\":\"INGESTION_SUCCEEDED\"}"),
                attempts,
                maxAttempts
        );
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
