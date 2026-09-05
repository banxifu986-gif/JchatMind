package com.kama.jchatmind.rag;

import java.util.List;

record RagRegressionReleaseDataset(
        RagRegressionReleaseManifest manifest,
        RagRegressionCandidateDataset sourceCandidate,
        List<RagRegressionCandidateCase> cases
) {
}
