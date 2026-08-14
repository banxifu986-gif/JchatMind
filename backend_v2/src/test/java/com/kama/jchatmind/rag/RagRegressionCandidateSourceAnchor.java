package com.kama.jchatmind.rag;

record RagRegressionCandidateSourceAnchor(
        String sourceDocumentLogicalId,
        String sourceDocumentSha256,
        String sourceSectionAnchor
) {
}
