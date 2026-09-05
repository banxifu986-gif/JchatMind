package com.kama.jchatmind.rag;

import java.util.List;

record RagRouterAblationDataset(
        String datasetVersion,
        List<RagRouterAblationCase> cases
) {
}
