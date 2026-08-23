package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.mapper.Bm25TokenDictionaryMapper;
import com.kama.jchatmind.model.dto.Bm25TokenDictionaryEntry;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Service
@AllArgsConstructor
public class VchordBm25ProjectionService {
    public static final int INDEX_VERSION = 1;

    private final Bm25TokenDictionaryMapper tokenDictionaryMapper;

    public Projection project(String titleSearchText, String content) {
        List<String> titleTokens = RetrievableTitleLexicalizer.tokenizeWithDuplicates(titleSearchText);
        List<String> contentTokens = RetrievableTitleLexicalizer.tokenizeWithDuplicates(content);
        Map<String, Long> tokenIds = resolveTokenIds(titleTokens, contentTokens);
        return new Projection(
                toBm25Vector(titleTokens, tokenIds),
                toBm25Vector(contentTokens, tokenIds),
                INDEX_VERSION
        );
    }

    private Map<String, Long> resolveTokenIds(List<String> titleTokens, List<String> contentTokens) {
        List<String> tokens = Stream.concat(titleTokens.stream(), contentTokens.stream())
                .distinct()
                .sorted()
                .toList();
        if (tokens.isEmpty()) {
            return Map.of();
        }

        Map<String, Long> tokenIds = new HashMap<>();
        for (Bm25TokenDictionaryEntry entry : tokenDictionaryMapper.upsertTokens(tokens)) {
            tokenIds.put(entry.getToken(), entry.getTokenId());
        }
        if (!tokenIds.keySet().containsAll(tokens)) {
            throw new IllegalStateException("BM25 token 词典未返回完整映射");
        }
        return tokenIds;
    }

    private String toBm25Vector(List<String> tokens, Map<String, Long> tokenIds) {
        Map<Long, Integer> frequencies = new TreeMap<>();
        for (String token : tokens) {
            Long tokenId = tokenIds.get(token);
            if (tokenId == null) {
                throw new IllegalStateException("BM25 token 缺少稳定标识");
            }
            frequencies.merge(tokenId, 1, Integer::sum);
        }
        return "{" + frequencies.entrySet().stream()
                .map(entry -> entry.getKey() + ":" + entry.getValue())
                .collect(Collectors.joining(",")) + "}";
    }

    public record Projection(String titleVector, String contentVector, Integer indexVersion) {
    }
}
