package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.dto.QueryRewriteResult;
import com.kama.jchatmind.model.dto.RagRetrievalContext;
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
import static org.mockito.Mockito.verifyNoInteractions;
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
                    mock(VchordBm25QueryService.class),
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
            VchordBm25QueryService bm25QueryService = mock(VchordBm25QueryService.class);
            when(bm25QueryService.searchContent(List.of("kb-1"), "目标标题", null, null, null, 120))
                    .thenReturn(List.of());

            RagServiceImpl service = new RagServiceImpl(
                    WebClient.builder(),
                    mapper,
                    rewriteService,
                    bm25QueryService,
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

    @Test
    void shouldPushHardContextIntoEveryTitleFallbackChannel() throws Exception {
        HttpServer server = createEmbeddingServer();
        server.start();

        try {
            ChunkBgeM3Mapper mapper = mock(ChunkBgeM3Mapper.class);
            QueryRewriteService rewriteService = mock(QueryRewriteService.class);
            VchordBm25QueryService bm25QueryService = mock(VchordBm25QueryService.class);
            RagRetrievalContext context = RagRetrievalContext.builder()
                    .kbId("kb-1")
                    .sourceName("architecture.md")
                    .sourceType("md")
                    .contentPath("rag > bm25")
                    .build();
            when(rewriteService.rewrite(List.of("kb-1"), "接口", context))
                    .thenReturn(QueryRewriteResult.builder()
                            .query("接口")
                            .context(context)
                            .titleQuery(true)
                            .contextApplyMode(QueryRewriteResult.ContextApplyMode.HARD)
                            .retrievalQueries(List.of("接口"))
                            .retrievalQuerySources(List.of("original"))
                            .build());
            when(mapper.similaritySearchDetailedWithContext(
                    List.of("kb-1"),
                    "[0.1,0.2,0.3]",
                    "architecture.md",
                    "md",
                    "rag > bm25",
                    50
            )).thenReturn(List.of());
            when(mapper.searchByTitleExactWithContext(
                    List.of("kb-1"),
                    "接口",
                    "[0.1,0.2,0.3]",
                    "architecture.md",
                    "md",
                    "rag > bm25",
                    20
            )).thenReturn(List.of());
            when(bm25QueryService.searchTitle(
                    List.of("kb-1"),
                    "接口",
                    "architecture.md",
                    "md",
                    "rag > bm25",
                    10
            )).thenReturn(List.of());
            when(bm25QueryService.searchContent(
                    List.of("kb-1"),
                    "接口",
                    "architecture.md",
                    "md",
                    "rag > bm25",
                    20
            )).thenReturn(List.of());
            RagServiceImpl service = new RagServiceImpl(
                    WebClient.builder(),
                    mapper,
                    rewriteService,
                    bm25QueryService,
                    "http://localhost:" + server.getAddress().getPort(),
                    "bge-m3:latest",
                    false,
                    false,
                    false,
                    0
            );

            service.retrieve(List.of("kb-1"), "接口", context, 1);

            org.mockito.Mockito.verify(mapper).searchByTitleContainsWithContext(
                    List.of("kb-1"),
                    "接口",
                    "%接口%",
                    "architecture.md",
                    "md",
                    "rag > bm25",
                    10
            );
            org.mockito.Mockito.verify(mapper).searchByTitleKeywordsWithContext(
                    List.of("kb-1"),
                    List.of("接口"),
                    2,
                    "architecture.md",
                    "md",
                    "rag > bm25",
                    10
            );
            org.mockito.Mockito.verify(mapper).searchByTitleTrigramWithContext(
                    List.of("kb-1"),
                    "接口",
                    0.18D,
                    "architecture.md",
                    "md",
                    "rag > bm25",
                    10
            );
            org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never())
                    .searchByTitleContains(List.of("kb-1"), "接口", "%接口%", 10);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldReturnEmptyBeforeAnyRetrievalWhenHardContextKbIsOutsideAuthorizedScope() {
        ChunkBgeM3Mapper mapper = mock(ChunkBgeM3Mapper.class);
        QueryRewriteService rewriteService = mock(QueryRewriteService.class);
        VchordBm25QueryService bm25QueryService = mock(VchordBm25QueryService.class);
        RagRetrievalContext context = RagRetrievalContext.builder().kbId("kb-revoked").build();
        when(rewriteService.rewrite(List.of("kb-1"), "问题", context))
                .thenReturn(QueryRewriteResult.builder()
                        .query("问题")
                        .context(context)
                        .contextApplyMode(QueryRewriteResult.ContextApplyMode.HARD)
                        .build());
        RagServiceImpl service = new RagServiceImpl(
                WebClient.builder(),
                mapper,
                rewriteService,
                bm25QueryService,
                "http://localhost",
                "bge-m3:latest",
                false,
                false,
                false,
                0
        );

        List<RagRetrievalResult> results = service.retrieve(List.of("kb-1"), "问题", context, 1);

        assertTrue(results.isEmpty());
        verifyNoInteractions(mapper, bm25QueryService);
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
