package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Service
public class BgeRerankerService {
    private static final int TEI_MAX_CLIENT_BATCH_SIZE = 32;
    private static final int TEI_MAX_CONCURRENT_BATCHES = 1;

    private final WebClient webClient;
    private final boolean enabled;
    private final Duration timeout;

    public BgeRerankerService(
            WebClient.Builder builder,
            @Value("${rag.rerank.enabled:false}") boolean enabled,
            @Value("${rag.rerank.base-url:http://127.0.0.1:8081}") String baseUrl,
            @Value("${rag.rerank.timeout-ms:3000}") int timeoutMs
    ) {
        this.webClient = builder.baseUrl(baseUrl).build();
        this.enabled = enabled;
        this.timeout = Duration.ofMillis(Math.max(timeoutMs, 1));
    }

    public List<Double> rerank(String query, List<String> texts) {
        if (!enabled || !StringUtils.hasText(query) || CollectionUtils.isEmpty(texts)) {
            return List.of();
        }

        List<List<String>> batches = batches(texts);
        List<List<Double>> scoresByBatch = Flux.fromIterable(batches)
                .flatMapSequential(batch -> rerankBatch(query, batch), TEI_MAX_CONCURRENT_BATCHES)
                .collectList()
                .block(timeout);
        if (scoresByBatch == null) {
            throw new IllegalStateException("TEI rerank response is empty");
        }
        return scoresByBatch.stream().flatMap(List::stream).toList();
    }

    private Mono<List<Double>> rerankBatch(String query, List<String> texts) {
        return webClient.post()
                .uri("/rerank")
                .bodyValue(new TeiRerankRequest(query, texts))
                .retrieve()
                .bodyToMono(new ParameterizedTypeReference<List<TeiRerankResult>>() {
                })
                .map(response -> restoreCandidateOrder(response, texts.size()));
    }

    private List<List<String>> batches(List<String> texts) {
        List<List<String>> batches = new ArrayList<>();
        for (int start = 0; start < texts.size(); start += TEI_MAX_CLIENT_BATCH_SIZE) {
            batches.add(List.copyOf(texts.subList(start, Math.min(start + TEI_MAX_CLIENT_BATCH_SIZE, texts.size()))));
        }
        return List.copyOf(batches);
    }

    private List<Double> restoreCandidateOrder(List<TeiRerankResult> response, int candidateCount) {
        if (response == null || response.size() != candidateCount) {
            throw new IllegalStateException("TEI rerank response size does not match candidate count");
        }
        List<Double> scores = new ArrayList<>(java.util.Collections.nCopies(candidateCount, null));
        for (TeiRerankResult result : response) {
            if (result == null || result.getIndex() == null || result.getScore() == null
                    || result.getIndex() < 0 || result.getIndex() >= candidateCount
                    || scores.get(result.getIndex()) != null) {
                throw new IllegalStateException("TEI rerank response contains an invalid result index");
            }
            scores.set(result.getIndex(), result.getScore());
        }
        if (scores.stream().anyMatch(score -> score == null)) {
            throw new IllegalStateException("TEI rerank response does not cover every candidate");
        }
        return List.copyOf(scores);
    }

    @Data
    private static class TeiRerankRequest {
        private final String query;
        private final List<String> texts;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class TeiRerankResult {
        private Integer index;

        private Double score;
    }
}
