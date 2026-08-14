package com.kama.jchatmind.rag;

import java.util.List;

record RagRegressionCandidateSourceReport(
        String datasetId,
        int total,
        int verified,
        int failed,
        String runtimeChunkUuidMappingStatus,
        List<Item> items
) {
    record Item(
            String caseId,
            String sourceDocumentLogicalId,
            String logicalChunkId,
            String sourceSectionAnchor,
            boolean hashMatches,
            boolean anchorMatches,
            String status
    ) {
    }
}
