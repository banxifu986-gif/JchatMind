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
import reactor.core.publisher.Hooks;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.stream.IntStream;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RagServiceImplTest {

    @Test
    void shouldRetrieveOnlyMarkdownTableAssetCandidatesWithinAuthorizedKnowledgeBasesAndHardContext() throws Exception {
        HttpServer server = createEmbeddingServer();
        server.start();

        try {
            RagRetrievalResult assetCandidate = new RagRetrievalResult();
            assetCandidate.setChunkId("chunk-markdown-table-2");
            assetCandidate.setKbId("kb-1");
            assetCandidate.setDocId("document-1");
            assetCandidate.setContent("| phase | owner |\n| --- | --- |\n| G2 | platform |");
            assetCandidate.setMetadata("{\"asset\":{\"id\":\"asset-table-2\",\"type\":\"TABLE\"}}");
            assetCandidate.setDistance(0.1D);
            assetCandidate.setRank(1);
            ChunkBgeM3Mapper mapper = mock(ChunkBgeM3Mapper.class, invocation -> {
                if ("similaritySearchMarkdownTableAssets".equals(invocation.getMethod().getName())) {
                    return List.of(assetCandidate);
                }
                return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
            });
            QueryRewriteService rewriteService = mock(QueryRewriteService.class);
            RagRetrievalContext context = RagRetrievalContext.builder()
                    .kbId("kb-1")
                    .sourceName("roadmap.md")
                    .sourceType("md")
                    .contentPath("G2 > table assets")
                    .build();
            when(rewriteService.rewrite(List.of("kb-1"), "定位 G2 表格", context))
                    .thenReturn(QueryRewriteResult.builder()
                            .query("定位 G2 表格")
                            .context(context)
                            .contextApplyMode(QueryRewriteResult.ContextApplyMode.HARD)
                            .retrievalQueries(List.of("定位 G2 表格"))
                            .retrievalQuerySources(List.of("original"))
                            .build());
            RagServiceImpl service = new RagServiceImpl(
                    WebClient.builder(),
                    mapper,
                    rewriteService,
                    mock(VchordBm25QueryService.class),
                    mock(BgeRerankerService.class),
                    "http://localhost:" + server.getAddress().getPort(),
                    "bge-m3:latest",
                    false,
                    false,
                    true,
                    0
            );

            List<RagRetrievalResult> results = retrieveMarkdownTableAssets(
                    service,
                    List.of("kb-1"),
                    "定位 G2 表格",
                    context,
                    3
            );

            assertEquals(List.of("chunk-markdown-table-2"),
                    results.stream().map(RagRetrievalResult::getChunkId).toList());
            assertEquals(List.of("asset_table_original"), results.get(0).getRetrievalProvenance());
            assertTrue(mockingDetails(mapper).getInvocations().stream().anyMatch(invocation ->
                    "similaritySearchMarkdownTableAssets".equals(invocation.getMethod().getName())
                            && List.of("kb-1").equals(invocation.getArguments()[0])
                            && "[0.1,0.2,0.3]".equals(invocation.getArguments()[1])
                            && "roadmap.md".equals(invocation.getArguments()[2])
                            && "md".equals(invocation.getArguments()[3])
                            && "g2 > table assets".equals(invocation.getArguments()[4])
                            && Integer.valueOf(3).equals(invocation.getArguments()[5])
            ));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRetrieveOnlyPdfPageAssetCandidatesWithinAuthorizedKnowledgeBases() throws Exception {
        HttpServer server = createEmbeddingServer();
        server.start();

        try {
            RagRetrievalResult assetCandidate = new RagRetrievalResult();
            assetCandidate.setChunkId("chunk-pdf-page-2");
            assetCandidate.setKbId("kb-1");
            assetCandidate.setDocId("document-1");
            assetCandidate.setContent("第二页的表格说明");
            assetCandidate.setMetadata("{\"asset\":{\"id\":\"asset-page-2\",\"type\":\"PDF_PAGE_TEXT\"}}");
            assetCandidate.setDistance(0.1D);
            assetCandidate.setRank(1);
            ChunkBgeM3Mapper mapper = mock(ChunkBgeM3Mapper.class, invocation -> {
                if ("similaritySearchPdfPageAssets".equals(invocation.getMethod().getName())) {
                    return List.of(assetCandidate);
                }
                return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
            });
            QueryRewriteService rewriteService = mock(QueryRewriteService.class);
            when(rewriteService.rewrite(List.of("kb-1"), "定位 PDF 第二页表格", null))
                    .thenReturn(QueryRewriteResult.builder()
                            .query("定位 PDF 第二页表格")
                            .retrievalQueries(List.of("定位 PDF 第二页表格"))
                            .retrievalQuerySources(List.of("original"))
                            .build());
            RagServiceImpl service = new RagServiceImpl(
                    WebClient.builder(),
                    mapper,
                    rewriteService,
                    mock(VchordBm25QueryService.class),
                    mock(BgeRerankerService.class),
                    "http://localhost:" + server.getAddress().getPort(),
                    "bge-m3:latest",
                    false,
                    false,
                    true,
                    0
            );

            List<RagRetrievalResult> results = retrievePdfPageAssets(
                    service,
                    List.of("kb-1"),
                    "定位 PDF 第二页表格",
                    3
            );

            assertEquals(List.of("chunk-pdf-page-2"),
                    results.stream().map(RagRetrievalResult::getChunkId).toList());
            assertEquals(List.of("asset_pdf_page_text_original"), results.get(0).getRetrievalProvenance());
            assertTrue(mockingDetails(mapper).getInvocations().stream().anyMatch(invocation ->
                    "similaritySearchPdfPageAssets".equals(invocation.getMethod().getName())
                            && List.of("kb-1").equals(invocation.getArguments()[0])
                            && "[0.1,0.2,0.3]".equals(invocation.getArguments()[1])
                            && invocation.getArguments()[2] == null
                            && invocation.getArguments()[3] == null
                            && invocation.getArguments()[4] == null
                            && Integer.valueOf(3).equals(invocation.getArguments()[5])
            ));
        } finally {
            server.stop(0);
        }
    }

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
                    mock(BgeRerankerService.class),
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
                    mock(BgeRerankerService.class),
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
                    mock(BgeRerankerService.class),
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
                mock(BgeRerankerService.class),
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

    @Test
    void shouldUseBgeRerankerScoresForTopCandidates() throws Exception {
        HttpServer server = createEmbeddingServer();
        server.start();

        try {
            ChunkBgeM3Mapper mapper = mock(ChunkBgeM3Mapper.class);
            QueryRewriteService rewriteService = mock(QueryRewriteService.class);
            VchordBm25QueryService bm25QueryService = mock(VchordBm25QueryService.class);
            BgeRerankerService rerankerService = mock(BgeRerankerService.class);
            List<RagRetrievalResult> candidates = IntStream.rangeClosed(1, 3)
                    .mapToObj(index -> {
                        RagRetrievalResult result = new RagRetrievalResult();
                        result.setChunkId("chunk-" + index);
                        result.setKbId("kb-1");
                        result.setContent("通用正文 " + index);
                        result.setMetadata("{\"retrievableTitle\":\"通用标题\"}");
                        result.setDistance(0.5D);
                        result.setRank(index);
                        return result;
                    })
                    .toList();
            when(rewriteService.rewrite(List.of("kb-1"), "问题", null))
                    .thenReturn(QueryRewriteResult.builder()
                            .query("问题")
                            .retrievalQueries(List.of("问题"))
                            .retrievalQuerySources(List.of("original"))
                            .build());
            when(mapper.similaritySearchDetailed(List.of("kb-1"), "[0.1,0.2,0.3]", 50))
                    .thenReturn(candidates);
            when(bm25QueryService.searchContent(List.of("kb-1"), "问题", null, null, null, 20))
                    .thenReturn(List.of());
            when(rerankerService.rerank(
                    "问题",
                    candidates.stream()
                            .map(result -> "通用标题\n" + result.getContent())
                            .toList()
            )).thenReturn(List.of(0.1D, 0.95D, 0.2D));

            RagServiceImpl service = new RagServiceImpl(
                    WebClient.builder(),
                    mapper,
                    rewriteService,
                    bm25QueryService,
                    rerankerService,
                    "http://localhost:" + server.getAddress().getPort(),
                    "bge-m3:latest",
                    false,
                    false,
                    false,
                    0
            );

            List<RagRetrievalResult> results = service.retrieve(List.of("kb-1"), "问题", 3);

            assertEquals(List.of("chunk-2", "chunk-3", "chunk-1"),
                    results.stream().map(RagRetrievalResult::getChunkId).toList());
            assertEquals(List.of(0.95D, 0.2D, 0.1D),
                    results.stream().map(RagRetrievalResult::getRerankScore).toList());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldTimeoutEmbeddingWhenOllamaDoesNotRespond() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/api/embed", exchange -> {
            try {
                Thread.sleep(31_000L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
            byte[] response = "{\"model\":\"bge-m3:latest\",\"embeddings\":[[0.1,0.2,0.3]]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (OutputStream output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        Hooks.onErrorDropped(ignored -> {
        });

        try {
            RagServiceImpl service = new RagServiceImpl(
                    WebClient.builder(),
                    mock(ChunkBgeM3Mapper.class),
                    mock(QueryRewriteService.class),
                    mock(VchordBm25QueryService.class),
                    mock(BgeRerankerService.class),
                    "http://localhost:" + server.getAddress().getPort(),
                    "bge-m3:latest",
                    false,
                    false,
                    false,
                    0
            );

            assertThrows(IllegalStateException.class, () -> service.embed("timeout"));
        } finally {
            Hooks.resetOnErrorDropped();
            server.stop(0);
        }
    }

    @Test
    void shouldCountSameChunkOnceInEachOriginalAndExpandedVectorBranch() throws Exception {
        HttpServer server = createEmbeddingServer();
        server.start();

        try {
            ChunkBgeM3Mapper mapper = mock(ChunkBgeM3Mapper.class);
            QueryRewriteService rewriteService = mock(QueryRewriteService.class);
            VchordBm25QueryService bm25QueryService = mock(VchordBm25QueryService.class);
            RagRetrievalResult candidate = new RagRetrievalResult();
            candidate.setChunkId("chunk-1");
            candidate.setKbId("kb-1");
            candidate.setContent("内容");
            candidate.setRank(1);
            when(rewriteService.rewrite(List.of("kb-1"), "问题", null))
                    .thenReturn(QueryRewriteResult.builder()
                            .query("问题")
                            .retrievalQueries(List.of("问题", "问题补全"))
                            .retrievalQuerySources(List.of("original", "standalone"))
                            .build());
            when(mapper.similaritySearchDetailed(List.of("kb-1"), "[0.1,0.2,0.3]", 50))
                    .thenReturn(List.of(candidate), List.of(candidate));
            when(bm25QueryService.searchContent(List.of("kb-1"), "问题", null, null, null, 20))
                    .thenReturn(List.of());

            RagServiceImpl service = new RagServiceImpl(
                    WebClient.builder(),
                    mapper,
                    rewriteService,
                    bm25QueryService,
                    mock(BgeRerankerService.class),
                    "http://localhost:" + server.getAddress().getPort(),
                    "bge-m3:latest",
                    false,
                    false,
                    true,
                    0
            );

            List<RagRetrievalResult> results = service.retrieve(List.of("kb-1"), "问题", 1);

            assertEquals(1, results.size());
            assertEquals(2D / 61D, results.get(0).getRrfScore(), 0.0000000001D);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldCountSameChunkOnlyOnceAcrossTitleChannels() throws Exception {
        HttpServer server = createEmbeddingServer();
        server.start();

        try {
            ChunkBgeM3Mapper mapper = mock(ChunkBgeM3Mapper.class);
            QueryRewriteService rewriteService = mock(QueryRewriteService.class);
            VchordBm25QueryService bm25QueryService = mock(VchordBm25QueryService.class);
            RagRetrievalResult candidate = new RagRetrievalResult();
            candidate.setChunkId("chunk-1");
            candidate.setKbId("kb-1");
            candidate.setContent("接口内容");
            candidate.setRank(1);
            when(rewriteService.rewrite(List.of("kb-1"), "接口", null))
                    .thenReturn(QueryRewriteResult.builder()
                            .query("接口")
                            .titleQuery(true)
                            .retrievalQueries(List.of("接口"))
                            .retrievalQuerySources(List.of("original"))
                            .build());
            when(mapper.similaritySearchDetailed(List.of("kb-1"), "[0.1,0.2,0.3]", 50))
                    .thenReturn(List.of());
            when(mapper.searchByTitleExact(List.of("kb-1"), "接口", "[0.1,0.2,0.3]", 20))
                    .thenReturn(List.of(candidate));
            when(mapper.searchByTitleContains(List.of("kb-1"), "接口", "%接口%", 10))
                    .thenReturn(List.of(candidate));
            when(mapper.searchByTitleKeywords(List.of("kb-1"), List.of("接口"), 2, 10))
                    .thenReturn(List.of());
            when(mapper.searchByTitleTrigram(List.of("kb-1"), "接口", 0.18D, 10))
                    .thenReturn(List.of());
            when(bm25QueryService.searchTitle(List.of("kb-1"), "接口", null, null, null, 10))
                    .thenReturn(List.of());
            when(bm25QueryService.searchContent(List.of("kb-1"), "接口", null, null, null, 20))
                    .thenReturn(List.of());

            RagServiceImpl service = new RagServiceImpl(
                    WebClient.builder(),
                    mapper,
                    rewriteService,
                    bm25QueryService,
                    mock(BgeRerankerService.class),
                    "http://localhost:" + server.getAddress().getPort(),
                    "bge-m3:latest",
                    false,
                    false,
                    true,
                    0
            );

            List<RagRetrievalResult> results = service.retrieve(List.of("kb-1"), "接口", 1);

            assertEquals(1, results.size());
            assertEquals(1D / 61D, results.get(0).getRrfScore());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldKeepChannelAndQueryProvenanceAfterFusion() throws Exception {
        HttpServer server = createEmbeddingServer();
        server.start();

        try {
            ChunkBgeM3Mapper mapper = mock(ChunkBgeM3Mapper.class);
            QueryRewriteService rewriteService = mock(QueryRewriteService.class);
            VchordBm25QueryService bm25QueryService = mock(VchordBm25QueryService.class);
            RagRetrievalResult candidate = new RagRetrievalResult();
            candidate.setChunkId("chunk-1");
            candidate.setKbId("kb-1");
            candidate.setContent("接口内容");
            candidate.setRank(1);
            when(rewriteService.rewrite(List.of("kb-1"), "接口", null))
                    .thenReturn(QueryRewriteResult.builder()
                            .query("接口")
                            .titleQuery(true)
                            .retrievalQueries(List.of("接口", "接口补全"))
                            .retrievalQuerySources(List.of("original", "standalone"))
                            .build());
            when(mapper.similaritySearchDetailed(List.of("kb-1"), "[0.1,0.2,0.3]", 50))
                    .thenReturn(List.of(candidate), List.of(candidate));
            when(mapper.searchByTitleExact(List.of("kb-1"), "接口", "[0.1,0.2,0.3]", 20))
                    .thenReturn(List.of(candidate));
            when(mapper.searchByTitleContains(List.of("kb-1"), "接口", "%接口%", 10))
                    .thenReturn(List.of());
            when(mapper.searchByTitleKeywords(List.of("kb-1"), List.of("接口"), 2, 10))
                    .thenReturn(List.of());
            when(mapper.searchByTitleTrigram(List.of("kb-1"), "接口", 0.18D, 10))
                    .thenReturn(List.of());
            when(bm25QueryService.searchTitle(List.of("kb-1"), "接口", null, null, null, 10))
                    .thenReturn(List.of());
            when(bm25QueryService.searchContent(List.of("kb-1"), "接口", null, null, null, 20))
                    .thenReturn(List.of());

            RagServiceImpl service = new RagServiceImpl(
                    WebClient.builder(),
                    mapper,
                    rewriteService,
                    bm25QueryService,
                    mock(BgeRerankerService.class),
                    "http://localhost:" + server.getAddress().getPort(),
                    "bge-m3:latest",
                    false,
                    false,
                    true,
                    0
            );

            List<RagRetrievalResult> results = service.retrieve(List.of("kb-1"), "接口", 1);

            assertEquals(
                    List.of(
                            "dense-original:vector:original",
                            "sparse-original:title-exact:original",
                            "expanded-query:vector:standalone"
                    ),
                    results.get(0).getRetrievalProvenance()
            );
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldKeepOriginalQueryOutOfExpandedBranch() throws Exception {
        HttpServer server = createEmbeddingServer();
        server.start();

        try {
            ChunkBgeM3Mapper mapper = mock(ChunkBgeM3Mapper.class);
            QueryRewriteService rewriteService = mock(QueryRewriteService.class);
            VchordBm25QueryService bm25QueryService = mock(VchordBm25QueryService.class);
            when(rewriteService.rewrite(List.of("kb-1"), "问题", null))
                    .thenReturn(QueryRewriteResult.builder()
                            .query("问题")
                            .retrievalQueries(List.of("问题", "问题补全"))
                            .retrievalQuerySources(List.of("original", "standalone"))
                            .build());
            when(mapper.similaritySearchDetailed(List.of("kb-1"), "[0.1,0.2,0.3]", 50))
                    .thenReturn(
                            List.of(candidate("chunk-dense", 1)),
                            List.of(candidate("chunk-expanded", 1))
                    );

            RagServiceImpl service = new RagServiceImpl(
                    WebClient.builder(),
                    mapper,
                    rewriteService,
                    bm25QueryService,
                    mock(BgeRerankerService.class),
                    "http://localhost:" + server.getAddress().getPort(),
                    "bge-m3:latest",
                    false,
                    false,
                    true,
                    0
            );

            List<RagRetrievalResult> results = service.retrieve(List.of("kb-1"), "问题", 2);

            assertEquals(
                    List.of("dense-original:vector:original"),
                    results.get(0).getRetrievalProvenance()
            );
            assertEquals(
                    List.of("expanded-query:vector:standalone"),
                    results.get(1).getRetrievalProvenance()
            );
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldRejectUnknownExpandedQuerySource() throws Exception {
        HttpServer server = createEmbeddingServer();
        server.start();

        try {
            ChunkBgeM3Mapper mapper = mock(ChunkBgeM3Mapper.class);
            QueryRewriteService rewriteService = mock(QueryRewriteService.class);
            VchordBm25QueryService bm25QueryService = mock(VchordBm25QueryService.class);
            when(rewriteService.rewrite(List.of("kb-1"), "问题", null))
                    .thenReturn(QueryRewriteResult.builder()
                            .query("问题")
                            .retrievalQueries(List.of("问题", "不受控扩展"))
                            .retrievalQuerySources(List.of("original", "legacy"))
                            .build());
            when(mapper.similaritySearchDetailed(List.of("kb-1"), "[0.1,0.2,0.3]", 50))
                    .thenReturn(
                            List.of(candidate("chunk-dense", 1)),
                            List.of(candidate("chunk-legacy", 1))
                    );

            RagServiceImpl service = new RagServiceImpl(
                    WebClient.builder(),
                    mapper,
                    rewriteService,
                    bm25QueryService,
                    mock(BgeRerankerService.class),
                    "http://localhost:" + server.getAddress().getPort(),
                    "bge-m3:latest",
                    false,
                    false,
                    true,
                    0
            );

            List<RagRetrievalResult> results = service.retrieve(List.of("kb-1"), "问题", 2);

            assertEquals(List.of("chunk-dense"), results.stream().map(RagRetrievalResult::getChunkId).toList());
            assertEquals(
                    List.of("dense-original:vector:original"),
                    results.get(0).getRetrievalProvenance()
            );
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldCountSameChunkAtMostOncePerIndependentBranch() throws Exception {
        HttpServer server = createEmbeddingServer();
        server.start();

        try {
            ChunkBgeM3Mapper mapper = mock(ChunkBgeM3Mapper.class);
            QueryRewriteService rewriteService = mock(QueryRewriteService.class);
            VchordBm25QueryService bm25QueryService = mock(VchordBm25QueryService.class);
            when(rewriteService.rewrite(List.of("kb-1"), "接口", null))
                    .thenReturn(QueryRewriteResult.builder()
                            .query("接口")
                            .titleQuery(true)
                            .retrievalQueries(List.of("接口", "接口补全"))
                            .retrievalQuerySources(List.of("original", "standalone"))
                            .build());
            when(mapper.similaritySearchDetailed(List.of("kb-1"), "[0.1,0.2,0.3]", 50))
                    .thenReturn(List.of(candidate("chunk-1", 1)), List.of(candidate("chunk-1", 1)));
            when(mapper.searchByTitleExact(List.of("kb-1"), "接口", "[0.1,0.2,0.3]", 20))
                    .thenReturn(List.of(candidate("chunk-1", 1)));
            when(mapper.searchByTitleContains(List.of("kb-1"), "接口", "%接口%", 10))
                    .thenReturn(List.of(candidate("chunk-1", 1)));

            RagServiceImpl service = new RagServiceImpl(
                    WebClient.builder(),
                    mapper,
                    rewriteService,
                    bm25QueryService,
                    mock(BgeRerankerService.class),
                    "http://localhost:" + server.getAddress().getPort(),
                    "bge-m3:latest",
                    false,
                    false,
                    true,
                    0
            );

            List<RagRetrievalResult> results = service.retrieve(List.of("kb-1"), "接口", 1);

            assertEquals(1, results.size());
            assertEquals(3D / 61D, results.get(0).getRrfScore(), 0.0000000001D);
            assertEquals(
                    List.of(
                            "dense-original:vector:original",
                            "sparse-original:title-exact:original",
                            "sparse-original:title-contains:original",
                            "expanded-query:vector:standalone"
                    ),
                    results.get(0).getRetrievalProvenance()
            );
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldApplyHardScopeBeforeLimitForEveryIndependentBranch() throws Exception {
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
                            .retrievalQueries(List.of("接口", "完整接口"))
                            .retrievalQuerySources(List.of("original", "standalone"))
                            .build());

            RagServiceImpl service = new RagServiceImpl(
                    WebClient.builder(),
                    mapper,
                    rewriteService,
                    bm25QueryService,
                    mock(BgeRerankerService.class),
                    "http://localhost:" + server.getAddress().getPort(),
                    "bge-m3:latest",
                    false,
                    false,
                    true,
                    0
            );

            service.retrieve(List.of("kb-1"), "接口", context, 1);

            org.mockito.Mockito.verify(mapper, org.mockito.Mockito.times(2)).similaritySearchDetailedWithContext(
                    List.of("kb-1"),
                    "[0.1,0.2,0.3]",
                    "architecture.md",
                    "md",
                    "rag > bm25",
                    50
            );
            org.mockito.Mockito.verify(mapper, org.mockito.Mockito.never())
                    .similaritySearchDetailed(List.of("kb-1"), "[0.1,0.2,0.3]", 50);
            org.mockito.Mockito.verify(mapper).searchByTitleExactWithContext(
                    List.of("kb-1"),
                    "完整接口",
                    "[0.1,0.2,0.3]",
                    "architecture.md",
                    "md",
                    "rag > bm25",
                    20
            );
            org.mockito.Mockito.verify(mapper).searchByTitleContainsWithContext(
                    List.of("kb-1"),
                    "完整接口",
                    "%完整接口%",
                    "architecture.md",
                    "md",
                    "rag > bm25",
                    10
            );
            org.mockito.Mockito.verify(mapper).searchByTitleKeywordsWithContext(
                    List.of("kb-1"),
                    List.of("完整", "整接", "接口"),
                    4,
                    "architecture.md",
                    "md",
                    "rag > bm25",
                    10
            );
            org.mockito.Mockito.verify(mapper).searchByTitleTrigramWithContext(
                    List.of("kb-1"),
                    "完整接口",
                    0.18D,
                    "architecture.md",
                    "md",
                    "rag > bm25",
                    10
            );
            org.mockito.Mockito.verify(bm25QueryService).searchTitle(
                    List.of("kb-1"),
                    "完整接口",
                    "architecture.md",
                    "md",
                    "rag > bm25",
                    10
            );
            org.mockito.Mockito.verify(bm25QueryService).searchContent(
                    List.of("kb-1"),
                    "完整接口",
                    "architecture.md",
                    "md",
                    "rag > bm25",
                    20
            );
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldKeepThreeBranchProvenanceAndOuterFusionStable() throws Exception {
        HttpServer server = createEmbeddingServer();
        server.start();

        try {
            ChunkBgeM3Mapper mapper = mock(ChunkBgeM3Mapper.class);
            QueryRewriteService rewriteService = mock(QueryRewriteService.class);
            VchordBm25QueryService bm25QueryService = mock(VchordBm25QueryService.class);
            when(rewriteService.rewrite(List.of("kb-1"), "问题", null))
                    .thenReturn(QueryRewriteResult.builder()
                            .query("问题")
                            .retrievalQueries(List.of("问题", "问题补全"))
                            .retrievalQuerySources(List.of("original", "standalone"))
                            .build());
            when(mapper.similaritySearchDetailed(List.of("kb-1"), "[0.1,0.2,0.3]", 50))
                    .thenReturn(
                            List.of(candidate("chunk-dense", 1), candidate("chunk-shared", 2)),
                            List.of(candidate("chunk-expanded", 1), candidate("chunk-shared", 2))
                    );
            when(bm25QueryService.searchContent(List.of("kb-1"), "问题", null, null, null, 20))
                    .thenReturn(List.of(candidate("chunk-sparse", 1), candidate("chunk-shared", 2)));

            RagServiceImpl service = new RagServiceImpl(
                    WebClient.builder(),
                    mapper,
                    rewriteService,
                    bm25QueryService,
                    mock(BgeRerankerService.class),
                    "http://localhost:" + server.getAddress().getPort(),
                    "bge-m3:latest",
                    false,
                    false,
                    true,
                    0
            );

            List<RagRetrievalResult> results = service.retrieve(List.of("kb-1"), "问题", 4);

            assertEquals(
                    List.of("chunk-shared", "chunk-dense", "chunk-expanded", "chunk-sparse"),
                    results.stream().map(RagRetrievalResult::getChunkId).toList()
            );
            assertEquals(3D / 62D, results.get(0).getRrfScore(), 0.0000000001D);
            assertEquals(
                    List.of(
                            "dense-original:vector:original",
                            "sparse-original:content-bm25:original",
                            "expanded-query:vector:standalone"
                    ),
                    results.get(0).getRetrievalProvenance()
            );
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldDeduplicateSameChunkAcrossExpandedQueriesBeforeOuterRrf() throws Exception {
        HttpServer server = createEmbeddingServer();
        server.start();

        try {
            ChunkBgeM3Mapper mapper = mock(ChunkBgeM3Mapper.class);
            QueryRewriteService rewriteService = mock(QueryRewriteService.class);
            VchordBm25QueryService bm25QueryService = mock(VchordBm25QueryService.class);
            when(rewriteService.rewrite(List.of("kb-1"), "问题", null))
                    .thenReturn(QueryRewriteResult.builder()
                            .query("问题")
                            .retrievalQueries(List.of("问题", "补全A", "补全B"))
                            .retrievalQuerySources(List.of("original", "standalone", "llm"))
                            .build());
            when(mapper.similaritySearchDetailed(List.of("kb-1"), "[0.1,0.2,0.3]", 50))
                    .thenReturn(
                            List.of(),
                            List.of(candidate("chunk-expanded", 1)),
                            List.of(candidate("chunk-expanded", 1))
                    );

            RagServiceImpl service = new RagServiceImpl(
                    WebClient.builder(),
                    mapper,
                    rewriteService,
                    bm25QueryService,
                    mock(BgeRerankerService.class),
                    "http://localhost:" + server.getAddress().getPort(),
                    "bge-m3:latest",
                    false,
                    false,
                    true,
                    0
            );

            List<RagRetrievalResult> results = service.retrieve(List.of("kb-1"), "问题", 1);

            assertEquals(1, results.size());
            assertEquals(1D / 61D, results.get(0).getRrfScore(), 0.0000000001D);
            assertEquals(
                    List.of(
                            "expanded-query:vector:standalone",
                            "expanded-query:vector:llm"
                    ),
                    results.get(0).getRetrievalProvenance()
            );
        } finally {
            server.stop(0);
        }
    }

    @Test
    void shouldKeepIndependentBranchEvaluationVariantsSeparated() throws Exception {
        HttpServer server = createEmbeddingServer();
        server.start();

        try {
            ChunkBgeM3Mapper mapper = mock(ChunkBgeM3Mapper.class);
            QueryRewriteService rewriteService = mock(QueryRewriteService.class);
            VchordBm25QueryService bm25QueryService = mock(VchordBm25QueryService.class);
            when(rewriteService.rewrite(List.of("kb-1"), "原问", null))
                    .thenReturn(QueryRewriteResult.builder()
                            .query("原问")
                            .retrievalQueries(List.of("原问", "standalone 补全问句"))
                            .retrievalQuerySources(List.of("original", "standalone"))
                            .build());
            when(mapper.similaritySearchDetailed(List.of("kb-1"), "[0.1,0.2,0.3]", 50))
                    .thenReturn(List.of(candidate("chunk-shared", 1)));
            when(bm25QueryService.searchContent(List.of("kb-1"), "原问", null, null, null, 20))
                    .thenReturn(List.of(candidate("chunk-shared", 1)));
            when(bm25QueryService.searchContent(List.of("kb-1"), "standalone 补全问句", null, null, null, 20))
                    .thenReturn(List.of(candidate("chunk-shared", 1)));

            RagServiceImpl service = new RagServiceImpl(
                    WebClient.builder(),
                    mapper,
                    rewriteService,
                    bm25QueryService,
                    mock(BgeRerankerService.class),
                    "http://localhost:" + server.getAddress().getPort(),
                    "bge-m3:latest",
                    false,
                    false,
                    true,
                    0
            );

            List<RagRetrievalResult> r0 = service.retrieveForIndependentBranchEvaluation(
                    List.of("kb-1"), "原问", 10, "current-flat"
            );
            List<RagRetrievalResult> r1 = service.retrieveForIndependentBranchEvaluation(
                    List.of("kb-1"), "原问", 10, "two-branch-original"
            );
            List<RagRetrievalResult> r2 = service.retrieveForIndependentBranchEvaluation(
                    List.of("kb-1"), "原问", 10, "three-branch-expanded"
            );

            assertEquals(
                    List.of("vector_original", "vector_standalone", "content_bm25"),
                    r0.get(0).getRetrievalProvenance()
            );
            assertEquals(
                    List.of("dense-original:vector:original", "sparse-original:content-bm25:original"),
                    r1.get(0).getRetrievalProvenance()
            );
            assertTrue(r2.get(0).getRetrievalProvenance().contains("expanded-query:vector:standalone"));
            assertTrue(r2.get(0).getRetrievalProvenance().contains("expanded-query:content-bm25:standalone"));
        } finally {
            server.stop(0);
        }
    }

    private static RagRetrievalResult candidate(String chunkId, int rank) {
        RagRetrievalResult candidate = new RagRetrievalResult();
        candidate.setChunkId(chunkId);
        candidate.setKbId("kb-1");
        candidate.setContent(chunkId + " 内容");
        candidate.setRank(rank);
        return candidate;
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

    @SuppressWarnings("unchecked")
    private static List<RagRetrievalResult> retrievePdfPageAssets(
            RagServiceImpl service,
            List<String> kbIds,
            String query,
            int limit
    ) {
        try {
            Method method = RagServiceImpl.class.getMethod(
                    "retrievePdfPageAssets",
                    List.class,
                    String.class,
                    RagRetrievalContext.class,
                    int.class
            );
            return (List<RagRetrievalResult>) method.invoke(service, kbIds, query, null, limit);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new AssertionError(e.getCause());
        } catch (NoSuchMethodException e) {
            throw new AssertionError("PDF 页资产候选检索尚未实现", e);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<RagRetrievalResult> retrieveMarkdownTableAssets(
            RagServiceImpl service,
            List<String> kbIds,
            String query,
            RagRetrievalContext context,
            int limit
    ) {
        try {
            Method method = RagServiceImpl.class.getMethod(
                    "retrieveMarkdownTableAssets",
                    List.class,
                    String.class,
                    RagRetrievalContext.class,
                    int.class
            );
            return (List<RagRetrievalResult>) method.invoke(service, kbIds, query, context, limit);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new AssertionError(e.getCause());
        } catch (NoSuchMethodException e) {
            throw new AssertionError("Markdown 表格资产候选检索尚未实现", e);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
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
