package com.kama.jchatmind.rag;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.service.impl.VchordBm25ProjectionService;
import org.springframework.util.StringUtils;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class G2CurrentMultimodalCandidateDatabaseImporter {

    private static final String EVALUATION_NAMESPACE = "rag-eval";
    private static final String DATASET_ID = "g2-current-multimodal-v1";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private G2CurrentMultimodalCandidateDatabaseImporter() {
    }

    static void importCandidate(
            Connection connection,
            String ownerId,
            G2CurrentMultimodalCandidateRuntimeBuilder.PreparedRuntimePlan runtimePlan
    ) throws SQLException {
        G2CurrentFixtureRuntimeDatabaseImporter.requireIsolatedTransaction(connection);
        validateImport(ownerId, runtimePlan);

        G2CurrentMultimodalCandidateFixtureLoader.AssetImportPlan plan = runtimePlan.assetImportPlan();
        ExistingKnowledgeBase existing = findKnowledgeBase(connection, plan.knowledgeBaseId());
        if (existing.exists()) {
            if (!ownerId.equals(existing.ownerId())
                    || !EVALUATION_NAMESPACE.equals(existing.evaluationNamespace())
                    || !matchesJson(existing.metadata(), plan.knowledgeBaseMetadata())) {
                throw new IllegalStateException("隔离 multimodal candidate KB 已存在但 owner 或 manifest metadata 不一致");
            }
            verifyImportedRows(connection, runtimePlan);
            return;
        }

        ensureIdsUnused(connection, plan);
        insertKnowledgeBase(connection, ownerId, plan);
        insertDocuments(connection, plan);
        insertChunks(connection, runtimePlan);
        insertAssets(connection, plan);
        insertAssetChunkRelations(connection, plan);
        verifyKnowledgeBase(connection, ownerId, plan);
        verifyImportedRows(connection, runtimePlan);
    }

    static void validateImport(
            String ownerId,
            G2CurrentMultimodalCandidateRuntimeBuilder.PreparedRuntimePlan runtimePlan
    ) {
        if (!StringUtils.hasText(ownerId)) {
            throw new IllegalArgumentException("multimodal candidate import 缺少 ownerId");
        }
        try {
            Long.parseLong(ownerId);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("multimodal candidate import ownerId 必须为数字", exception);
        }
        if (runtimePlan == null || !runtimePlan.candidateOnly() || runtimePlan.assetImportPlan() == null
                || runtimePlan.chunks() == null || runtimePlan.chunks().isEmpty()) {
            throw new IllegalArgumentException("multimodal candidate import plan 不完整或未标记 candidate-only");
        }

        G2CurrentMultimodalCandidateFixtureLoader.AssetImportPlan plan = runtimePlan.assetImportPlan();
        Map<String, Object> metadata = plan.knowledgeBaseMetadata();
        Object manifestSha256 = metadata == null ? null : metadata.get("manifestSha256");
        if (!plan.candidateOnly()
                || metadata == null
                || !Boolean.TRUE.equals(metadata.get("candidateOnly"))
                || !EVALUATION_NAMESPACE.equals(metadata.get("evaluationNamespace"))
                || !DATASET_ID.equals(metadata.get("datasetId"))
                || !StringUtils.hasText(plan.knowledgeBaseId())
                || !(manifestSha256 instanceof String hash) || !hash.matches("[0-9a-f]{64}")
                || plan.documentRows() == null || plan.documentRows().isEmpty()
                || plan.chunkRows() == null || plan.chunkRows().size() != runtimePlan.chunks().size()
                || plan.assetRows() == null || plan.assetRows().isEmpty()
                || plan.assetChunkRelations() == null || plan.assetChunkRelations().isEmpty()) {
            throw new IllegalArgumentException("multimodal candidate import metadata 或 row 数量不符合要求");
        }

        Map<String, G2CurrentMultimodalCandidateFixtureLoader.DocumentImportRow> documentsById =
                new LinkedHashMap<>();
        for (G2CurrentMultimodalCandidateFixtureLoader.DocumentImportRow document : plan.documentRows()) {
            if (document == null || !StringUtils.hasText(document.documentId())
                    || !plan.knowledgeBaseId().equals(document.knowledgeBaseId())
                    || !StringUtils.hasText(document.filename())
                    || !StringUtils.hasText(document.filetype())
                    || document.sizeBytes() < 0
                    || documentsById.putIfAbsent(document.documentId(), document) != null) {
                throw new IllegalArgumentException("multimodal candidate document row 无效或重复");
            }
        }

        Map<String, G2CurrentMultimodalCandidateFixtureLoader.ChunkImportRow> chunksById = new LinkedHashMap<>();
        Set<String> logicalChunkIds = new HashSet<>();
        for (int index = 0; index < plan.chunkRows().size(); index++) {
            G2CurrentMultimodalCandidateFixtureLoader.ChunkImportRow chunk = plan.chunkRows().get(index);
            G2CurrentMultimodalCandidateRuntimeBuilder.RuntimeChunk runtimeChunk = runtimePlan.chunks().get(index);
            if (chunk == null || !chunk.equals(runtimeChunk.chunk())
                    || !plan.knowledgeBaseId().equals(chunk.knowledgeBaseId())
                    || !documentsById.containsKey(chunk.documentId())
                    || !StringUtils.hasText(chunk.chunkId())
                    || !StringUtils.hasText(chunk.logicalChunkId())
                    || chunk.content() == null
                    || chunksById.putIfAbsent(chunk.chunkId(), chunk) != null
                    || !logicalChunkIds.add(chunk.logicalChunkId())) {
                throw new IllegalArgumentException("multimodal candidate chunk row 与 runtime vector 映射无效");
            }
            float[] embedding = runtimeChunk.embedding();
            VchordBm25ProjectionService.Projection projection = runtimeChunk.projection();
            if (embedding == null || embedding.length != 1024 || !allFinite(embedding)
                    || projection == null || !StringUtils.hasText(projection.titleVector())
                    || !StringUtils.hasText(projection.contentVector())
                    || projection.indexVersion() == null
                    || projection.indexVersion() != VchordBm25ProjectionService.INDEX_VERSION) {
                throw new IllegalArgumentException("multimodal candidate chunk 缺少有效 embedding 或 BM25 projection");
            }
        }

        Map<String, G2CurrentMultimodalCandidateFixtureLoader.AssetImportRow> assetsById =
                new LinkedHashMap<>();
        for (G2CurrentMultimodalCandidateFixtureLoader.AssetImportRow asset : plan.assetRows()) {
            boolean validTable = "TABLE".equals(asset == null ? null : asset.assetType())
                    && asset.pageNumber() == null
                    && "markdown-table-v1".equals(asset.parserVersion());
            boolean validPdfPage = "PDF_PAGE_TEXT".equals(asset == null ? null : asset.assetType())
                    && asset.pageNumber() != null && asset.pageNumber() > 0
                    && "pdf-text-v1".equals(asset.parserVersion());
            if (asset == null || !StringUtils.hasText(asset.assetId())
                    || !documentsById.containsKey(asset.documentId())
                    || !(validTable || validPdfPage)
                    || !StringUtils.hasText(asset.assetKey())
                    || !StringUtils.hasText(asset.contentHash())
                    || !asset.contentHash().matches("[0-9a-f]{64}")
                    || !StringUtils.hasText(asset.parserVersion())
                    || !"READY".equals(asset.status())
                    || !isJsonObject(asset.locatorJson())
                    || assetsById.putIfAbsent(asset.assetId(), asset) != null) {
                throw new IllegalArgumentException("multimodal candidate asset row 无效或重复");
            }
        }

        Set<RelationKey> relations = new HashSet<>();
        Set<String> relatedAssetIds = new HashSet<>();
        for (G2CurrentMultimodalCandidateFixtureLoader.AssetChunkRelation relation : plan.assetChunkRelations()) {
            G2CurrentMultimodalCandidateFixtureLoader.AssetImportRow asset =
                    relation == null ? null : assetsById.get(relation.assetId());
            G2CurrentMultimodalCandidateFixtureLoader.ChunkImportRow chunk =
                    relation == null ? null : chunksById.get(relation.chunkId());
            if (asset == null || chunk == null
                    || !asset.documentId().equals(relation.assetDocumentId())
                    || !chunk.documentId().equals(relation.chunkDocumentId())
                    || !relation.assetDocumentId().equals(relation.chunkDocumentId())
                    || !relations.add(new RelationKey(
                            relation.assetId(), relation.chunkId(),
                            relation.assetDocumentId(), relation.chunkDocumentId()
                    ))) {
                throw new IllegalArgumentException("multimodal candidate asset/chunk relation 无效或重复");
            }
            relatedAssetIds.add(relation.assetId());
        }
        if (!relatedAssetIds.equals(assetsById.keySet())) {
            throw new IllegalArgumentException("multimodal candidate 存在未关联 chunk 的资产");
        }
    }

    private static ExistingKnowledgeBase findKnowledgeBase(Connection connection, String knowledgeBaseId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT owner_id::text, metadata::text, evaluation_namespace
                FROM knowledge_base
                WHERE id = CAST(? AS uuid)
                """)) {
            statement.setString(1, knowledgeBaseId);
            try (ResultSet result = statement.executeQuery()) {
                return result.next()
                        ? new ExistingKnowledgeBase(true, result.getString(1), result.getString(2), result.getString(3))
                        : new ExistingKnowledgeBase(false, null, null, null);
            }
        }
    }

    private static void ensureIdsUnused(
            Connection connection,
            G2CurrentMultimodalCandidateFixtureLoader.AssetImportPlan plan
    ) throws SQLException {
        for (G2CurrentMultimodalCandidateFixtureLoader.DocumentImportRow document : plan.documentRows()) {
            ensureIdUnused(connection, "document", "id", document.documentId());
        }
        for (G2CurrentMultimodalCandidateFixtureLoader.ChunkImportRow chunk : plan.chunkRows()) {
            ensureIdUnused(connection, "chunk_bge_m3", "id", chunk.chunkId());
        }
        for (G2CurrentMultimodalCandidateFixtureLoader.AssetImportRow asset : plan.assetRows()) {
            ensureIdUnused(connection, "document_asset", "asset_id", asset.assetId());
        }
    }

    private static void ensureIdUnused(Connection connection, String table, String column, String id)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM " + table + " WHERE " + column + " = CAST(? AS uuid)")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    throw new IllegalStateException("multimodal candidate runtime UUID 已被其他数据占用: " + id);
                }
            }
        }
    }

    private static void insertKnowledgeBase(
            Connection connection,
            String ownerId,
            G2CurrentMultimodalCandidateFixtureLoader.AssetImportPlan plan
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO knowledge_base
                (id, name, description, metadata, owner_id, evaluation_namespace, created_at, updated_at)
                VALUES (CAST(? AS uuid), ?, ?, CAST(? AS jsonb), CAST(? AS bigint), ?,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, plan.knowledgeBaseId());
            statement.setString(2, DATASET_ID + " candidate");
            statement.setString(3, "Isolated multimodal candidate fixture");
            statement.setString(4, toJson(plan.knowledgeBaseMetadata()));
            statement.setString(5, ownerId);
            statement.setString(6, EVALUATION_NAMESPACE);
            statement.executeUpdate();
        }
    }

    private static void insertDocuments(
            Connection connection,
            G2CurrentMultimodalCandidateFixtureLoader.AssetImportPlan plan
    ) throws SQLException {
        for (G2CurrentMultimodalCandidateFixtureLoader.DocumentImportRow document : plan.documentRows()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO document
                    (id, kb_id, filename, filetype, size, metadata, evaluation_namespace, created_at, updated_at)
                    VALUES (CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, CAST(? AS jsonb), ?,
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    ON CONFLICT (id) DO NOTHING
                    """)) {
                statement.setString(1, document.documentId());
                statement.setString(2, document.knowledgeBaseId());
                statement.setString(3, document.filename());
                statement.setString(4, document.filetype());
                statement.setLong(5, document.sizeBytes());
                statement.setString(6, toJson(document.metadata()));
                statement.setString(7, EVALUATION_NAMESPACE);
                statement.executeUpdate();
            }
        }
    }

    private static void insertChunks(
            Connection connection,
            G2CurrentMultimodalCandidateRuntimeBuilder.PreparedRuntimePlan runtimePlan
    ) throws SQLException {
        G2CurrentMultimodalCandidateFixtureLoader.AssetImportPlan plan = runtimePlan.assetImportPlan();
        for (G2CurrentMultimodalCandidateRuntimeBuilder.RuntimeChunk runtimeChunk : runtimePlan.chunks()) {
            G2CurrentMultimodalCandidateFixtureLoader.ChunkImportRow chunk = runtimeChunk.chunk();
            VchordBm25ProjectionService.Projection projection = runtimeChunk.projection();
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO chunk_bge_m3
                    (id, kb_id, doc_id, content, metadata, embedding, title_bm25_vector, content_bm25_vector,
                     bm25_index_version, evaluation_namespace, created_at, updated_at)
                    VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, CAST(? AS jsonb), CAST(? AS vector),
                            CAST(? AS bm25_catalog.bm25vector), CAST(? AS bm25_catalog.bm25vector), ?,
                            ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    ON CONFLICT (id) DO NOTHING
                    """)) {
                statement.setString(1, chunk.chunkId());
                statement.setString(2, chunk.knowledgeBaseId());
                statement.setString(3, chunk.documentId());
                statement.setString(4, chunk.content());
                statement.setString(5, toJson(chunk.metadata()));
                statement.setString(6, vectorLiteral(runtimeChunk.embedding()));
                statement.setString(7, projection.titleVector());
                statement.setString(8, projection.contentVector());
                statement.setInt(9, projection.indexVersion());
                statement.setString(10, EVALUATION_NAMESPACE);
                statement.executeUpdate();
            }
        }
    }

    private static void insertAssets(
            Connection connection,
            G2CurrentMultimodalCandidateFixtureLoader.AssetImportPlan plan
    ) throws SQLException {
        for (G2CurrentMultimodalCandidateFixtureLoader.AssetImportRow asset : plan.assetRows()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO document_asset
                    (asset_id, document_id, asset_type, asset_key, page_number, locator, content_hash,
                     parser_version, status, created_at, updated_at)
                    VALUES (CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, CAST(? AS jsonb), ?, ?, ?,
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    ON CONFLICT (asset_id) DO NOTHING
                    """)) {
                statement.setString(1, asset.assetId());
                statement.setString(2, asset.documentId());
                statement.setString(3, asset.assetType());
                statement.setString(4, asset.assetKey());
                if (asset.pageNumber() == null) {
                    statement.setNull(5, Types.INTEGER);
                } else {
                    statement.setInt(5, asset.pageNumber());
                }
                statement.setString(6, asset.locatorJson());
                statement.setString(7, asset.contentHash());
                statement.setString(8, asset.parserVersion());
                statement.setString(9, asset.status());
                statement.executeUpdate();
            }
        }
    }

    private static void insertAssetChunkRelations(
            Connection connection,
            G2CurrentMultimodalCandidateFixtureLoader.AssetImportPlan plan
    ) throws SQLException {
        for (G2CurrentMultimodalCandidateFixtureLoader.AssetChunkRelation relation : plan.assetChunkRelations()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO document_asset_chunk
                    (asset_id, chunk_id, asset_document_id, chunk_document_id)
                    VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid))
                    ON CONFLICT (asset_id, chunk_id) DO NOTHING
                    """)) {
                statement.setString(1, relation.assetId());
                statement.setString(2, relation.chunkId());
                statement.setString(3, relation.assetDocumentId());
                statement.setString(4, relation.chunkDocumentId());
                statement.executeUpdate();
            }
        }
    }

    private static void verifyKnowledgeBase(
            Connection connection,
            String ownerId,
            G2CurrentMultimodalCandidateFixtureLoader.AssetImportPlan plan
    ) throws SQLException {
        ExistingKnowledgeBase imported = findKnowledgeBase(connection, plan.knowledgeBaseId());
        if (!imported.exists() || !ownerId.equals(imported.ownerId())
                || !EVALUATION_NAMESPACE.equals(imported.evaluationNamespace())
                || !matchesJson(imported.metadata(), plan.knowledgeBaseMetadata())) {
            throw new IllegalStateException("multimodal candidate runtime KB 导入后校验失败");
        }
    }

    private static void verifyImportedRows(
            Connection connection,
            G2CurrentMultimodalCandidateRuntimeBuilder.PreparedRuntimePlan runtimePlan
    ) throws SQLException {
        verifyDocuments(connection, runtimePlan.assetImportPlan());
        verifyChunks(connection, runtimePlan);
        verifyAssets(connection, runtimePlan.assetImportPlan());
        verifyRelations(connection, runtimePlan.assetImportPlan());
    }

    private static void verifyDocuments(
            Connection connection,
            G2CurrentMultimodalCandidateFixtureLoader.AssetImportPlan plan
    ) throws SQLException {
        Map<String, G2CurrentMultimodalCandidateFixtureLoader.DocumentImportRow> expected = new HashMap<>();
        for (G2CurrentMultimodalCandidateFixtureLoader.DocumentImportRow row : plan.documentRows()) {
            expected.put(row.documentId(), row);
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id::text, kb_id::text, filename, filetype, size, metadata::text, evaluation_namespace
                FROM document
                WHERE kb_id = CAST(? AS uuid)
                """)) {
            statement.setString(1, plan.knowledgeBaseId());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String id = result.getString(1);
                    G2CurrentMultimodalCandidateFixtureLoader.DocumentImportRow row = expected.remove(id);
                    if (row == null
                            || !plan.knowledgeBaseId().equals(result.getString(2))
                            || !row.filename().equals(result.getString(3))
                            || !row.filetype().equals(result.getString(4))
                            || row.sizeBytes() != result.getLong(5)
                            || !matchesJson(result.getString(6), row.metadata())
                            || !EVALUATION_NAMESPACE.equals(result.getString(7))) {
                        throw new IllegalStateException("multimodal candidate document 导入后校验失败: " + id);
                    }
                }
            }
        }
        if (!expected.isEmpty()) {
            throw new IllegalStateException("multimodal candidate document 导入后存在缺失项: " + expected.size());
        }
    }

    private static void verifyChunks(
            Connection connection,
            G2CurrentMultimodalCandidateRuntimeBuilder.PreparedRuntimePlan runtimePlan
    ) throws SQLException {
        G2CurrentMultimodalCandidateFixtureLoader.AssetImportPlan plan = runtimePlan.assetImportPlan();
        Map<String, G2CurrentMultimodalCandidateRuntimeBuilder.RuntimeChunk> expected = new HashMap<>();
        for (G2CurrentMultimodalCandidateRuntimeBuilder.RuntimeChunk chunk : runtimePlan.chunks()) {
            expected.put(chunk.chunk().chunkId(), chunk);
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT id::text, doc_id::text, content, metadata::text, evaluation_namespace
                FROM chunk_bge_m3
                WHERE kb_id = CAST(? AS uuid)
                """)) {
            statement.setString(1, plan.knowledgeBaseId());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String id = result.getString(1);
                    G2CurrentMultimodalCandidateRuntimeBuilder.RuntimeChunk runtimeChunk = expected.remove(id);
                    if (runtimeChunk == null
                            || !runtimeChunk.chunk().documentId().equals(result.getString(2))
                            || !runtimeChunk.chunk().content().equals(result.getString(3))
                            || !matchesJson(result.getString(4), runtimeChunk.chunk().metadata())
                            || !EVALUATION_NAMESPACE.equals(result.getString(5))) {
                        throw new IllegalStateException("multimodal candidate chunk 导入后校验失败: " + id);
                    }
                }
            }
        }
        if (!expected.isEmpty()) {
            throw new IllegalStateException("multimodal candidate chunk 导入后存在缺失项: " + expected.size());
        }
    }

    private static void verifyAssets(
            Connection connection,
            G2CurrentMultimodalCandidateFixtureLoader.AssetImportPlan plan
    ) throws SQLException {
        Map<String, G2CurrentMultimodalCandidateFixtureLoader.AssetImportRow> expected = new HashMap<>();
        for (G2CurrentMultimodalCandidateFixtureLoader.AssetImportRow row : plan.assetRows()) {
            expected.put(row.assetId(), row);
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT asset.asset_id::text, asset.document_id::text, asset.asset_type, asset.asset_key,
                       asset.page_number, asset.locator::text, asset.content_hash, asset.parser_version, asset.status
                FROM document_asset asset
                JOIN document ON document.id = asset.document_id
                WHERE document.kb_id = CAST(? AS uuid)
                """)) {
            statement.setString(1, plan.knowledgeBaseId());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String id = result.getString(1);
                    G2CurrentMultimodalCandidateFixtureLoader.AssetImportRow row = expected.remove(id);
                    Integer pageNumber = result.getObject(5) == null ? null : result.getInt(5);
                    if (row == null
                            || !row.documentId().equals(result.getString(2))
                            || !row.assetType().equals(result.getString(3))
                            || !row.assetKey().equals(result.getString(4))
                            || !java.util.Objects.equals(row.pageNumber(), pageNumber)
                            || !jsonEquals(result.getString(6), row.locatorJson())
                            || !row.contentHash().equals(result.getString(7))
                            || !row.parserVersion().equals(result.getString(8))
                            || !row.status().equals(result.getString(9))) {
                        throw new IllegalStateException("multimodal candidate asset 导入后校验失败: " + id);
                    }
                }
            }
        }
        if (!expected.isEmpty()) {
            throw new IllegalStateException("multimodal candidate asset 导入后存在缺失项: " + expected.size());
        }
    }

    private static void verifyRelations(
            Connection connection,
            G2CurrentMultimodalCandidateFixtureLoader.AssetImportPlan plan
    ) throws SQLException {
        Set<RelationKey> expected = new HashSet<>();
        for (G2CurrentMultimodalCandidateFixtureLoader.AssetChunkRelation relation : plan.assetChunkRelations()) {
            expected.add(new RelationKey(
                    relation.assetId(), relation.chunkId(),
                    relation.assetDocumentId(), relation.chunkDocumentId()
            ));
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT relation.asset_id::text, relation.chunk_id::text,
                       relation.asset_document_id::text, relation.chunk_document_id::text
                FROM document_asset_chunk relation
                JOIN document ON document.id = relation.asset_document_id
                WHERE document.kb_id = CAST(? AS uuid)
                """)) {
            statement.setString(1, plan.knowledgeBaseId());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    RelationKey key = new RelationKey(
                            result.getString(1), result.getString(2), result.getString(3), result.getString(4)
                    );
                    if (!expected.remove(key)) {
                        throw new IllegalStateException("multimodal candidate asset/chunk relation 导入后出现未知项");
                    }
                }
            }
        }
        if (!expected.isEmpty()) {
            throw new IllegalStateException("multimodal candidate asset/chunk relation 导入后存在缺失项");
        }
    }

    private static boolean allFinite(float[] values) {
        for (float value : values) {
            if (!Float.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isJsonObject(String json) {
        try {
            return OBJECT_MAPPER.readTree(json).isObject();
        } catch (Exception exception) {
            return false;
        }
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

    private static boolean jsonEquals(String left, String right) {
        try {
            return OBJECT_MAPPER.readTree(left).equals(OBJECT_MAPPER.readTree(right));
        } catch (Exception exception) {
            return false;
        }
    }

    private static String toJson(Map<String, ?> values) {
        try {
            return OBJECT_MAPPER.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("multimodal candidate metadata 序列化失败", exception);
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

    private record ExistingKnowledgeBase(
            boolean exists,
            String ownerId,
            String metadata,
            String evaluationNamespace
    ) {
    }

    private record RelationKey(
            String assetId,
            String chunkId,
            String assetDocumentId,
            String chunkDocumentId
    ) {
    }
}
