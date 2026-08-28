package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;

final class MmarcoZhSampledManifestImporter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    ManifestImportResult importManifest(
            Path manifestPath,
            Connection connection,
            String ownerId,
            int embeddingBatchSize,
            Function<List<String>, List<float[]>> embeddingFunction,
            BiFunction<String, String, Bm25Projection> bm25ProjectionFunction
    ) throws IOException, SQLException {
        if (manifestPath == null || !Files.isRegularFile(manifestPath) || !Files.isReadable(manifestPath)
                || connection == null || isBlank(ownerId) || embeddingBatchSize <= 0
                || embeddingFunction == null || bm25ProjectionFunction == null) {
            throw new IllegalArgumentException("mMARCO manifest 导入输入不完整");
        }
        MmarcoZhSampledDatasetFreezer.FrozenManifest manifest = OBJECT_MAPPER.readValue(
                Files.readString(manifestPath),
                MmarcoZhSampledDatasetFreezer.FrozenManifest.class
        );
        validateManifest(manifest);
        String candidateManifestSha256 = sha256(manifestPath);
        validateCandidateIdentities(manifest.candidates());
        MmarcoZhSampledIsolatedImporter importer = new MmarcoZhSampledIsolatedImporter();
        var existingImport = importer.findExistingImport(
                connection,
                manifest.datasetVersion(),
                candidateManifestSha256,
                manifest.candidates()
        );
        if (existingImport.isPresent()) {
            return new ManifestImportResult(candidateManifestSha256, manifest, existingImport.orElseThrow());
        }
        Map<String, String> logicalChunkIdByRuntimeUuid = new LinkedHashMap<>();
        Map<String, String> documentIdByPassageId = new LinkedHashMap<>();
        String knowledgeBaseId = null;
        for (int start = 0; start < manifest.candidates().size(); start += embeddingBatchSize) {
            List<MmarcoZhSampledDatasetFreezer.Candidate> batch = manifest.candidates().subList(
                    start, Math.min(start + embeddingBatchSize, manifest.candidates().size())
            );
            List<MmarcoZhSampledIsolatedImporter.ImportedCandidate> importedCandidates = toImportedCandidateBatch(
                    batch, embeddingFunction, bm25ProjectionFunction
            );
            MmarcoZhSampledIsolatedImporter.ImportResult batchResult = importer.importCandidates(
                    connection,
                    new MmarcoZhSampledIsolatedImporter.ImportRequest(
                            manifest.datasetVersion(), candidateManifestSha256, ownerId, importedCandidates
                    )
            );
            if (knowledgeBaseId == null) {
                knowledgeBaseId = batchResult.knowledgeBaseId();
            } else if (!knowledgeBaseId.equals(batchResult.knowledgeBaseId())) {
                throw new IllegalStateException("mMARCO 分批导入产生了不同的隔离 KB");
            }
            mergeMappings(logicalChunkIdByRuntimeUuid, batchResult.logicalChunkIdByRuntimeUuid(), "runtime chunk UUID");
            mergeMappings(documentIdByPassageId, batchResult.documentIdByPassageId(), "passage document ID");
        }
        return new ManifestImportResult(
                candidateManifestSha256,
                manifest,
                new MmarcoZhSampledIsolatedImporter.ImportResult(
                        knowledgeBaseId, Map.copyOf(logicalChunkIdByRuntimeUuid), Map.copyOf(documentIdByPassageId)
                )
        );
    }

    List<MmarcoZhSampledIsolatedImporter.ImportedCandidate> toImportedCandidates(
            List<MmarcoZhSampledDatasetFreezer.Candidate> candidates,
            int embeddingBatchSize,
            Function<List<String>, List<float[]>> embeddingFunction,
            BiFunction<String, String, Bm25Projection> bm25ProjectionFunction
    ) {
        if (candidates == null || candidates.isEmpty() || embeddingBatchSize <= 0) {
            throw new IllegalArgumentException("mMARCO 批量候选输入无效");
        }
        List<MmarcoZhSampledIsolatedImporter.ImportedCandidate> importedCandidates = new ArrayList<>(candidates.size());
        for (int start = 0; start < candidates.size(); start += embeddingBatchSize) {
            importedCandidates.addAll(toImportedCandidateBatch(
                    candidates.subList(start, Math.min(start + embeddingBatchSize, candidates.size())),
                    embeddingFunction,
                    bm25ProjectionFunction
            ));
        }
        return List.copyOf(importedCandidates);
    }

    private List<MmarcoZhSampledIsolatedImporter.ImportedCandidate> toImportedCandidateBatch(
            List<MmarcoZhSampledDatasetFreezer.Candidate> candidates,
            Function<List<String>, List<float[]>> embeddingFunction,
            BiFunction<String, String, Bm25Projection> bm25ProjectionFunction
    ) {
        List<float[]> embeddings = embeddingFunction.apply(candidates.stream()
                .map(MmarcoZhSampledDatasetFreezer.Candidate::content).toList());
        if (embeddings == null || embeddings.size() != candidates.size()) {
            throw new IllegalStateException("mMARCO 批量 embedding 数量与候选不一致");
        }
        List<MmarcoZhSampledIsolatedImporter.ImportedCandidate> importedCandidates = new ArrayList<>(candidates.size());
        for (int index = 0; index < candidates.size(); index++) {
            importedCandidates.add(toImportedCandidate(candidates.get(index), embeddings.get(index), bm25ProjectionFunction));
        }
        return List.copyOf(importedCandidates);
    }

    private void validateCandidateIdentities(List<MmarcoZhSampledDatasetFreezer.Candidate> candidates) {
        Set<String> passageIds = new LinkedHashSet<>();
        Set<String> logicalChunkIds = new LinkedHashSet<>();
        Set<String> runtimeChunkUuids = new LinkedHashSet<>();
        for (MmarcoZhSampledDatasetFreezer.Candidate candidate : candidates) {
            if (candidate == null || isBlank(candidate.passageId()) || isBlank(candidate.logicalChunkId())
                    || isBlank(candidate.runtimeChunkUuid()) || !passageIds.add(candidate.passageId())
                    || !logicalChunkIds.add(candidate.logicalChunkId()) || !runtimeChunkUuids.add(candidate.runtimeChunkUuid())) {
                throw new IllegalStateException("mMARCO frozen manifest 候选 ID 无效或重复");
            }
        }
    }

    private void mergeMappings(Map<String, String> target, Map<String, String> source, String label) {
        for (Map.Entry<String, String> entry : source.entrySet()) {
            if (target.putIfAbsent(entry.getKey(), entry.getValue()) != null) {
                throw new IllegalStateException("mMARCO 分批导入产生重复 " + label);
            }
        }
    }

    private MmarcoZhSampledIsolatedImporter.ImportedCandidate toImportedCandidate(
            MmarcoZhSampledDatasetFreezer.Candidate candidate,
            float[] embedding,
            BiFunction<String, String, Bm25Projection> bm25ProjectionFunction
    ) {
        Bm25Projection projection = bm25ProjectionFunction.apply(candidate.passageId(), candidate.content());
        if (projection == null || isBlank(projection.titleVector()) || isBlank(projection.contentVector())
                || projection.indexVersion() == null || projection.indexVersion() <= 0) {
            throw new IllegalStateException("mMARCO 候选 BM25 投影无效: " + candidate.passageId());
        }
        return new MmarcoZhSampledIsolatedImporter.ImportedCandidate(
                candidate,
                embedding,
                projection.titleVector(),
                projection.contentVector(),
                projection.indexVersion()
        );
    }

    private void validateManifest(MmarcoZhSampledDatasetFreezer.FrozenManifest manifest) {
        if (manifest == null || isBlank(manifest.datasetVersion()) || isBlank(manifest.preprocessVersion())
                || !MmarcoZhSampledDatasetFreezer.UPSTREAM_REVISION.equals(manifest.upstreamRevision())
                || !MmarcoZhSampledDatasetFreezer.LANGUAGE.equals(manifest.language())
                || !MmarcoZhSampledDatasetFreezer.MAPPING_VERSION.equals(manifest.mappingVersion())
                || manifest.sourceSha256() == null || isBlank(manifest.sourceSha256().collection())
                || isBlank(manifest.sourceSha256().queries()) || isBlank(manifest.sourceSha256().qrels())
                || isBlank(manifest.sourceSha256().hardNegativeRun())
                || manifest.candidates() == null || manifest.candidates().isEmpty()) {
            throw new IllegalStateException("mMARCO frozen manifest 不完整");
        }
    }

    private String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (var input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 不支持 SHA-256", exception);
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    record Bm25Projection(String titleVector, String contentVector, Integer indexVersion) {
    }

    record ManifestImportResult(
            String candidateManifestSha256,
            MmarcoZhSampledDatasetFreezer.FrozenManifest manifest,
            MmarcoZhSampledIsolatedImporter.ImportResult importResult
    ) {
    }
}
