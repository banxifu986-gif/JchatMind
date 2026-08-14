package com.kama.jchatmind.rag;

import java.util.List;

record RagEvaluationDataset(
        RagEvaluationDatasetManifest manifest,
        List<RagEvaluationCase> cases
) {
}
