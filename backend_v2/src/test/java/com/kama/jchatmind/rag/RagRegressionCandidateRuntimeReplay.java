package com.kama.jchatmind.rag;

import java.util.List;

record RagRegressionCandidateRuntimeReplay(
        String caseId,
        List<String> topRuntimeChunkUuids,
        boolean abstained
) {
}
