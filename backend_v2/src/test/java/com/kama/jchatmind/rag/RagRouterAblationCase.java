package com.kama.jchatmind.rag;

import java.util.Set;

record RagRouterAblationCase(
        String caseId,
        Set<String> goldChunkIds,
        boolean shouldAbstain,
        boolean externalAllowed,
        String expectedRoute
) {
}
