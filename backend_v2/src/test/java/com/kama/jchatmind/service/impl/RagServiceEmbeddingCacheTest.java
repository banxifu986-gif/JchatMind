package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.service.QueryRewriteService;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RagServiceEmbeddingCacheTest {

    @Test
    void shouldCacheEmbeddingsWithinOneModelInstanceWithoutSharingAcrossModels() throws Exception {
        HttpServer embeddingServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        List<String> requestedModels = Collections.synchronizedList(new ArrayList<>());
        ObjectMapper objectMapper = new ObjectMapper();
        embeddingServer.createContext("/api/embed", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String model = objectMapper.readTree(requestBody).path("model").asText();
            requestedModels.add(model);
            requests.incrementAndGet();
            String vector = "model-a".equals(model) ? "[0.1,0.2]" : "[0.8,0.9]";
            byte[] response = ("{\"embeddings\":[" + vector + "]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        embeddingServer.start();

        try {
            String baseUrl = "http://127.0.0.1:" + embeddingServer.getAddress().getPort();
            RagServiceImpl modelA = newService(baseUrl, "model-a");
            RagServiceImpl modelB = newService(baseUrl, "model-b");

            float[] firstModelAResult = modelA.embed(" repeated query ");
            firstModelAResult[0] = 99F;
            float[] cachedModelAResult = modelA.embed("repeated query");
            float[] modelBResult = modelB.embed("repeated query");

            assertThat(cachedModelAResult).containsExactly(0.1F, 0.2F);
            assertThat(modelBResult).containsExactly(0.8F, 0.9F);
            assertThat(requests).hasValue(2);
            assertThat(requestedModels).containsExactly("model-a", "model-b");
        } finally {
            embeddingServer.stop(0);
        }
    }

    @Test
    void shouldSkipCacheWhenCapacityIsZero() throws Exception {
        HttpServer embeddingServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        embeddingServer.createContext("/api/embed", exchange -> {
            requests.incrementAndGet();
            byte[] response = "{\"embeddings\":[[0.1,0.2]]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        embeddingServer.start();

        try {
            String baseUrl = "http://127.0.0.1:" + embeddingServer.getAddress().getPort();
            RagServiceImpl service = newService(baseUrl, "model-a", 0);

            assertThat(service.embed("same query")).containsExactly(0.1F, 0.2F);
            assertThat(service.embed("same query")).containsExactly(0.1F, 0.2F);
            assertThat(requests).hasValue(2);
        } finally {
            embeddingServer.stop(0);
        }
    }

    private static RagServiceImpl newService(String baseUrl, String embeddingModel) {
        return newService(baseUrl, embeddingModel, 8);
    }

    private static RagServiceImpl newService(String baseUrl, String embeddingModel, int cacheMaxEntries) {
        return new RagServiceImpl(
                WebClient.builder(),
                mock(ChunkBgeM3Mapper.class),
                mock(QueryRewriteService.class),
                mock(VchordBm25QueryService.class),
                mock(BgeRerankerService.class),
                baseUrl,
                embeddingModel,
                false,
                false,
                false,
                cacheMaxEntries
        );
    }
}
