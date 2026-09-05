package com.kama.jchatmind.rag;

import java.util.List;
import java.util.Map;

record G2CurrentFixtureProfile(
        String datasetId,
        int caseCount,
        int answerableCaseCount,
        int abstentionCaseCount,
        int followUpCaseCount,
        int crossDocumentCaseCount,
        List<G2CurrentFixtureCase> cases,
        Map<String, Integer> routeCounts
) {
}
