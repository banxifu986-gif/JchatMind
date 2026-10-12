package com.kama.jchatmind.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.service.impl.VchordBm25ProjectionService;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class G2CurrentFixtureRuntimeDatabaseImporter {

    private static final String ISOLATED_JDBC_URL = "jdbc:postgresql://127.0.0.1:55432/jchatmind_rag_eval";
    private static final String ISOLATED_CATALOG = "jchatmind_rag_eval";
    private static final String EVALUATION_NAMESPACE = "rag-eval";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private G2CurrentFixtureRuntimeDatabaseImporter() {
    }

    static void requireIsolatedTransaction(Connection connection) throws SQLException {
        if (connection == null) {
            throw new IllegalArgumentException("runtime fixture import 缺少隔离数据库连接");
        }
        DatabaseMetaData metadata = connection.getMetaData();
        if (metadata == null || !ISOLATED_JDBC_URL.equals(metadata.getURL())
                || !ISOLATED_CATALOG.equals(connection.getCatalog())) {
            throw new IllegalStateException("runtime fixture import 仅允许 jdbc:postgresql://127.0.0.1:55432/jchatmind_rag_eval");
        }
        if (connection.getAutoCommit()) {
            throw new IllegalStateException("runtime fixture import 禁止 autocommit，必须使用显式事务");
        }
    }

    static G2CurrentFixtureRuntimeImportPlan.Prepared importFixture(
            Connection connection,
            String ownerId,
            G2CurrentFixtureRuntimeImportPlan.Prepared plan,
            List<G2CurrentFixtureRuntimeCandidateBuilder.RuntimeCandidate> candidates
    ) throws SQLException {
        requireIsolatedTransaction(connection);
        validateCandidates(plan, candidates);
        if (!StringUtils.hasText(ownerId)) {
            throw new IllegalArgumentException("runtime fixture import 缺少 ownerId");
        }
        try {
            Long.parseLong(ownerId);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("runtime fixture import ownerId 必须为数字", exception);
        }

        ExistingKnowledgeBase existing = findKnowledgeBase(connection, plan.knowledgeBaseId());
        if (existing.exists()) {
            if (!ownerId.equals(existing.ownerId())
                    || !matchesJson(existing.metadata(), plan.knowledgeBaseMetadata())) {
                throw new IllegalStateException("隔离 runtime KB 已存在但 owner 或 fixture metadata 不一致");
            }
            verifyDocuments(connection, plan, candidates);
            verifyChunks(connection, plan, candidates);
            return plan;
        }

        if (hasChunks(connection, plan.knowledgeBaseId())) {
            throw new IllegalStateException("隔离 runtime KB 缺失但已有 chunk，拒绝接管未知状态");
        }
        ensureDocumentIdsUnused(connection, candidates);
        insertKnowledgeBase(connection, ownerId, plan);
        insertDocuments(connection, plan, candidates);
        insertChunks(connection, plan, candidates);

        ExistingKnowledgeBase imported = findKnowledgeBase(connection, plan.knowledgeBaseId());
        if (!imported.exists() || !ownerId.equals(imported.ownerId())
                || !matchesJson(imported.metadata(), plan.knowledgeBaseMetadata())) {
            throw new IllegalStateException("隔离 runtime KB 导入后校验失败");
        }
        verifyDocuments(connection, plan, candidates);
        verifyChunks(connection, plan, candidates);
        return plan;
    }

    static void validateCandidates(
            G2CurrentFixtureRuntimeImportPlan.Prepared plan,
            List<G2CurrentFixtureRuntimeCandidateBuilder.RuntimeCandidate> candidates
    ) {
        if (plan == null || !"fixture-fast-v1".equals(plan.datasetId())
                || !StringUtils.hasText(plan.fixtureSha256())
                || !StringUtils.hasText(plan.knowledgeBaseId())
                || plan.logicalChunkIdByRuntimeUuid() == null
                || plan.chunkMetadataByLogicalChunkId() == null
                || candidates == null || candidates.isEmpty()
                || candidates.size() != plan.logicalChunkIdByRuntimeUuid().size()
                || candidates.size() != plan.chunkMetadataByLogicalChunkId().size()) {
            throw new IllegalArgumentException("runtime import plan 与候选数量不一致");
        }

        Set<String> chunkUuids = new HashSet<>();
        Set<String> logicalChunkIds = new HashSet<>();
        Map<String, String> documentUuidBySource = new HashMap<>();
        Map<String, String> sourceByDocumentUuid = new HashMap<>();
        for (G2CurrentFixtureRuntimeCandidateBuilder.RuntimeCandidate candidate : candidates) {
            if (candidate == null || candidate.chunk() == null || candidate.embedding() == null
                    || candidate.embedding().length != 1024 || candidate.projection() == null
                    || !StringUtils.hasText(candidate.projection().titleVector())
                    || !StringUtils.hasText(candidate.projection().contentVector())
                    || candidate.projection().indexVersion() == null
                    || candidate.projection().indexVersion() != VchordBm25ProjectionService.INDEX_VERSION) {
                throw new IllegalArgumentException("runtime import 候选缺少有效 embedding 或 BM25 projection");
            }
            G2CurrentFixtureRuntimeCorpus.Chunk chunk = candidate.chunk();
            if (!StringUtils.hasText(chunk.sourceDocumentId())) {
                throw new IllegalArgumentException("runtime import candidate 缺少 sourceDocumentId");
            }
            if (!StringUtils.hasText(chunk.runtimeDocumentUuid())) {
                throw new IllegalArgumentException("runtime import candidate 缺少 runtimeDocumentUuid: "
                        + chunk.logicalChunkId());
            }
            if (!StringUtils.hasText(chunk.runtimeChunkUuid()) || !StringUtils.hasText(chunk.logicalChunkId())) {
                throw new IllegalArgumentException("runtime import candidate 缺少 chunk identity: "
                        + chunk.logicalChunkId());
            }
            if (chunk.content() == null) {
                throw new IllegalArgumentException("runtime import candidate 的 section content 缺失: "
                        + chunk.logicalChunkId());
            }
            if (!chunk.logicalChunkId().equals(
                    plan.logicalChunkIdByRuntimeUuid().get(chunk.runtimeChunkUuid()))) {
                throw new IllegalArgumentException("runtime UUID 未映射到对应的 logical chunk: "
                        + chunk.logicalChunkId());
            }
            if (!plan.chunkMetadataByLogicalChunkId().containsKey(chunk.logicalChunkId())) {
                throw new IllegalArgumentException("runtime import plan 缺少 chunk metadata: "
                        + chunk.logicalChunkId());
            }
            if (!chunkUuids.add(chunk.runtimeChunkUuid())) {
                throw new IllegalArgumentException("runtimeChunkUuid 重复: " + chunk.runtimeChunkUuid());
            }
            if (!logicalChunkIds.add(chunk.logicalChunkId())) {
                throw new IllegalArgumentException("logicalChunkId 重复: " + chunk.logicalChunkId());
            }
            String existingDocumentUuid = documentUuidBySource.putIfAbsent(
                    chunk.sourceDocumentId(), chunk.runtimeDocumentUuid()
            );
            String existingSource = sourceByDocumentUuid.putIfAbsent(
                    chunk.runtimeDocumentUuid(), chunk.sourceDocumentId()
            );
            if ((existingDocumentUuid != null && !existingDocumentUuid.equals(chunk.runtimeDocumentUuid()))
                    || (existingSource != null && !existingSource.equals(chunk.sourceDocumentId()))) {
                throw new IllegalArgumentException("runtime document UUID 与来源文档映射不一致");
            }
        }
        if (!logicalChunkIds.equals(plan.chunkMetadataByLogicalChunkId().keySet())) {
            throw new IllegalArgumentException("runtime import 候选未覆盖全部 chunk metadata");
        }
    }

    private static ExistingKnowledgeBase findKnowledgeBase(Connection connection, String knowledgeBaseId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT owner_id::text, metadata::text
                FROM knowledge_base
                WHERE id = CAST(? AS uuid)
                """)) {
            statement.setString(1, knowledgeBaseId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? new ExistingKnowledgeBase(true, result.getString(1), result.getString(2))
                        : new ExistingKnowledgeBase(false, null, null);
            }
        }
    }

    private static boolean hasChunks(Connection connection, String knowledgeBaseId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1
                FROM chunk_bge_m3
                WHERE kb_id = CAST(? AS uuid)
                LIMIT 1
                """)) {
            statement.setString(1, knowledgeBaseId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static void ensureDocumentIdsUnused(
            Connection connection,
            List<G2CurrentFixtureRuntimeCandidateBuilder.RuntimeCandidate> candidates
    ) throws SQLException {
        Set<String> documentIds = new HashSet<>();
        for (G2CurrentFixtureRuntimeCandidateBuilder.RuntimeCandidate candidate : candidates) {
            String documentId = candidate.chunk().runtimeDocumentUuid();
            if (!documentIds.add(documentId)) {
                continue;
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT 1
                    FROM document
                    WHERE id = CAST(? AS uuid)
                    """)) {
                statement.setString(1, documentId);
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        throw new IllegalStateException("runtime document UUID 已被其他 fixture 占用: " + documentId);
                    }
                }
            }
        }
    }

    private static void insertKnowledgeBase(
            Connection connection,
            String ownerId,
            G2CurrentFixtureRuntimeImportPlan.Prepared plan
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO knowledge_base
                (id, name, description, metadata, owner_id, created_at, updated_at)
                VALUES (CAST(? AS uuid), ?, ?, CAST(? AS jsonb), CAST(? AS bigint), CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, plan.knowledgeBaseId());
            statement.setString(2, "G2 current fixture runtime " + plan.fixtureSha256().substring(0, 12));
            statement.setString(3, "Isolated current fixture for fixed-chain and Router runtime comparison");
            statement.setString(4, toJson(plan.knowledgeBaseMetadata()));
            statement.setString(5, ownerId);
            statement.executeUpdate();
        }
    }

    private static void insertDocuments(
            Connection connection,
            G2CurrentFixtureRuntimeImportPlan.Prepared plan,
            List<G2CurrentFixtureRuntimeCandidateBuilder.RuntimeCandidate> candidates
    ) throws SQLException {
        Map<String, G2CurrentFixtureRuntimeCandidateBuilder.RuntimeCandidate> byDocumentId =
                firstCandidateByDocumentId(candidates);
        for (Map.Entry<String, G2CurrentFixtureRuntimeCandidateBuilder.RuntimeCandidate> entry
                : byDocumentId.entrySet()) {
            G2CurrentFixtureRuntimeCorpus.Chunk chunk = entry.getValue().chunk();
            Map<String, Object> metadata = documentMetadata(plan, chunk);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO document
                    (id, kb_id, filename, filetype, size, metadata, created_at, updated_at)
                    VALUES (CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, CAST(? AS jsonb), CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    ON CONFLICT (id) DO NOTHING
                    """)) {
                statement.setString(1, entry.getKey());
                statement.setString(2, plan.knowledgeBaseId());
                statement.setString(3, chunk.sourceName());
                statement.setString(4, "md");
                statement.setLong(5, chunk.documentSizeBytes());
                statement.setString(6, toJson(metadata));
                statement.executeUpdate();
            }
        }
    }

    private static void insertChunks(
            Connection connection,
            G2CurrentFixtureRuntimeImportPlan.Prepared plan,
            List<G2CurrentFixtureRuntimeCandidateBuilder.RuntimeCandidate> candidates
    ) throws SQLException {
        for (G2CurrentFixtureRuntimeCandidateBuilder.RuntimeCandidate candidate : candidates) {
            G2CurrentFixtureRuntimeCorpus.Chunk chunk = candidate.chunk();
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO chunk_bge_m3
                    (id, kb_id, doc_id, content, metadata, embedding, title_bm25_vector, content_bm25_vector,
                     bm25_index_version, evaluation_namespace, created_at, updated_at)
                    VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, CAST(? AS jsonb), CAST(? AS vector),
                            CAST(? AS bm25_catalog.bm25vector), CAST(? AS bm25_catalog.bm25vector), ?,
                            ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    ON CONFLICT (id) DO NOTHING
                    """)) {
                statement.setString(1, chunk.runtimeChunkUuid());
                statement.setString(2, plan.knowledgeBaseId());
                statement.setString(3, chunk.runtimeDocumentUuid());
                statement.setString(4, chunk.content());
                statement.setString(5, toJson(plan.chunkMetadataByLogicalChunkId().get(chunk.logicalChunkId())));
                statement.setString(6, vectorLiteral(candidate.embedding()));
                statement.setString(7, candidate.projection().titleVector());
                statement.setString(8, candidate.projection().contentVector());
                statement.setInt(9, candidate.projection().indexVersion());
                statement.setString(10, EVALUATION_NAMESPACE);
                statement.executeUpdate();
            }
        }
    }

    private static Map<String, Object> documentMetadata(
            G2CurrentFixtureRuntimeImportPlan.Prepared plan,
            G2CurrentFixtureRuntimeCorpus.Chunk chunk
    ) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("evaluationNamespace", EVALUATION_NAMESPACE);
        metadata.put("datasetId", plan.datasetId());
        metadata.put("fixtureSha256", plan.fixtureSha256());
        metadata.put("sourceDocumentId", chunk.sourceDocumentId());
        metadata.put("sourceName", chunk.sourceName());
        return metadata;
    }

    private static void verifyDocuments(
            Connection connection,
            G2CurrentFixtureRuntimeImportPlan.Prepared plan,
            List<G2CurrentFixtureRuntimeCandidateBuilder.RuntimeCandidate> candidates
    ) throws SQLException {
        for (Map.Entry<String, G2CurrentFixtureRuntimeCandidateBuilder.RuntimeCandidate> entry
                : firstCandidateByDocumentId(candidates).entrySet()) {
            G2CurrentFixtureRuntimeCorpus.Chunk chunk = entry.getValue().chunk();
            try (PreparedStatement statement = connection.prepareStatement("""
                    SELECT kb_id::text, filename, filetype, metadata::text
                    FROM document
                    WHERE id = CAST(? AS uuid)
                    """)) {
                statement.setString(1, entry.getKey());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()
                            || !plan.knowledgeBaseId().equals(result.getString(1))
                            || !chunk.sourceName().equals(result.getString(2))
                            || !"md".equals(result.getString(3))
                            || !matchesJson(result.getString(4), documentMetadata(plan, chunk))) {
                        throw new IllegalStateException("runtime document 导入后校验失败: " + entry.getKey());
                    }
                }
            }
        }
    }

    private static void verifyChunks(
            Connection connection,
            G2CurrentFixtureRuntimeImportPlan.Prepared plan,
            List<G2CurrentFixtureRuntimeCandidateBuilder.RuntimeCandidate> candidates
    ) throws SQLException {
        Map<String, G2CurrentFixtureRuntimeCandidateBuilder.RuntimeCandidate> expectedByChunkId = new HashMap<>();
        for (G2CurrentFixtureRuntimeCandidateBuilder.RuntimeCandidate candidate : candidates) {
            expectedByChunkId.put(candidate.chunk().runtimeChunkUuid(), candidate);
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id::text, doc_id::text, content, metadata::text
                FROM chunk_bge_m3
                WHERE kb_id = CAST(? AS uuid)
                ORDER BY id
                """)) {
            statement.setString(1, plan.knowledgeBaseId());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String runtimeChunkUuid = result.getString(1);
                    G2CurrentFixtureRuntimeCandidateBuilder.RuntimeCandidate candidate =
                            expectedByChunkId.remove(runtimeChunkUuid);
                    if (candidate == null
                            || !candidate.chunk().runtimeDocumentUuid().equals(result.getString(2))
                            || !candidate.chunk().content().equals(result.getString(3))
                            || !matchesJson(result.getString(4), plan.chunkMetadataByLogicalChunkId()
                            .get(candidate.chunk().logicalChunkId()))) {
                        throw new IllegalStateException("runtime chunk 导入后校验失败: " + runtimeChunkUuid);
                    }
                }
            }
        }
        if (!expectedByChunkId.isEmpty()) {
            throw new IllegalStateException("runtime chunk 导入后存在缺失项: " + expectedByChunkId.size());
        }
    }

    private static Map<String, G2CurrentFixtureRuntimeCandidateBuilder.RuntimeCandidate> firstCandidateByDocumentId(
            List<G2CurrentFixtureRuntimeCandidateBuilder.RuntimeCandidate> candidates
    ) {
        Map<String, G2CurrentFixtureRuntimeCandidateBuilder.RuntimeCandidate> result = new LinkedHashMap<>();
        for (G2CurrentFixtureRuntimeCandidateBuilder.RuntimeCandidate candidate : candidates) {
            result.putIfAbsent(candidate.chunk().runtimeDocumentUuid(), candidate);
        }
        return result;
    }

    private static boolean matchesJson(String actualJson, Map<String, ?> expected) {
        if (actualJson == null) {
            return false;
        }
        try {
            JsonNode actual = OBJECT_MAPPER.readTree(actualJson);
            return actual.equals(OBJECT_MAPPER.valueToTree(expected));
        } catch (JsonProcessingException exception) {
            return false;
        }
    }

    private static String toJson(Map<String, ?> values) {
        try {
            return OBJECT_MAPPER.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("runtime fixture metadata 序列化失败", exception);
        }
    }

    private static String vectorLiteral(float[] vector) {
        StringBuilder builder = new StringBuilder("[");
        for (int index = 0; index < vector.length; index++) {
            if (index > 0) {
                builder.append(',');
            }
            builder.append(vector[index]);
        }
        return builder.append(']').toString();
    }

    private record ExistingKnowledgeBase(boolean exists, String ownerId, String metadata) {
    }
}
