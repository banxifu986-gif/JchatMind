package com.kama.jchatmind.intent;

import java.nio.file.Path;

record IntentEvaluationCandidateExport(
        Path path,
        Path manifestPath,
        int caseCount,
        String sha256
) {
}
