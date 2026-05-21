package com.kama.jchatmind.service;

import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.model.dto.RagRetrievalContext;

import java.util.List;

public interface RagService {
    float[] embed(String text);

    List<String> similaritySearch(List<String> kbIds, String title);

    List<RagRetrievalResult> retrieve(List<String> kbIds, String query, int limit);

    List<RagRetrievalResult> retrieve(List<String> kbIds, String query, RagRetrievalContext context, int limit);
}
