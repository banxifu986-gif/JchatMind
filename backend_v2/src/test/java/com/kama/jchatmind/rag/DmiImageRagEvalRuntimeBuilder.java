package com.kama.jchatmind.rag;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * dmi-image-rag-v1 隔离评测 runtime：导入计划构建（KB/document/chunk/IMAGE 资产）
 * 与 fail-closed 隔离库导入（仅允许 127.0.0.1:55432/jchatmind_rag_eval 显式事务）。
 */
final class DmiImageRagEvalRuntimeBuilder {

    static final String DATASET_ID = DmiImageRagCandidateFixtureLoader.DATASET_ID;
    static final String ISOLATED_JDBC_URL = "jdbc:postgresql://127.0.0.1:55432/jchatmind_rag_eval";
    static final String ISOLATED_CATALOG = "jchatmind_rag_eval";
    private static final String EVALUATION_NAMESPACE = "rag-eval";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private DmiImageRagEvalRuntimeBuilder() {
    }

    record DmiChunkRow(
            String chunkId,
            String knowledgeBaseId,
            String documentId,
            String logicalChunkId,
            String content,
            Map<String, Object> metadata,
            String embeddingText,
            String titleSearchText,
            String embeddingLiteral
    ) {
    }

    record DmiAssetRow(
            String assetId,
            String documentId,
            String assetType,
            String assetKey,
            Integer pageNumber,
            String locatorJson,
            String contentHash,
            String parserVersion,
            String status,
            Path derivedPngFile
    ) {
    }

    record DmiRelationRow(String assetId, String chunkId, String documentId) {
    }

    record DmiDocumentRow(
            String documentId,
            String knowledgeBaseId,
            String filename,
            String filetype,
            long sizeBytes,
            Map<String, Object> metadata
    ) {
    }

    record DmiImportPlan(
            String knowledgeBaseId,
            Map<String, Object> knowledgeBaseMetadata,
            List<DmiDocumentRow> documents,
            List<DmiChunkRow> chunks,
            List<DmiAssetRow> assets,
            List<DmiRelationRow> relations
    ) {
    }

    static String knowledgeBaseId(String manifestSha256) {
        return deterministicRuntimeUuid(manifestSha256, "knowledge-base", DATASET_ID);
    }

    static String documentId(String manifestSha256, String sourceDocumentId) {
        return deterministicRuntimeUuid(manifestSha256, "document", sourceDocumentId);
    }

    static String chunkId(String manifestSha256, String logicalChunkId) {
        return deterministicRuntimeUuid(manifestSha256, "chunk", logicalChunkId);
    }

    static String assetId(String manifestSha256, String sourceDocumentId, String assetKey) {
        return deterministicRuntimeUuid(manifestSha256, "asset", sourceDocumentId + ":" + assetKey);
    }

    static void importPlan(Connection connection, String ownerId, DmiImportPlan plan) throws SQLException {
        requireIsolatedTransaction(connection);
        if (knowledgeBaseExists(connection, plan)) {
            verifyImportedPlan(connection, plan);
            return;
        }
        insertKnowledgeBase(connection, ownerId, plan);
        insertDocuments(connection, plan);
        for (DmiChunkRow chunk : plan.chunks()) {
            ensureIdUnused(connection, "chunk_bge_m3", "id", chunk.chunkId());
        }
        for (DmiAssetRow asset : plan.assets()) {
            ensureIdUnused(connection, "document_asset", "asset_id", asset.assetId());
        }
        insertChunks(connection, plan);
        insertAssets(connection, plan);
        insertRelations(connection, plan);
    }

    static void requireIsolatedTransaction(Connection connection) throws SQLException {
        if (connection == null) {
            throw new IllegalArgumentException("dmi eval import 缺少隔离数据库连接");
        }
        java.sql.DatabaseMetaData metadata = connection.getMetaData();
        if (metadata == null || !ISOLATED_JDBC_URL.equals(metadata.getURL())
                || !ISOLATED_CATALOG.equals(connection.getCatalog())) {
            throw new IllegalStateException("dmi eval import 仅允许 " + ISOLATED_JDBC_URL);
        }
        if (connection.getAutoCommit()) {
            throw new IllegalStateException("dmi eval import 禁止 autocommit，必须使用显式事务");
        }
    }

    private static boolean knowledgeBaseExists(Connection connection, DmiImportPlan plan) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT 1 FROM knowledge_base
                WHERE id = CAST(? AS uuid)
                  AND metadata->>'datasetId' = ?
                  AND metadata->>'manifestSha256' = ?
                """)) {
            statement.setString(1, plan.knowledgeBaseId());
            statement.setString(2, plan.knowledgeBaseMetadata().get("datasetId").toString());
            statement.setString(3, plan.knowledgeBaseMetadata().get("manifestSha256").toString());
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private static void verifyImportedPlan(Connection connection, DmiImportPlan plan) throws SQLException {
        int documents = countByKb(connection, "document", plan.knowledgeBaseId());
        int chunks = countByKb(connection, "chunk_bge_m3", plan.knowledgeBaseId());
        int assets = assetCount(connection, plan.knowledgeBaseId());
        int relations = relationCount(connection, plan);
        if (documents != plan.documents().size() || chunks < plan.chunks().size()
                || assets != plan.assets().size() || relations < plan.relations().size()) {
            throw new IllegalStateException("dmi eval 隔离库存在不完整的既有导入（documents=" + documents
                    + "/" + plan.documents().size() + " chunks=" + chunks + "/" + plan.chunks().size()
                    + " assets=" + assets + "/" + plan.assets().size()
                    + " relations=" + relations + "/" + plan.relations().size()
                    + "），请人工清理后重试");
        }
    }

    private static int countByKb(Connection connection, String table, String kbId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM " + table + " WHERE kb_id = CAST(? AS uuid)")) {
            statement.setString(1, kbId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static int assetCount(Connection connection, String kbId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM document_asset a
                JOIN document d ON d.id = a.document_id
                WHERE d.kb_id = CAST(? AS uuid)
                """)) {
            statement.setString(1, kbId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static int relationCount(Connection connection, DmiImportPlan plan) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM document_asset_chunk r
                JOIN document_asset a ON a.asset_id = r.asset_id
                JOIN document d ON d.id = a.document_id
                WHERE d.kb_id = CAST(? AS uuid)
                """)) {
            statement.setString(1, plan.knowledgeBaseId());
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static void insertKnowledgeBase(Connection connection, String ownerId, DmiImportPlan plan)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO knowledge_base
                (id, name, description, metadata, owner_id, evaluation_namespace, created_at, updated_at)
                VALUES (CAST(? AS uuid), ?, ?, CAST(? AS jsonb), CAST(? AS bigint), ?,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                ON CONFLICT (id) DO NOTHING
                """)) {
            statement.setString(1, plan.knowledgeBaseId());
            statement.setString(2, DATASET_ID + " candidate");
            statement.setString(3, "Isolated document-image RAG candidate fixture");
            statement.setString(4, toJson(plan.knowledgeBaseMetadata()));
            statement.setString(5, ownerId);
            statement.setString(6, EVALUATION_NAMESPACE);
            statement.executeUpdate();
        }
    }

    private static void insertDocuments(Connection connection, DmiImportPlan plan) throws SQLException {
        for (DmiDocumentRow document : plan.documents()) {
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

    private static void insertChunks(Connection connection, DmiImportPlan plan) throws SQLException {
        for (DmiChunkRow chunk : plan.chunks()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO chunk_bge_m3
                    (id, kb_id, doc_id, content, metadata, embedding, evaluation_namespace,
                     created_at, updated_at)
                    VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), ?, CAST(? AS jsonb),
                            CAST(? AS vector), ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    ON CONFLICT (id) DO NOTHING
                    """)) {
                statement.setString(1, chunk.chunkId());
                statement.setString(2, chunk.knowledgeBaseId());
                statement.setString(3, chunk.documentId());
                statement.setString(4, chunk.content());
                statement.setString(5, toJson(chunk.metadata()));
                statement.setString(6, chunk.embeddingLiteral());
                statement.setString(7, EVALUATION_NAMESPACE);
                statement.executeUpdate();
            }
        }
    }

    private static void insertAssets(Connection connection, DmiImportPlan plan) throws SQLException {
        for (DmiAssetRow asset : plan.assets()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO document_asset
                    (asset_id, document_id, asset_type, asset_key, page_number, locator,
                     content_hash, parser_version, status, created_at, updated_at)
                    VALUES (CAST(? AS uuid), CAST(? AS uuid), ?, ?, ?, CAST(? AS jsonb), ?, ?, ?,
                            CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                    ON CONFLICT (asset_id) DO NOTHING
                    """)) {
                statement.setString(1, asset.assetId());
                statement.setString(2, asset.documentId());
                statement.setString(3, asset.assetType());
                statement.setString(4, asset.assetKey());
                if (asset.pageNumber() == null) {
                    statement.setNull(5, java.sql.Types.INTEGER);
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

    private static void insertRelations(Connection connection, DmiImportPlan plan) throws SQLException {
        for (DmiRelationRow relation : plan.relations()) {
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO document_asset_chunk
                    (asset_id, chunk_id, asset_document_id, chunk_document_id)
                    VALUES (CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid), CAST(? AS uuid))
                    ON CONFLICT (asset_id, chunk_id) DO NOTHING
                    """)) {
                statement.setString(1, relation.assetId());
                statement.setString(2, relation.chunkId());
                statement.setString(3, relation.documentId());
                statement.setString(4, relation.documentId());
                statement.executeUpdate();
            }
        }
    }

    private static void ensureIdUnused(Connection connection, String table, String column, String id)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM " + table + " WHERE " + column + " = CAST(? AS uuid)")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    throw new IllegalStateException("dmi eval runtime UUID 已被其他数据占用: " + id);
                }
            }
        }
    }

    static String deterministicRuntimeUuid(String manifestSha256, String kind, String logicalId) {
        String value = String.join(":", "rag-eval", DATASET_ID, manifestSha256, kind, logicalId);
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString();
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    static String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("dmi eval import metadata 序列化失败", e);
        }
    }

    static String toJsonFromNode(Object node) {
        return node.toString();
    }

    static void writeTextFile(Path target, String content) {
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, content, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("dmi eval 报告写入失败: " + target, e);
        }
    }

    static Map<String, Object> linkedMap() {
        return new LinkedHashMap<>();
    }

    static List<Object> list() {
        return new ArrayList<>();
    }

    static String joinBbox(List<Double> bbox) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < bbox.size(); i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(bbox.get(i));
        }
        return builder.append(']').toString();
    }

    static String vectorLiteral(float[] embedding) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < embedding.length; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append(embedding[i]);
        }
        return builder.append(']').toString();
    }

    static TreeMap<String, Object> treeMap(Map<String, Object> source) {
        return new TreeMap<>(source);
    }
}