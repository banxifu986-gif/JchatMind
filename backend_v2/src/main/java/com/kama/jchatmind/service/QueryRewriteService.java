package com.kama.jchatmind.service;

import com.kama.jchatmind.model.dto.QueryRewriteResult;
import com.kama.jchatmind.model.dto.RagRetrievalContext;

import java.util.List;

public interface QueryRewriteService {
    QueryRewriteResult rewrite(List<String> kbIds, String query, RagRetrievalContext context);
}
