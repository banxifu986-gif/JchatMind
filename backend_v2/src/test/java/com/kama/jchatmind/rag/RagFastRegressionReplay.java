package com.kama.jchatmind.rag;

import java.util.List;

record RagFastRegressionReplay(
        String caseId,
        List<String> topChunkIds,
        boolean abstained
) {
}
