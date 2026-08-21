package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.dto.QueryRewriteResult;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.service.QueryRewriteService;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.IntStream;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagServiceImplTest {

    @Test
    void shouldCallOllamaEmbedEndpointWithInputAndKeepAlive() throws Exception {
        AtomicReference<String> requestPath = new AtomicReference<>();
        AtomicReference<String> requestMethod = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/embed", exchange -> {
            captureRequest(exchange, requestPath, requestMethod, requestBody);
            byte[] response = "{\"model\":\"bge-m3:latest\",\"embeddings\":[[0.1,0.2,0.3]]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });
        server.start();

        try {
            RagServiceImpl service = new RagServiceImpl(
                    WebClient.builder(),
                    mock(ChunkBgeM3Mapper.class),
                    mock(QueryRewriteService.class),
                    "http://localhost:" + server.getAddress().getPort(),
                    "bge-m3:latest",
                    false,
                    false,
                    false,
                    0
            );

            float[] embedding = service.embed("你好");

            assertNotNull(embedding);
            assertArrayEquals(new float[]{0.1F, 0.2F, 0.3F}, embedding);
            assertEquals("/api/embed", requestPath.get());
            assertEquals("POST", requestMethod.get());

            JsonNode requestJson = new ObjectMapper().readTree(requestBody.get());
            assertTrue(requestJson.isObject());
            assertEquals("bge-m3:latest", requestJson.get("model").asText());
            assertEquals("你好", requestJson.get("input").asText());
            assertEquals("-1", requestJson.get("keep_alive").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldLetDeepExactCandidateRiseWithinBoundedRerankBudget() throws Exception {
        HttpServer server = createEmbeddingServer();
        server.start();

        try {
            ChunkBgeM3Mapper mapper = mock(ChunkBgeM3Mapper.class);
            QueryRewriteService rewriteService = mock(QueryRewriteService.class);
            List<RagRetrievalResult> candidates = IntStream.rangeClosed(1, 55)
                    .mapToObj(index -> {
                        RagRetrievalResult result = new RagRetrievalResult();
                        result.setChunkId("chunk-" + index);
                        result.setKbId("kb-1");
                        result.setContent("generic content " + index);
                        result.setDistance(0.5D);
                        result.setRank(index);
                        if (index == 35 || index == 55) {
                            result.setMetadata("{\"retrievableTitle\":\"目标标题\"}");
                        }
                        return result;
                    })
                    .toList();
            when(rewriteService.rewrite(List.of("kb-1"), "目标标题", null))
                    .thenReturn(QueryRewriteResult.builder()
                            .query("目标标题")
                            .retrievalQueries(List.of("目标标题"))
                            .build());
            when(mapper.similaritySearchDetailed(List.of("kb-1"), "[0.1,0.2,0.3]", 55))
                    .thenReturn(candidates);
            when(mapper.selectContentLexicalCandidatesByKbIds(List.of("kb-1")))
                    .thenReturn(List.of());

            RagServiceImpl service = new RagServiceImpl(
                    WebClient.builder(),
                    mapper,
                    rewriteService,
                    "http://localhost:" + server.getAddress().getPort(),
                    "bge-m3:latest",
                    false,
                    false,
                    false,
                    0
            );

            List<RagRetrievalResult> results = service.retrieve(List.of("kb-1"), "目标标题", 55);

            assertEquals("chunk-35", results.get(0).getChunkId());
            assertEquals("chunk-55", results.get(54).getChunkId());
        } finally {
            server.stop(0);
        }
    }

    private static HttpServer createEmbeddingServer() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/embed", exchange -> {
            byte[] response = "{\"model\":\"bge-m3:latest\",\"embeddings\":[[0.1,0.2,0.3]]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response);
            }
        });
        return server;
    }

    private static void captureRequest(
            HttpExchange exchange,
            AtomicReference<String> requestPath,
            AtomicReference<String> requestMethod,
            AtomicReference<String> requestBody
    ) throws IOException {
        requestPath.set(exchange.getRequestURI().getPath());
        requestMethod.set(exchange.getRequestMethod());
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    }
}
