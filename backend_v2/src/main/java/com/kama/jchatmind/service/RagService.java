package com.kama.jchatmind.service;

import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.model.dto.RagRetrievalContext;

import java.util.List;
import java.util.Map;

public interface RagService {
    float[] embed(String text);

    List<String> similaritySearch(List<String> kbIds, String title);

    List<RagRetrievalResult> retrieve(List<String> kbIds, String query, int limit);

    List<RagRetrievalResult> retrieve(List<String> kbIds, String query, RagRetrievalContext context, int limit);

    List<RagRetrievalResult> retrievePdfPageAssets(
            List<String> kbIds,
            String query,
            RagRetrievalContext context,
            int limit
    );

    List<RagRetrievalResult> retrieveMarkdownTableAssets(
            List<String> kbIds,
            String query,
            RagRetrievalContext context,
            int limit
    );

    Map<String, Long> indexRevisions(List<String> kbIds);

    String retrievalConfigurationFingerprint();
}
