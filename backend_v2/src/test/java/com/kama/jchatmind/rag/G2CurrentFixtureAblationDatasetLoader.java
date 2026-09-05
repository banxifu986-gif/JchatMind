package com.kama.jchatmind.rag;

import java.io.IOException;
import java.util.List;
import java.util.Set;

final class G2CurrentFixtureAblationDatasetLoader {

    private G2CurrentFixtureAblationDatasetLoader() {
    }

    static RagRouterAblationDataset load(String manifestPath) throws IOException {
        G2CurrentFixtureProfile profile = G2CurrentFixtureProfileLoader.load(manifestPath);
        List<RagRouterAblationCase> cases = profile.cases().stream()
                .map(item -> new RagRouterAblationCase(
                        item.caseId(),
                        Set.copyOf(item.goldChunkIds()),
                        item.shouldAbstain(),
                        false,
                        item.expectedRoute()
                ))
                .toList();
        return new RagRouterAblationDataset(profile.datasetId(), cases);
    }
}
