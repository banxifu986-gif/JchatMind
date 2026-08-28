package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

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

    @Test
    void shouldSplitCandidatesAtTheTeiClientBatchLimitAndRestoreGlobalOrder() throws Exception {
        List<List<String>> batches = Collections.synchronizedList(new ArrayList<>());
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/rerank", exchange -> {
            JsonNode request = new ObjectMapper().readTree(exchange.getRequestBody());
            List<String> texts = request.path("texts").valueStream().map(JsonNode::asText).toList();
            batches.add(texts);
            if (texts.size() > 32) {
                exchange.sendResponseHeaders(422, -1);
                exchange.close();
                return;
            }
            StringBuilder response = new StringBuilder("[");
            for (int index = texts.size() - 1; index >= 0; index--) {
                if (index < texts.size() - 1) {
                    response.append(',');
                }
                response.append("{\"index\":").append(index)
                        .append(",\"score\":").append(texts.get(index).substring("candidate-".length()))
                        .append('}');
            }
            response.append(']');
            byte[] body = response.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(body);
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
            List<String> texts = IntStream.range(0, 33).mapToObj(index -> "candidate-" + index).toList();

            List<Double> scores = service.rerank("query", texts);

            assertEquals(IntStream.range(0, 33).mapToDouble(index -> index).boxed().toList(), scores);
            assertEquals(List.of(1, 32), batches.stream().map(List::size).sorted().toList());
            assertEquals(texts, batches.stream().flatMap(List::stream).sorted((left, right) -> Integer.compare(
                    Integer.parseInt(left.substring("candidate-".length())),
                    Integer.parseInt(right.substring("candidate-".length()))
            )).toList());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldLimitCpuTeiClientBatchConcurrencyToOne() throws Exception {
        var field = BgeRerankerService.class.getDeclaredField("TEI_MAX_CONCURRENT_BATCHES");
        field.setAccessible(true);

        assertEquals(1, field.getInt(null));
    }
}
