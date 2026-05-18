package com.kama.jchatmind.service;

import com.kama.jchatmind.model.dto.QueryRewriteResult;
import com.kama.jchatmind.model.dto.RagRetrievalContext;

public interface QueryRewriteService {
    QueryRewriteResult rewrite(String kbId, String query, RagRetrievalContext context);
}
