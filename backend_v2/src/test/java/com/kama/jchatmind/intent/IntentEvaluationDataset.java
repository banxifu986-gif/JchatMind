package com.kama.jchatmind.intent;

import java.util.List;

record IntentEvaluationDataset(
        IntentEvaluationDatasetManifest manifest,
        List<IntentEvaluationCase> cases,
        String effectiveCaseSha256
) {
}
