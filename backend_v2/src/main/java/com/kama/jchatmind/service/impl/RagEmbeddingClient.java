package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RagEmbeddingClient {
    private static final Duration EMBEDDING_TIMEOUT = Duration.ofSeconds(30);

    private final WebClient webClient;
    private final String embeddingModel;
    private final boolean embeddingCacheEnabled;
    private final Map<String, float[]> embeddingCache;

    RagEmbeddingClient(WebClient.Builder builder, String baseUrl, String model, int cacheMaxEntries) {
        this.webClient = builder.baseUrl(baseUrl).build();
        this.embeddingModel = model;
        this.embeddingCacheEnabled = cacheMaxEntries > 0;
        this.embeddingCache = createEmbeddingCache(cacheMaxEntries);
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class EmbeddingResponse {
        private List<float[]> embeddings;
    }

    private Map<String, float[]> createEmbeddingCache(int maxEntries) {
        int cacheSize = Math.max(maxEntries, 0);
        if (cacheSize == 0) {
            return Map.of();
        }
        return Collections.synchronizedMap(new LinkedHashMap<>(cacheSize, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
                return size() > cacheSize;
            }
        });
    }

    float[] embed(String text) {
        String cacheKey = StringUtils.hasText(text) ? text.trim() : text;
        float[] cachedEmbedding = embeddingCacheEnabled ? embeddingCache.get(cacheKey) : null;
        if (cachedEmbedding != null) {
            return cachedEmbedding.clone();
        }

        Map<String, Object> body = new HashMap<>();
        body.put("model", embeddingModel);
        body.put("input", text);
        body.put("keep_alive", -1);

        EmbeddingResponse resp = webClient.post()
                .uri("/api/embed")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(EmbeddingResponse.class)
                .block(EMBEDDING_TIMEOUT);
        Assert.notNull(resp, "Embedding response cannot be null");
        Assert.isTrue(!CollectionUtils.isEmpty(resp.getEmbeddings()), "Embedding response cannot be empty");
        Assert.notNull(resp.getEmbeddings().get(0), "Embedding vector cannot be null");

        float[] embedding = resp.getEmbeddings().get(0);
        if (embeddingCacheEnabled) {
            embeddingCache.put(cacheKey, embedding.clone());
        }
        return embedding;
    }

}
