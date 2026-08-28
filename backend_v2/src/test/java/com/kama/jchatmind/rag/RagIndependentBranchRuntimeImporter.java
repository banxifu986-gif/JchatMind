package com.kama.jchatmind.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class RagIndependentBranchRuntimeImporter {

    private static final String DATASET_ID = "g2-pre-bm25-v1";
    private static final String EVALUATION_NAMESPACE = "rag-eval";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    ImportResult importFixture(
            Connection connection,
            String ownerId,
            String fixtureSha256,
            List<EmbeddedCandidate> candidates
    ) throws SQLException {
        if (connection == null || isBlank(ownerId) || isBlank(fixtureSha256) || candidates == null || candidates.isEmpty()) {
            throw new IllegalArgumentException("独立三路运行时 fixture 导入输入不完整");
        }
        validateCandidates(candidates);
        String knowledgeBaseId = deterministicUuid("g2-three-branch:knowledge-base:" + fixtureSha256);
        ImportResult existing = findExisting(connection, knowledgeBaseId, fixtureSha256, candidates);
        if (existing != null) {
            return existing;
        }

        insertKnowledgeBase(connection, knowledgeBaseId, ownerId, fixtureSha256);
        for (EmbeddedCandidate candidate : candidates) {
            insertDocument(connection, knowledgeBaseId, fixtureSha256, candidate.candidate());
            insertChunk(connection, knowledgeBaseId, fixtureSha256, candidate);
        }
        ImportResult imported = findExisting(connection, knowledgeBaseId, fixtureSha256, candidates);
        if (imported == null) {
            throw new IllegalStateException("独立三路运行时 fixture 导入后校验失败");
        }
        return imported;
    }

    static String evaluationNamespace() {
        return EVALUATION_NAMESPACE;
    }

    private ImportResult findExisting(
            Connection connection,
            String knowledgeBaseId,
            String fixtureSha256,
            List<EmbeddedCandidate> candidates
    ) throws SQLException {
        if (!matchesKnowledgeBase(connection, knowledgeBaseId, fixtureSha256)) {
            if (hasKnowledgeBase(connection, knowledgeBaseId) || hasChunks(connection, knowledgeBaseId)) {
                throw new IllegalStateException("独立三路隔离 KB 已存在但 fixture 哈希不一致");
            }
            return null;
        }
        Map<String, EmbeddedCandidate> expectedByRuntimeUuid = new LinkedHashMap<>();
        for (EmbeddedCandidate candidate : candidates) {
            expectedByRuntimeUuid.put(candidate.candidate().runtimeChunkUuid(), candidate);
        }
        Map<String, String> logicalChunkIdByRuntimeUuid = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT chunk.id::text, chunk.doc_id::text, chunk.content,
                       chunk.metadata->>'fixtureSha256', chunk.metadata->>'logicalChunkId',
                       chunk.metadata->>'sourceName', chunk.metadata->>'sourceType',
                       chunk.metadata->>'contentPath', chunk.metadata->>'pageNumber'
                FROM chunk_bge_m3 chunk
                WHERE chunk.kb_id = CAST(? AS uuid)
                ORDER BY chunk.id
                """)) {
            statement.setString(1, knowledgeBaseId);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String runtimeUuid = result.getString(1);
                    EmbeddedCandidate expected = expectedByRuntimeUuid.remove(runtimeUuid);
                    if (expected == null || !matchesChunk(result, expected.candidate(), fixtureSha256)) {
                        return null;
                    }
                    logicalChunkIdByRuntimeUuid.put(runtimeUuid, expected.candidate().logicalChunkId());
                }
            }
        }
        if (!expectedByRuntimeUuid.isEmpty() || logicalChunkIdByRuntimeUuid.size() != candidates.size()) {
            return null;
        }
        return new ImportResult(knowledgeBaseId, Map.copyOf(logicalChunkIdByRuntimeUuid));
    }

    private boolean matchesKnowledgeBase(Connection connection, String knowledgeBaseId, String fixtureSha256) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT metadata->>'evaluationNamespace', metadata->>'datasetId', metadata->>'fixtureSha256'
                FROM knowledge_base
                WHERE id = CAST(? AS uuid)
                """)) {
            statement.setString(1, knowledgeBaseId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        && EVALUATION_NAMESPACE.equals(result.getString(1))
                        && DATASET_ID.equals(result.getString(2))
                        && fixtureSha256.equals(result.getString(3));
            }
        }
    }

    private boolean hasKnowledgeBase(Connection connection, String knowledgeBaseId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM knowledge_base WHERE id = CAST(? AS uuid)")) {
            statement.setString(1, knowledgeBaseId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private boolean hasChunks(Connection connection, String knowledgeBaseId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT 1 FROM chunk_bge_m3 WHERE kb_id = CAST(? AS uuid)")) {
            statement.setString(1, knowledgeBaseId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private boolean matchesChunk(
            ResultSet result,
            RagIndependentBranchRuntimeFixture.Candidate candidate,
            String fixtureSha256
    ) throws SQLException {
        String pageNumber = candidate.pageNumber() == null ? null : candidate.pageNumber().toString();
        return candidate.runtimeDocumentUuid().equals(result.getString(2))
                && candidate.content().equals(result.getString(3))
                && fixtureSha256.equals(result.getString(4))
                && candidate.logicalChunkId().equals(result.getString(5))
                && candidate.sourceName().equals(result.getString(6))
                && candidate.sourceType().equals(result.getString(7))
                && candidate.contentPath().equals(result.getString(8))
                && java.util.Objects.equals(pageNumber, result.getString(9));
    }

    private void insertKnowledgeBase(
            Connection connection,
            String knowledgeBaseId,
            String ownerId,
            String fixtureSha256
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO knowledge_base
                (id, name, description, metadata, owner_id, created_at, updated_at)
                VALUES (CAST(? AS uuid), ?, ?, CAST(? AS jsonb), CAST(? AS bigint), CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, knowledgeBaseId);
            statement.setString(2, "G2 independent branch runtime " + fixtureSha256.substring(0, 12));
            statement.setString(3, "Isolated frozen G2 three-branch retrieval evaluation fixture");
            statement.setString(4, json(Map.of(
                    "evaluationNamespace", EVALUATION_NAMESPACE,
                    "datasetId", DATASET_ID,
                    "fixtureSha256", fixtureSha256
            )));
            statement.setString(5, ownerId);
            statement.executeUpdate();
        }
    }

    private void insertDocument(
            Connection connection,
            String knowledgeBaseId,
            String fixtureSha256,
            RagIndependentBranchRuntimeFixture.Candidate candidate
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO document
                (id, kb_id, filename, filetype, size, metadata, created_at, updated_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, CAST(? AS jsonb), CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, candidate.runtimeDocumentUuid());
            statement.setString(2, knowledgeBaseId);
            statement.setString(3, candidate.sourceName());
            statement.setString(4, candidate.sourceType());
            statement.setLong(5, candidate.content().getBytes(StandardCharsets.UTF_8).length);
            statement.setString(6, json(Map.of(
                    "evaluationNamespace", EVALUATION_NAMESPACE,
                    "datasetId", DATASET_ID,
                    "fixtureSha256", fixtureSha256,
                    "documentId", candidate.documentId(),
                    "sourceName", candidate.sourceName()
            )));
            statement.executeUpdate();
        }
    }

    private void insertChunk(
            Connection connection,
            String knowledgeBaseId,
            String fixtureSha256,
            EmbeddedCandidate embedded
    ) throws SQLException {
        RagIndependentBranchRuntimeFixture.Candidate candidate = embedded.candidate();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO chunk_bge_m3
                (id, kb_id, doc_id, content, metadata, embedding, title_bm25_vector, content_bm25_vector,
                 bm25_index_version, evaluation_namespace, created_at, updated_at)
                VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, CAST(? AS jsonb), CAST(? AS vector),
                        CAST(? AS bm25_catalog.bm25vector), CAST(? AS bm25_catalog.bm25vector), ?,
                        ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, candidate.runtimeChunkUuid());
            statement.setString(2, knowledgeBaseId);
            statement.setString(3, candidate.runtimeDocumentUuid());
            statement.setString(4, candidate.content());
            statement.setString(5, chunkMetadata(candidate, fixtureSha256));
            statement.setString(6, vectorLiteral(embedded.embedding()));
            statement.setString(7, embedded.titleBm25Vector());
            statement.setString(8, embedded.contentBm25Vector());
            statement.setInt(9, embedded.bm25IndexVersion());
            statement.setString(10, EVALUATION_NAMESPACE);
            statement.executeUpdate();
        }
    }

    private String chunkMetadata(RagIndependentBranchRuntimeFixture.Candidate candidate, String fixtureSha256) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("evaluationNamespace", EVALUATION_NAMESPACE);
        metadata.put("datasetId", DATASET_ID);
        metadata.put("fixtureSha256", fixtureSha256);
        metadata.put("logicalChunkId", candidate.logicalChunkId());
        metadata.put("title", candidate.title());
        metadata.put("retrievableTitle", candidate.title());
        metadata.put("sourceName", candidate.sourceName());
        metadata.put("sourceType", candidate.sourceType());
        metadata.put("contentPath", candidate.contentPath());
        metadata.put("parentContentPath", candidate.sourceName());
        metadata.put("headingLevel", 2);
        metadata.put("sectionType", "LEAF_CONTENT");
        metadata.put("pathDepth", 2);
        if (candidate.pageNumber() != null) {
            metadata.put("pageNumber", candidate.pageNumber());
        }
        return json(metadata);
    }

    private void validateCandidates(List<EmbeddedCandidate> candidates) {
        Set<String> chunkUuids = new LinkedHashSet<>();
        Set<String> logicalChunkIds = new LinkedHashSet<>();
        Set<String> documentUuids = new LinkedHashSet<>();
        for (EmbeddedCandidate embedded : candidates) {
            if (embedded == null || embedded.candidate() == null || embedded.embedding() == null
                    || embedded.embedding().length == 0 || isBlank(embedded.titleBm25Vector())
                    || isBlank(embedded.contentBm25Vector()) || embedded.bm25IndexVersion() <= 0) {
                throw new IllegalArgumentException("独立三路运行时候选缺少 embedding 或 BM25 投影");
            }
            RagIndependentBranchRuntimeFixture.Candidate candidate = embedded.candidate();
            if (isBlank(candidate.logicalChunkId()) || isBlank(candidate.runtimeChunkUuid())
                    || isBlank(candidate.runtimeDocumentUuid()) || isBlank(candidate.content())
                    || !chunkUuids.add(candidate.runtimeChunkUuid()) || !logicalChunkIds.add(candidate.logicalChunkId())) {
                throw new IllegalArgumentException("独立三路运行时 chunk 标识无效或重复");
            }
            documentUuids.add(candidate.runtimeDocumentUuid());
        }
    }

    private String json(Map<String, ?> values) {
        try {
            return OBJECT_MAPPER.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("独立三路运行时 fixture metadata 序列化失败", exception);
        }
    }

    private String vectorLiteral(float[] vector) {
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(vector[index]);
        }
        return builder.append(']').toString();
    }

    private String deterministicUuid(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    record EmbeddedCandidate(
            RagIndependentBranchRuntimeFixture.Candidate candidate,
            float[] embedding,
            String titleBm25Vector,
            String contentBm25Vector,
            int bm25IndexVersion
    ) {
    }

    record ImportResult(String knowledgeBaseId, Map<String, String> logicalChunkIdByRuntimeUuid) {
        ImportResult {
            logicalChunkIdByRuntimeUuid = Map.copyOf(logicalChunkIdByRuntimeUuid);
        }
    }
}
