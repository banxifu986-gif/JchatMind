package com.kama.jchatmind.rag;

import java.util.List;

record RagRegressionCandidateChunkUuidMapping(
        String executionStatus,
        String knowledgeBaseId,
        int total,
        int mapped,
        int unmapped,
        int ambiguous,
        List<Item> items
) {

    static RagRegressionCandidateChunkUuidMapping fromItems(
            String executionStatus,
            String knowledgeBaseId,
            List<Item> items
    ) {
        int mapped = (int) items.stream().filter(item -> "mapped".equals(item.status())).count();
        int unmapped = (int) items.stream().filter(item -> "unmapped".equals(item.status())).count();
        int ambiguous = (int) items.stream().filter(item -> "ambiguous".equals(item.status())).count();
        return new RagRegressionCandidateChunkUuidMapping(
                executionStatus, knowledgeBaseId, items.size(), mapped, unmapped, ambiguous, List.copyOf(items)
        );
    }

    record Item(
            String logicalChunkId,
            String sourceDocumentLogicalId,
            String sourceSectionAnchor,
            List<String> runtimeChunkUuids,
            String status
    ) {
    }
}
