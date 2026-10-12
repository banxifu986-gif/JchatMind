package com.kama.jchatmind.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class G2CurrentFixtureRuntimeImportPlan {

    private static final String EVALUATION_NAMESPACE = "rag-eval";

    private G2CurrentFixtureRuntimeImportPlan() {
    }

    static Prepared prepare(
            G2CurrentFixtureRuntimeCorpus fixture,
            java.util.List<G2CurrentFixtureRuntimeCandidateBuilder.RuntimeCandidate> candidates
    ) {
        if (fixture == null || !"fixture-fast-v1".equals(fixture.datasetId())
                || fixture.chunksByLogicalId() == null || fixture.chunksByLogicalId().isEmpty()
                || candidates == null || candidates.isEmpty()
                || candidates.size() != fixture.chunksByLogicalId().size()) {
            throw new IllegalArgumentException("fixture-fast-v1 runtime import plan 输入不完整");
        }

        Map<String, String> logicalChunkIdByRuntimeUuid = new LinkedHashMap<>();
        Set<String> expectedLogicalChunkIds = fixture.chunksByLogicalId().keySet();
        for (G2CurrentFixtureRuntimeCandidateBuilder.RuntimeCandidate candidate : candidates) {
            if (candidate == null || candidate.chunk() == null
                    || !expectedLogicalChunkIds.contains(candidate.chunk().logicalChunkId())
                    || candidate.chunk().runtimeChunkUuid() == null
                    || candidate.chunk().runtimeChunkUuid().isBlank()
                    || logicalChunkIdByRuntimeUuid.putIfAbsent(
                    candidate.chunk().runtimeChunkUuid(), candidate.chunk().logicalChunkId()) != null) {
                throw new IllegalArgumentException("fixture-fast-v1 runtime candidate ID 无效或重复");
            }
        }

        if (!Set.copyOf(logicalChunkIdByRuntimeUuid.values()).equals(expectedLogicalChunkIds)) {
            throw new IllegalArgumentException("fixture-fast-v1 runtime import plan 未覆盖全部 corpus chunk");
        }
        for (RagEvaluationCase evaluationCase : fixture.cases()) {
            if (!evaluationCase.shouldAbstain()
                    && !expectedLogicalChunkIds.containsAll(evaluationCase.goldChunkIds())) {
                throw new IllegalArgumentException("fixture-fast-v1 runtime import plan 缺少 gold chunk");
            }
        }

        String fixtureSha256 = fixture.fixtureSha256();
        if (!fixtureFingerprint(fixture.manifest()).equals(fixtureSha256)) {
            throw new IllegalArgumentException("fixture-fast-v1 runtime import fingerprint 与 manifest 不一致");
        }
        String knowledgeBaseId = UUID.nameUUIDFromBytes(
                (EVALUATION_NAMESPACE + ":" + fixture.datasetId() + ":" + fixtureSha256)
                        .getBytes(StandardCharsets.UTF_8)
        ).toString();
        Map<String, Object> knowledgeBaseMetadata = Map.of(
                "evaluationNamespace", EVALUATION_NAMESPACE,
                "datasetId", fixture.datasetId(),
                "fixtureSha256", fixtureSha256,
                "caseSha256", fixture.caseSha256(),
                "corpusSha256", fixture.corpusSha256()
        );
        Map<String, Map<String, Object>> chunkMetadataByLogicalChunkId = new LinkedHashMap<>();
        for (G2CurrentFixtureRuntimeCandidateBuilder.RuntimeCandidate candidate : candidates) {
            G2CurrentFixtureRuntimeCorpus.Chunk chunk = candidate.chunk();
            Map<String, Object> chunkMetadata = new LinkedHashMap<>(chunk.metadata());
            chunkMetadata.put("evaluationNamespace", EVALUATION_NAMESPACE);
            chunkMetadata.put("datasetId", fixture.datasetId());
            chunkMetadata.put("fixtureSha256", fixtureSha256);
            chunkMetadata.put("caseSha256", fixture.caseSha256());
            chunkMetadata.put("corpusSha256", fixture.corpusSha256());
            chunkMetadata.put("sourceDocumentId", chunk.sourceDocumentId());
            chunkMetadata.put("logicalChunkId", chunk.logicalChunkId());
            chunkMetadataByLogicalChunkId.put(chunk.logicalChunkId(), chunkMetadata);
        }
        return new Prepared(
                fixture.datasetId(),
                fixtureSha256,
                knowledgeBaseId,
                Map.copyOf(logicalChunkIdByRuntimeUuid),
                knowledgeBaseMetadata,
                Map.copyOf(chunkMetadataByLogicalChunkId)
        );
    }

    static String fixtureFingerprint(RagEvaluationDatasetManifest manifest) {
        if (manifest == null) {
            throw new IllegalArgumentException("runtime import fingerprint 缺少 manifest");
        }
        String value = String.join("\n",
                manifest.datasetId(),
                manifest.schemaVersion(),
                manifest.caseSha256(),
                manifest.corpusSha256(),
                manifest.chunkingVersion(),
                manifest.embeddingModel(),
                manifest.embeddingInputVersion(),
                Integer.toString(manifest.defaultTopK()),
                manifest.status()
        );
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 不支持 SHA-256", exception);
        }
    }

    record Prepared(
            String datasetId,
            String fixtureSha256,
            String knowledgeBaseId,
            Map<String, String> logicalChunkIdByRuntimeUuid,
            Map<String, Object> knowledgeBaseMetadata,
            Map<String, Map<String, Object>> chunkMetadataByLogicalChunkId
    ) {
        Prepared {
            logicalChunkIdByRuntimeUuid = Map.copyOf(logicalChunkIdByRuntimeUuid);
            knowledgeBaseMetadata = Map.copyOf(knowledgeBaseMetadata);
            chunkMetadataByLogicalChunkId = Map.copyOf(chunkMetadataByLogicalChunkId);
        }
    }
}
