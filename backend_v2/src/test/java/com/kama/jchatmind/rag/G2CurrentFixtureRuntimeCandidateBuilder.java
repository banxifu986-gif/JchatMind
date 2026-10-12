package com.kama.jchatmind.rag;

import com.kama.jchatmind.service.impl.VchordBm25ProjectionService;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class G2CurrentFixtureRuntimeCandidateBuilder {

    private G2CurrentFixtureRuntimeCandidateBuilder() {
    }

    static List<RuntimeCandidate> build(
            G2CurrentFixtureRuntimeCorpus fixture,
            List<float[]> embeddings,
            List<VchordBm25ProjectionService.Projection> projections
    ) {
        if (fixture == null || !"fixture-fast-v1".equals(fixture.datasetId())
                || fixture.chunksByLogicalId() == null || fixture.chunksByLogicalId().isEmpty()
                || embeddings == null || projections == null) {
            throw new IllegalArgumentException("fixture-fast-v1 runtime candidate 输入不完整");
        }

        List<G2CurrentFixtureRuntimeCorpus.Chunk> chunks = fixture.chunksByLogicalId().values().stream()
                .sorted(Comparator.comparing(G2CurrentFixtureRuntimeCorpus.Chunk::logicalChunkId))
                .toList();
        if (chunks.size() != embeddings.size() || chunks.size() != projections.size()) {
            throw new IllegalArgumentException("runtime chunk、embedding 与 BM25 projection 数量不一致");
        }

        List<RuntimeCandidate> candidates = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            float[] embedding = embeddings.get(index);
            VchordBm25ProjectionService.Projection projection = projections.get(index);
            if (embedding == null || embedding.length != 1024) {
                throw new IllegalArgumentException("fixture-fast-v1 runtime embedding 必须为 1024 维");
            }
            if (projection == null || !StringUtils.hasText(projection.titleVector())
                    || !StringUtils.hasText(projection.contentVector())
                    || projection.indexVersion() == null
                    || projection.indexVersion() != VchordBm25ProjectionService.INDEX_VERSION) {
                throw new IllegalArgumentException("fixture-fast-v1 runtime BM25 projection 无效");
            }
            candidates.add(new RuntimeCandidate(chunks.get(index), embedding, projection));
        }
        return List.copyOf(candidates);
    }

    record RuntimeCandidate(
            G2CurrentFixtureRuntimeCorpus.Chunk chunk,
            float[] embedding,
            VchordBm25ProjectionService.Projection projection
    ) {
    }
}
