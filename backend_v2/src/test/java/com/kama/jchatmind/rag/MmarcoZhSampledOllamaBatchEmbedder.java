package com.kama.jchatmind.rag;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.ExchangeStrategies;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class MmarcoZhSampledOllamaBatchEmbedder {

    static final int EMBEDDING_TIMEOUT_MILLIS = 300_000;
    static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final Duration EMBEDDING_TIMEOUT = Duration.ofMillis(EMBEDDING_TIMEOUT_MILLIS);

    private final WebClient webClient;
    private final String embeddingModel;
    private final int batchSize;

    MmarcoZhSampledOllamaBatchEmbedder(
            WebClient.Builder builder,
            String ollamaBaseUrl,
            String embeddingModel,
            int batchSize
    ) {
        this.webClient = builder
                .baseUrl(ollamaBaseUrl)
                .exchangeStrategies(ExchangeStrategies.builder()
                        .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(MAX_RESPONSE_BYTES))
                        .build())
                .build();
        this.embeddingModel = embeddingModel;
        this.batchSize = batchSize;
    }

    List<float[]> embedAll(List<String> texts) {
        if (CollectionUtils.isEmpty(texts) || batchSize <= 0) {
            throw new IllegalArgumentException("mMARCO 批量 embedding 输入无效");
        }
        List<float[]> embeddings = new ArrayList<>(texts.size());
        for (int start = 0; start < texts.size(); start += batchSize) {
            List<String> batch = texts.subList(start, Math.min(start + batchSize, texts.size()));
            embeddings.addAll(embedBatch(batch));
        }
        return List.copyOf(embeddings);
    }

    private List<float[]> embedBatch(List<String> texts) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", embeddingModel);
        body.put("input", texts);
        body.put("keep_alive", -1);
        EmbeddingResponse response = webClient.post()
                .uri("/api/embed")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(EmbeddingResponse.class)
                .block(EMBEDDING_TIMEOUT);
        Assert.notNull(response, "Embedding response cannot be null");
        Assert.notNull(response.getEmbeddings(), "Embedding response cannot be null");
        if (response.getEmbeddings().size() != texts.size()
                || response.getEmbeddings().stream().anyMatch(vector -> vector == null || vector.length == 0)) {
            throw new IllegalStateException("mMARCO 批量 embedding 响应与输入不一致");
        }
        return response.getEmbeddings();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class EmbeddingResponse {
        private List<float[]> embeddings;
    }
}
