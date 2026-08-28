package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MmarcoZhSampledOllamaBatchEmbedderTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void preservesInputOrderAcrossBatchBoundaries() throws Exception {
        List<List<String>> inputsByRequest = new ArrayList<>();
        HttpServer server = startServer(inputsByRequest);
        try {
            MmarcoZhSampledOllamaBatchEmbedder embedder = new MmarcoZhSampledOllamaBatchEmbedder(
                    WebClient.builder(),
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "bge-m3:latest",
                    2
            );

            List<float[]> embeddings = embedder.embedAll(List.of("first", "second", "third"));

            assertThat(inputsByRequest).containsExactly(List.of("first", "second"), List.of("third"));
            assertThat(embeddings).extracting(vector -> vector[0]).containsExactly(1F, 2F, 3F);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsAnEmbeddingResponseThatDoesNotCoverTheWholeBatch() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/embed", this::respondWithIncompleteEmbedding);
        server.start();
        try {
            MmarcoZhSampledOllamaBatchEmbedder embedder = new MmarcoZhSampledOllamaBatchEmbedder(
                    WebClient.builder(),
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "bge-m3:latest",
                    2
            );

            assertThatThrownBy(() -> embedder.embedAll(List.of("first", "second")))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("mMARCO 批量 embedding 响应与输入不一致");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void acceptsAFullBatchResponseLargerThanTheDefaultWebClientBuffer() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/embed", this::respondWithLargeEmbeddingBatch);
        server.start();
        try {
            MmarcoZhSampledOllamaBatchEmbedder embedder = new MmarcoZhSampledOllamaBatchEmbedder(
                    WebClient.builder(),
                    "http://127.0.0.1:" + server.getAddress().getPort(),
                    "bge-m3:latest",
                    64
            );

            List<float[]> embeddings = embedder.embedAll(java.util.Collections.nCopies(64, "text"));

            assertThat(embeddings).hasSize(64);
            assertThat(embeddings).allSatisfy(vector -> assertThat(vector).hasSize(1024));
        } finally {
            server.stop(0);
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "rag.eval.mmarco.ollama.preflight.enabled", matches = "true")
    void embedsTheFirstFrozenV3BatchWithTheRuntimeClient() throws Exception {
        JsonNode candidates = OBJECT_MAPPER.readTree(Path.of(
                        "target", "rag-eval", "external", "mmarco-zh-sampled-v3-local-diagnostic",
                        "mmarco-zh-sampled-v3-local-diagnostic-manifest.json"
                ).toFile())
                .path("candidates");
        List<String> inputs = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            inputs.add(candidates.get(index).path("content").asText());
        }
        MmarcoZhSampledOllamaBatchEmbedder embedder = new MmarcoZhSampledOllamaBatchEmbedder(
                WebClient.builder(),
                System.getProperty("rag.eval.mmarco.ollama.base-url", "http://127.0.0.1:11434"),
                "bge-m3:latest",
                8
        );

        List<float[]> embeddings = embedder.embedAll(inputs);

        assertThat(embeddings).hasSize(8);
        assertThat(embeddings).allSatisfy(vector -> assertThat(vector).hasSize(1024));
    }

    private HttpServer startServer(List<List<String>> inputsByRequest) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/embed", exchange -> respond(exchange, inputsByRequest));
        server.start();
        return server;
    }

    private void respond(HttpExchange exchange, List<List<String>> inputsByRequest) throws IOException {
        JsonNode request = OBJECT_MAPPER.readTree(exchange.getRequestBody());
        List<String> inputs = new ArrayList<>();
        for (JsonNode input : request.path("input")) {
            inputs.add(input.asText());
        }
        inputsByRequest.add(List.copyOf(inputs));
        List<List<Float>> embeddings = new ArrayList<>();
        for (String input : inputs) {
            embeddings.add(List.of((float) (inputsByRequest.stream().mapToInt(List::size).sum() - inputs.size() + embeddings.size() + 1)));
        }
        byte[] response = OBJECT_MAPPER.writeValueAsBytes(java.util.Map.of("embeddings", embeddings));
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private void respondWithIncompleteEmbedding(HttpExchange exchange) throws IOException {
        byte[] response = "{\"embeddings\":[[1.0]]}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }

    private void respondWithLargeEmbeddingBatch(HttpExchange exchange) throws IOException {
        float[] vector = new float[1024];
        java.util.Arrays.fill(vector, 12_345_678.25F);
        List<float[]> embeddings = java.util.Collections.nCopies(64, vector);
        byte[] response = OBJECT_MAPPER.writeValueAsBytes(java.util.Map.of("embeddings", embeddings));
        assertThat(response.length).isGreaterThan(262_144);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.length);
        exchange.getResponseBody().write(response);
        exchange.close();
    }
}
