package com.kama.jchatmind.rag;

import java.util.List;

record RagEvaluationDatasetManifest(
        String datasetId,
        String schemaVersion,
        String caseFile,
        String caseSha256,
        List<String> corpusFiles,
        String corpusSha256,
        String chunkingVersion,
        String embeddingModel,
        String embeddingInputVersion,
        int defaultTopK,
        String status
) {
}
