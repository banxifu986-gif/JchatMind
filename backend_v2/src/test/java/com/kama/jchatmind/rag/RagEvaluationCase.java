package com.kama.jchatmind.rag;

import java.util.List;

record RagEvaluationCase(
        String caseId,
        String datasetId,
        String query,
        String queryType,
        String difficulty,
        List<RagEvaluationConversationTurn> conversation,
        List<String> kbScope,
        List<String> goldChunkIds,
        List<String> goldFacts,
        boolean shouldAbstain,
        String abstentionReason,
        List<String> sourceDocumentIds,
        List<String> labels,
        String createdBy,
        String reviewStatus
) {
}
