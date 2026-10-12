package com.kama.jchatmind.intent;

import java.util.Map;

record IntentEvaluationDatasetManifest(
        String datasetId,
        String schemaVersion,
        String caseFile,
        String caseSha256,
        String status,
        boolean fixtureOnly,
        String sourceDatasetId,
        String generatorVersion,
        Integer expectedCaseCount,
        Map<String, Integer> splitCounts
) {
}
