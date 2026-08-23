package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.mapper.Bm25TokenDictionaryMapper;
import com.kama.jchatmind.mapper.ChunkBgeM3Mapper;
import com.kama.jchatmind.model.dto.Bm25TokenDictionaryEntry;
import com.kama.jchatmind.model.dto.RagRetrievalResult;
import lombok.AllArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

@Service
@AllArgsConstructor
public class VchordBm25QueryService {
    private static final String BM25_SEARCH_PATH = "SET LOCAL search_path = bm25_catalog, pg_catalog, public";

    private final Bm25TokenDictionaryMapper tokenDictionaryMapper;
    private final ChunkBgeM3Mapper chunkBgeM3Mapper;
    private final JdbcTemplate jdbcTemplate;

    @Transactional(readOnly = true)
    public List<RagRetrievalResult> searchTitle(
            List<String> kbIds,
            String query,
            String sourceName,
            String sourceType,
            String contentPathPrefix,
            int limit
    ) {
        String queryVector = queryVector(query);
        if (!StringUtils.hasText(queryVector)) {
            return List.of();
        }
        jdbcTemplate.execute(BM25_SEARCH_PATH);
        List<RagRetrievalResult> results = chunkBgeM3Mapper.searchByTitleBm25(
                kbIds,
                queryVector,
                sourceName,
                sourceType,
                contentPathPrefix,
                VchordBm25ProjectionService.INDEX_VERSION,
                limit
        );
        return annotateRank(results, true);
    }

    @Transactional(readOnly = true)
    public List<RagRetrievalResult> searchContent(
            List<String> kbIds,
            String query,
            String sourceName,
            String sourceType,
            String contentPathPrefix,
            int limit
    ) {
        String queryVector = queryVector(query);
        if (!StringUtils.hasText(queryVector)) {
            return List.of();
        }
        jdbcTemplate.execute(BM25_SEARCH_PATH);
        List<RagRetrievalResult> results = chunkBgeM3Mapper.searchByContentBm25(
                kbIds,
                queryVector,
                sourceName,
                sourceType,
                contentPathPrefix,
                VchordBm25ProjectionService.INDEX_VERSION,
                limit
        );
        return annotateRank(results, false);
    }

    private String queryVector(String query) {
        List<String> tokens = RetrievableTitleLexicalizer.tokenizeWithDuplicates(query);
        if (tokens.isEmpty()) {
            return null;
        }
        List<String> uniqueTokens = tokens.stream()
                .distinct()
                .sorted()
                .toList();
        Map<String, Long> tokenIds = tokenDictionaryMapper.selectTokenIds(uniqueTokens).stream()
                .collect(Collectors.toMap(
                        Bm25TokenDictionaryEntry::getToken,
                        Bm25TokenDictionaryEntry::getTokenId,
                        (left, right) -> left
                ));
        Map<Long, Integer> frequencies = new TreeMap<>();
        for (String token : tokens) {
            Long tokenId = tokenIds.get(token);
            if (tokenId != null) {
                frequencies.merge(tokenId, 1, Integer::sum);
            }
        }
        if (frequencies.isEmpty()) {
            return null;
        }
        return "{" + frequencies.entrySet().stream()
                .map(entry -> entry.getKey() + ":" + entry.getValue())
                .collect(Collectors.joining(",")) + "}";
    }

    private List<RagRetrievalResult> annotateRank(List<RagRetrievalResult> results, boolean titleChannel) {
        for (int i = 0; i < results.size(); i++) {
            RagRetrievalResult result = results.get(i);
            int rank = result.getRank() == null || result.getRank() <= 0 ? i + 1 : result.getRank();
            result.setRank(rank);
            if (titleChannel) {
                result.setTitleBm25Rank(rank);
            } else {
                result.setContentBm25Rank(rank);
            }
        }
        return results;
    }
}
