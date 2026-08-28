package com.kama.jchatmind.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class MmarcoZhSampledIsolatedImporter {

    static final String MAPPING_VERSION = MmarcoZhSampledDatasetFreezer.MAPPING_VERSION;
    private static final String EVALUATION_NAMESPACE = "rag-eval";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    ImportResult importCandidates(Connection connection, ImportRequest request) throws SQLException {
        if (connection == null || connection.getAutoCommit()) {
            throw new IllegalStateException("mMARCO 隔离导入必须由调用方显式开启事务");
        }
        validateRequest(request);

        String knowledgeBaseId = deterministicUuid("mmarco:zh:knowledge-base:" + request.candidateManifestSha256());
        insertKnowledgeBase(connection, knowledgeBaseId, request);
        verifyKnowledgeBase(connection, knowledgeBaseId, request);

        Map<String, String> logicalChunkIdByRuntimeUuid = new LinkedHashMap<>();
        Map<String, String> documentIdByPassageId = new LinkedHashMap<>();
        for (ImportedCandidate imported : request.candidates()) {
            MmarcoZhSampledDatasetFreezer.Candidate candidate = imported.candidate();
            String documentId = deterministicUuid("mmarco:zh:document:" + candidate.passageId());
            insertDocument(connection, documentId, knowledgeBaseId, request, candidate);
            verifyDocument(connection, documentId, knowledgeBaseId, candidate);
            insertChunk(connection, candidate.runtimeChunkUuid(), knowledgeBaseId, documentId, request, imported);
            verifyChunk(connection, candidate.runtimeChunkUuid(), knowledgeBaseId, documentId, request, candidate);
            logicalChunkIdByRuntimeUuid.put(candidate.runtimeChunkUuid(), candidate.logicalChunkId());
            documentIdByPassageId.put(candidate.passageId(), documentId);
        }
        return new ImportResult(knowledgeBaseId, Map.copyOf(logicalChunkIdByRuntimeUuid), Map.copyOf(documentIdByPassageId));
    }

    Optional<ImportResult> findExistingImport(
            Connection connection,
            String datasetVersion,
            String candidateManifestSha256,
            List<MmarcoZhSampledDatasetFreezer.Candidate> candidates
    ) throws SQLException {
        if (connection == null || isBlank(datasetVersion) || isBlank(candidateManifestSha256)) {
            return Optional.empty();
        }
        if (!validCandidateIdentities(candidates)) {
            return Optional.empty();
        }

        String knowledgeBaseId = deterministicUuid("mmarco:zh:knowledge-base:" + candidateManifestSha256);
        if (!matchesKnowledgeBase(connection, knowledgeBaseId, datasetVersion, candidateManifestSha256)) {
            return Optional.empty();
        }

        Map<String, MmarcoZhSampledDatasetFreezer.Candidate> candidatesByRuntimeUuid = new LinkedHashMap<>();
        for (MmarcoZhSampledDatasetFreezer.Candidate candidate : candidates) {
            candidatesByRuntimeUuid.put(candidate.runtimeChunkUuid(), candidate);
        }

        Map<String, String> logicalChunkIdByRuntimeUuid = new LinkedHashMap<>();
        Map<String, String> documentIdByPassageId = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT chunk.id::text, chunk.doc_id::text, chunk.content, chunk.evaluation_namespace,
                       chunk.metadata->>'datasetVersion', chunk.metadata->>'candidateManifestSha256',
                       chunk.metadata->>'mappingVersion', chunk.metadata->>'logicalChunkId',
                       chunk.metadata->>'passageId', chunk.metadata->>'candidateSourceType',
                       document.kb_id::text, document.filename, document.filetype,
                       document.metadata->>'datasetVersion', document.metadata->>'passageId'
                FROM chunk_bge_m3 chunk
                JOIN document ON document.id = chunk.doc_id
                WHERE chunk.kb_id = CAST(? AS uuid)
                ORDER BY chunk.id
                """)) {
            statement.setString(1, knowledgeBaseId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String runtimeChunkUuid = result.getString(1);
                    MmarcoZhSampledDatasetFreezer.Candidate candidate = candidatesByRuntimeUuid.remove(runtimeChunkUuid);
                    if (candidate == null || !matchesCandidateRow(
                            result, knowledgeBaseId, datasetVersion, candidateManifestSha256, candidate
                    )) {
                        return Optional.empty();
                    }
                    String documentId = result.getString(2);
                    if (documentIdByPassageId.putIfAbsent(candidate.passageId(), documentId) != null) {
                        return Optional.empty();
                    }
                    logicalChunkIdByRuntimeUuid.put(runtimeChunkUuid, candidate.logicalChunkId());
                }
            }
        }
        if (!candidatesByRuntimeUuid.isEmpty()
                || logicalChunkIdByRuntimeUuid.size() != candidates.size()
                || documentIdByPassageId.size() != candidates.size()
                || !hasExactDocumentCount(connection, knowledgeBaseId, candidates.size())) {
            return Optional.empty();
        }
        return Optional.of(new ImportResult(
                knowledgeBaseId,
                Map.copyOf(logicalChunkIdByRuntimeUuid),
                Map.copyOf(documentIdByPassageId)
        ));
    }

    private boolean matchesKnowledgeBase(
            Connection connection,
            String knowledgeBaseId,
            String datasetVersion,
            String candidateManifestSha256
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT metadata->>'evaluationNamespace', metadata->>'datasetVersion',
                       metadata->>'candidateManifestSha256', metadata->>'mappingVersion'
                FROM knowledge_base
                WHERE id = CAST(? AS uuid)
                """)) {
            statement.setString(1, knowledgeBaseId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        && EVALUATION_NAMESPACE.equals(result.getString(1))
                        && datasetVersion.equals(result.getString(2))
                        && candidateManifestSha256.equals(result.getString(3))
                        && MAPPING_VERSION.equals(result.getString(4));
            }
        }
    }

    private boolean matchesCandidateRow(
            ResultSet result,
            String knowledgeBaseId,
            String datasetVersion,
            String candidateManifestSha256,
            MmarcoZhSampledDatasetFreezer.Candidate candidate
    ) throws SQLException {
        String expectedDocumentId = deterministicUuid("mmarco:zh:document:" + candidate.passageId());
        return expectedDocumentId.equals(result.getString(2))
                && candidate.content().equals(result.getString(3))
                && EVALUATION_NAMESPACE.equals(result.getString(4))
                && datasetVersion.equals(result.getString(5))
                && candidateManifestSha256.equals(result.getString(6))
                && MAPPING_VERSION.equals(result.getString(7))
                && candidate.logicalChunkId().equals(result.getString(8))
                && candidate.passageId().equals(result.getString(9))
                && candidate.sourceType().equals(result.getString(10))
                && knowledgeBaseId.equals(result.getString(11))
                && ("mmarco-zh-" + candidate.passageId() + ".tsv").equals(result.getString(12))
                && "tsv".equals(result.getString(13))
                && datasetVersion.equals(result.getString(14))
                && candidate.passageId().equals(result.getString(15));
    }

    private boolean hasExactDocumentCount(Connection connection, String knowledgeBaseId, int expectedCount) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT count(*)
                FROM document
                WHERE kb_id = CAST(? AS uuid)
                """)) {
            statement.setString(1, knowledgeBaseId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && expectedCount == result.getInt(1);
            }
        }
    }

    private void validateRequest(ImportRequest request) {
        if (request == null || isBlank(request.datasetVersion()) || isBlank(request.candidateManifestSha256())
                || isBlank(request.ownerId()) || request.candidates() == null || request.candidates().isEmpty()) {
            throw new IllegalArgumentException("mMARCO 隔离导入输入不完整");
        }
        Set<String> passageIds = new LinkedHashSet<>();
        Set<String> logicalChunkIds = new LinkedHashSet<>();
        Set<String> runtimeChunkUuids = new LinkedHashSet<>();
        for (ImportedCandidate imported : request.candidates()) {
            if (imported == null || imported.candidate() == null || imported.embedding() == null
                    || imported.embedding().length == 0 || isBlank(imported.titleBm25Vector())
                    || isBlank(imported.contentBm25Vector()) || imported.bm25IndexVersion() <= 0) {
                throw new IllegalArgumentException("mMARCO 候选缺少 embedding 或 BM25 投影");
            }
            MmarcoZhSampledDatasetFreezer.Candidate candidate = imported.candidate();
            String expectedLogicalChunkId = "mmarco:zh:" + candidate.passageId();
            if (isBlank(candidate.passageId()) || isBlank(candidate.logicalChunkId()) || isBlank(candidate.runtimeChunkUuid())
                    || isBlank(candidate.content()) || isBlank(candidate.sourceType())
                    || !expectedLogicalChunkId.equals(candidate.logicalChunkId())
                    || !deterministicUuid(candidate.logicalChunkId()).equals(candidate.runtimeChunkUuid())
                    || !passageIds.add(candidate.passageId())
                    || !logicalChunkIds.add(candidate.logicalChunkId())
                    || !runtimeChunkUuids.add(candidate.runtimeChunkUuid())) {
                throw new IllegalArgumentException("mMARCO 候选 ID 或确定性 UUID 无效/重复");
            }
        }
    }

    private boolean validCandidateIdentities(List<MmarcoZhSampledDatasetFreezer.Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return false;
        }
        Set<String> passageIds = new LinkedHashSet<>();
        Set<String> logicalChunkIds = new LinkedHashSet<>();
        Set<String> runtimeChunkUuids = new LinkedHashSet<>();
        for (MmarcoZhSampledDatasetFreezer.Candidate candidate : candidates) {
            if (candidate == null || isBlank(candidate.passageId()) || isBlank(candidate.logicalChunkId())
                    || isBlank(candidate.runtimeChunkUuid()) || isBlank(candidate.content()) || isBlank(candidate.sourceType())
                    || !("mmarco:zh:" + candidate.passageId()).equals(candidate.logicalChunkId())
                    || !deterministicUuid(candidate.logicalChunkId()).equals(candidate.runtimeChunkUuid())
                    || !passageIds.add(candidate.passageId()) || !logicalChunkIds.add(candidate.logicalChunkId())
                    || !runtimeChunkUuids.add(candidate.runtimeChunkUuid())) {
                return false;
            }
        }
        return true;
    }

    private void insertKnowledgeBase(Connection connection, String knowledgeBaseId, ImportRequest request) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO knowledge_base
                (id, name, description, metadata, owner_id, created_at, updated_at)
                VALUES (CAST(? AS uuid), ?, ?, CAST(? AS jsonb), CAST(? AS bigint), CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, knowledgeBaseId);
            statement.setString(2, "mMARCO zh sampled " + request.datasetVersion());
            statement.setString(3, "Isolated mMARCO zh sampled evaluation knowledge base");
            statement.setString(4, knowledgeBaseMetadata(request));
            statement.setString(5, request.ownerId());
            statement.executeUpdate();
        }
    }

    private void verifyKnowledgeBase(Connection connection, String knowledgeBaseId, ImportRequest request) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT metadata->>'candidateManifestSha256'
                FROM knowledge_base
                WHERE id = CAST(? AS uuid)
                """)) {
            statement.setString(1, knowledgeBaseId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !request.candidateManifestSha256().equals(result.getString(1))) {
                    throw new IllegalStateException("mMARCO 隔离 KB 与候选 manifest 不一致");
                }
            }
        }
    }

    private void insertDocument(
            Connection connection,
            String documentId,
            String knowledgeBaseId,
            ImportRequest request,
            MmarcoZhSampledDatasetFreezer.Candidate candidate
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO document
                (id, kb_id, filename, filetype, size, metadata, created_at, updated_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), ?, 'tsv', ?, CAST(? AS jsonb), CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, documentId);
            statement.setString(2, knowledgeBaseId);
            statement.setString(3, "mmarco-zh-" + candidate.passageId() + ".tsv");
            statement.setLong(4, candidate.content().getBytes(StandardCharsets.UTF_8).length);
            statement.setString(5, documentMetadata(request, candidate));
            statement.executeUpdate();
        }
    }

    private void verifyDocument(
            Connection connection,
            String documentId,
            String knowledgeBaseId,
            MmarcoZhSampledDatasetFreezer.Candidate candidate
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT kb_id::text, metadata->>'passageId'
                FROM document
                WHERE id = CAST(? AS uuid)
                """)) {
            statement.setString(1, documentId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !knowledgeBaseId.equals(result.getString(1))
                        || !candidate.passageId().equals(result.getString(2))) {
                    throw new IllegalStateException("mMARCO 隔离 document 与 passage 映射不一致");
                }
            }
        }
    }

    private void insertChunk(
            Connection connection,
            String runtimeChunkUuid,
            String knowledgeBaseId,
            String documentId,
            ImportRequest request,
            ImportedCandidate imported
    ) throws SQLException {
        MmarcoZhSampledDatasetFreezer.Candidate candidate = imported.candidate();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chunk_bge_m3
                (id, kb_id, doc_id, content, metadata, embedding, title_bm25_vector, content_bm25_vector,
                 bm25_index_version, evaluation_namespace, created_at, updated_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, CAST(? AS jsonb), CAST(? AS vector),
                        CAST(? AS bm25_catalog.bm25vector), CAST(? AS bm25_catalog.bm25vector), ?,
                        ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, runtimeChunkUuid);
            statement.setString(2, knowledgeBaseId);
            statement.setString(3, documentId);
            statement.setString(4, candidate.content());
            statement.setString(5, chunkMetadata(request, candidate));
            statement.setString(6, vectorLiteral(imported.embedding()));
            statement.setString(7, imported.titleBm25Vector());
            statement.setString(8, imported.contentBm25Vector());
            statement.setInt(9, imported.bm25IndexVersion());
            statement.setString(10, EVALUATION_NAMESPACE);
            statement.executeUpdate();
        }
    }

    private void verifyChunk(
            Connection connection,
            String runtimeChunkUuid,
            String knowledgeBaseId,
            String documentId,
            ImportRequest request,
            MmarcoZhSampledDatasetFreezer.Candidate candidate
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT kb_id::text, doc_id::text, content, metadata->>'logicalChunkId',
                       metadata->>'candidateManifestSha256'
                FROM chunk_bge_m3
                WHERE id = CAST(? AS uuid)
                """)) {
            statement.setString(1, runtimeChunkUuid);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !knowledgeBaseId.equals(result.getString(1))
                        || !documentId.equals(result.getString(2)) || !candidate.content().equals(result.getString(3))
                        || !candidate.logicalChunkId().equals(result.getString(4))
                        || !request.candidateManifestSha256().equals(result.getString(5))) {
                    throw new IllegalStateException("mMARCO 隔离 chunk 与冻结候选不一致");
                }
            }
        }
    }

    private String knowledgeBaseMetadata(ImportRequest request) {
        return json(Map.of(
                "evaluationNamespace", EVALUATION_NAMESPACE,
                "datasetVersion", request.datasetVersion(),
                "candidateManifestSha256", request.candidateManifestSha256(),
                "mappingVersion", MAPPING_VERSION
        ));
    }

    private String documentMetadata(ImportRequest request, MmarcoZhSampledDatasetFreezer.Candidate candidate) {
        return json(Map.of(
                "sourceName", "mmarco-zh-sampled",
                "sourceType", "tsv",
                "language", "zh",
                "datasetVersion", request.datasetVersion(),
                "passageId", candidate.passageId()
        ));
    }

    private String chunkMetadata(ImportRequest request, MmarcoZhSampledDatasetFreezer.Candidate candidate) {
        String title = "mMARCO zh passage " + candidate.passageId();
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("title", title);
        metadata.put("sourceName", "mmarco-zh-sampled");
        metadata.put("sourceType", "tsv");
        metadata.put("contentPath", "mMARCO > zh > " + candidate.passageId());
        metadata.put("headingLevel", 1);
        metadata.put("sectionType", "PASSAGE");
        metadata.put("pathDepth", 3);
        metadata.put("language", "zh");
        metadata.put("datasetVersion", request.datasetVersion());
        metadata.put("candidateManifestSha256", request.candidateManifestSha256());
        metadata.put("mappingVersion", MAPPING_VERSION);
        metadata.put("passageId", candidate.passageId());
        metadata.put("logicalChunkId", candidate.logicalChunkId());
        metadata.put("candidateSourceType", candidate.sourceType());
        return json(metadata);
    }

    private String vectorLiteral(float[] embedding) {
        StringBuilder literal = new StringBuilder("[");
        for (int index = 0; index < embedding.length; index++) {
            if (index > 0) {
                literal.append(',');
            }
            literal.append(embedding[index]);
        }
        return literal.append(']').toString();
    }

    private String deterministicUuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String json(Map<String, ?> value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("mMARCO metadata JSON 序列化失败", exception);
        }
    }

    record ImportRequest(
            String datasetVersion,
            String candidateManifestSha256,
            String ownerId,
            List<ImportedCandidate> candidates
    ) {
        ImportRequest {
            candidates = candidates == null ? null : List.copyOf(candidates);
        }
    }

    record ImportedCandidate(
            MmarcoZhSampledDatasetFreezer.Candidate candidate,
            float[] embedding,
            String titleBm25Vector,
            String contentBm25Vector,
            int bm25IndexVersion
    ) {
        ImportedCandidate {
            embedding = embedding == null ? null : embedding.clone();
        }
    }

    record ImportResult(
            String knowledgeBaseId,
            Map<String, String> logicalChunkIdByRuntimeUuid,
            Map<String, String> documentIdByPassageId
    ) {
    }
}
