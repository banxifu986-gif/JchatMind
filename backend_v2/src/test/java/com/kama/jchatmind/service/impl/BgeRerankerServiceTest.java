package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BgeRerankerServiceTest {

    @Test
    void shouldNotCallRerankEndpointWhenDisabled() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/rerank", exchange -> requestCount.incrementAndGet());
        server.start();

        try {
            BgeRerankerService service = new BgeRerankerService(
                    WebClient.builder(),
                    false,
                    "http://localhost:" + server.getAddress().getPort(),
                    1_000
            );

            List<Double> scores = service.rerank("什么是 RRF", List.of("RRF 是倒数排名融合。"));

            assertEquals(List.of(), scores);
            assertEquals(0, requestCount.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldSendTeiRequestAndRestoreScoresToCandidateOrder() throws Exception {
        AtomicReference<String> requestMethod = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/rerank", exchange -> {
            requestMethod.set(exchange.getRequestMethod());
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "[{\"index\":1,\"score\":0.93},{\"index\":0,\"score\":0.41}]"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();

        try {
            BgeRerankerService service = new BgeRerankerService(
                    WebClient.builder(),
                    true,
                    "http://localhost:" + server.getAddress().getPort(),
                    1_000
            );

            List<Double> scores = service.rerank("什么是 RRF", List.of("向量检索", "RRF 是倒数排名融合。"));

            assertEquals(List.of(0.41D, 0.93D), scores);
            assertEquals("POST", requestMethod.get());
            JsonNode body = new ObjectMapper().readTree(requestBody.get());
            assertEquals("什么是 RRF", body.path("query").asText());
            assertEquals(List.of("向量检索", "RRF 是倒数排名融合。"),
                    body.path("texts").valueStream().map(JsonNode::asText).toList());
        } finally {
            server.stop(0);
        }
    }
}
