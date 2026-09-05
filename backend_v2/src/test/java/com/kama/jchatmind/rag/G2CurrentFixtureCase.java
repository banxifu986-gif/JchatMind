package com.kama.jchatmind.rag;

import java.util.List;

record G2CurrentFixtureCase(
        String caseId,
        String query,
        String queryType,
        List<String> goldChunkIds,
        List<String> sourceDocumentIds,
        boolean shouldAbstain,
        boolean citationRequired,
        String expectedRoute
) {
}
