package com.kama.jchatmind.service;

import com.kama.jchatmind.model.dto.RagRetrievalResult;
import com.kama.jchatmind.model.dto.RagRetrievalContext;

import java.util.List;

public interface RagService {
    float[] embed(String text);

    List<String> similaritySearch(String kbId, String title);

    List<RagRetrievalResult> retrieve(String kbId, String query, int limit);

    List<RagRetrievalResult> retrieve(String kbId, String query, RagRetrievalContext context, int limit);
}
