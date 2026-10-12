package com.kama.jchatmind.rag;

import com.kama.jchatmind.service.impl.VchordBm25ProjectionService;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class G2CurrentMultimodalCandidateRuntimeBuilder {

    private G2CurrentMultimodalCandidateRuntimeBuilder() {
    }

    static PreparedRuntimePlan build(
            G2CurrentMultimodalCandidateFixtureLoader.CandidateProfile profile,
            List<float[]> embeddings,
            List<VchordBm25ProjectionService.Projection> projections
    ) {
        if (profile == null || !"candidate".equals(profile.status()) || profile.releaseEligible()
                || profile.assetImportPlan() == null
                || embeddings == null || projections == null) {
            throw new IllegalArgumentException("multimodal candidate runtime 输入不完整或已越过 candidate 状态");
        }

        G2CurrentMultimodalCandidateFixtureLoader.AssetImportPlan plan = profile.assetImportPlan();
        Map<String, Object> knowledgeBaseMetadata = plan.knowledgeBaseMetadata();
        if (!plan.candidateOnly()
                || !Boolean.TRUE.equals(knowledgeBaseMetadata.get("candidateOnly"))
                || !profile.datasetId().equals(knowledgeBaseMetadata.get("datasetId"))
                || !profile.manifestSha256().equals(knowledgeBaseMetadata.get("manifestSha256"))) {
            throw new IllegalArgumentException("multimodal candidate runtime plan 缺少 candidate-only 元数据");
        }

        List<G2CurrentMultimodalCandidateFixtureLoader.ChunkImportRow> chunks = plan.chunkRows();
        if (chunks == null || chunks.isEmpty()
                || chunks.size() != embeddings.size() || chunks.size() != projections.size()) {
            throw new IllegalArgumentException("multimodal candidate chunk、embedding 与 projection 数量不一致");
        }

        List<RuntimeChunk> runtimeChunks = new ArrayList<>(chunks.size());
        for (int index = 0; index < chunks.size(); index++) {
            G2CurrentMultimodalCandidateFixtureLoader.ChunkImportRow chunk = chunks.get(index);
            float[] embedding = embeddings.get(index);
            VchordBm25ProjectionService.Projection projection = projections.get(index);
            if (chunk == null || !StringUtils.hasText(chunk.chunkId())
                    || !StringUtils.hasText(chunk.logicalChunkId())
                    || embedding == null || embedding.length != 1024
                    || !allFinite(embedding)
                    || projection == null
                    || !StringUtils.hasText(projection.titleVector())
                    || !StringUtils.hasText(projection.contentVector())
                    || projection.indexVersion() == null
                    || projection.indexVersion() != VchordBm25ProjectionService.INDEX_VERSION) {
                throw new IllegalArgumentException("multimodal candidate runtime chunk 缺少有效 embedding 或 BM25 projection");
            }
            runtimeChunks.add(new RuntimeChunk(chunk, embedding.clone(), projection));
        }
        return new PreparedRuntimePlan(plan, true, List.copyOf(runtimeChunks));
    }

    private static boolean allFinite(float[] values) {
        for (float value : values) {
            if (!Float.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    record PreparedRuntimePlan(
            G2CurrentMultimodalCandidateFixtureLoader.AssetImportPlan assetImportPlan,
            boolean candidateOnly,
            List<RuntimeChunk> chunks
    ) {
    }

    record RuntimeChunk(
            G2CurrentMultimodalCandidateFixtureLoader.ChunkImportRow chunk,
            float[] embedding,
            VchordBm25ProjectionService.Projection projection
    ) {
    }
}
