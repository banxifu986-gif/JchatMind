package com.kama.jchatmind.rag;

import java.util.List;

record RagRegressionCandidateDataset(
        String datasetId,
        String status,
        String sourceKnowledgeBaseId,
        List<RagRegressionCandidateCase> cases
) {
}
