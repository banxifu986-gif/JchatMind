package com.kama.jchatmind.rag;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class G2CurrentFixtureProfileLoader {

    private G2CurrentFixtureProfileLoader() {
    }

    static G2CurrentFixtureProfile load(String manifestPath) throws IOException {
        RagEvaluationDataset dataset = RagEvaluationDatasetLoader.load(manifestPath);
        validateCurrentFixture(dataset);

        RagRouter router = new RagRouter();
        List<G2CurrentFixtureCase> cases = dataset.cases().stream()
                .map(item -> toProfileCase(item, router))
                .toList();
        Map<String, Integer> routeCounts = new LinkedHashMap<>();
        cases.forEach(item -> routeCounts.merge(item.expectedRoute(), 1, Integer::sum));

        return new G2CurrentFixtureProfile(
                dataset.manifest().datasetId(),
                cases.size(),
                (int) dataset.cases().stream().filter(item -> !item.shouldAbstain()).count(),
                (int) dataset.cases().stream().filter(RagEvaluationCase::shouldAbstain).count(),
                (int) dataset.cases().stream().filter(item -> "follow_up_contextual_rewrite".equals(item.queryType())).count(),
                (int) dataset.cases().stream().filter(item -> "cross_document".equals(item.queryType())).count(),
                List.copyOf(cases),
                Map.copyOf(routeCounts)
        );
    }

    private static G2CurrentFixtureCase toProfileCase(RagEvaluationCase item, RagRouter router) {
        RagRouteDecision decision = router.decide(
                item.query(),
                item.kbScope(),
                true,
                false,
                true
        );
        return new G2CurrentFixtureCase(
                item.caseId(),
                item.query(),
                item.queryType(),
                List.copyOf(item.goldChunkIds()),
                List.copyOf(item.sourceDocumentIds()),
                item.shouldAbstain(),
                !item.shouldAbstain(),
                decision.route().name()
        );
    }

    private static void validateCurrentFixture(RagEvaluationDataset dataset) {
        RagEvaluationDatasetManifest manifest = dataset.manifest();
        if (!"fixture-fast-v1".equals(manifest.datasetId())
                || !"markdown-section-v1".equals(manifest.chunkingVersion())
                || !"bge-m3".equals(manifest.embeddingModel())
                || manifest.defaultTopK() != 10) {
            throw new IllegalStateException("当前 G2 profile 只能加载 fixture-fast-v1 的冻结 manifest");
        }
    }
}
