package com.kama.jchatmind.rag;

import java.util.List;

record RagRegressionCandidateCase(
        String caseId,
        String query,
        String queryType,
        String difficulty,
        String logicalChunkId,
        String logicalSectionPath,
        String sourceDocumentLogicalId,
        String sourceDocumentSha256,
        List<RagEvaluationConversationTurn> conversation,
        List<String> additionalGoldLogicalChunkIds,
        List<String> retrievalGoldLogicalChunkIds,
        List<String> goldFacts,
        Boolean shouldAbstain,
        String abstentionReason,
        String reviewStatus,
        String createdBy,
        String reviewedBy,
        String reviewedAt,
        List<String> labels
) {
}
