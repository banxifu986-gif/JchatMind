package com.kama.jchatmind.rag;

import lombok.Builder;

import java.util.List;

@Builder
public record RagRouteDecision(
        Route route,
        List<String> searchScope,
        RewriteMode rewriteMode,
        List<String> retrievalChannels,
        int topK,
        boolean rerankEnabled,
        boolean needClarification,
        String reason
) {
    public enum Route {
        DIRECT,
        PRIVATE_RAG,
        HYBRID_RAG,
        MULTIMODAL_RAG,
        EXTERNAL_TOOL,
        CLARIFY,
        ABSTAIN
    }

    public enum RewriteMode {
        NONE,
        LIGHT,
        CONTEXTUAL
    }
}
