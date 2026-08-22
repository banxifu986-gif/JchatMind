package com.kama.jchatmind.ingestion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfSystemProperty(named = "g2.asset.contract.l2", matches = "true")
class DocumentAssetMigrationL2Test {

    private static final String JDBC_URL_PROPERTY = "g2.asset.contract.jdbc-url";
    private static final String ISOLATED_DATABASE = "g2assetcontract";
    private static final String DEFAULT_JDBC_URL = "jdbc:postgresql://127.0.0.1:55433/" + ISOLATED_DATABASE;
    private static final Path MIGRATION = Path.of(
            "..", "sql", "ingestion", "2026-08-22-create-document-asset.sql"
    );
    private static final String DOCUMENT_A = "00000000-0000-0000-0000-00000000a001";
    private static final String DOCUMENT_B = "00000000-0000-0000-0000-00000000b001";
    private static final String ASSET_A = "00000000-0000-0000-0000-00000000a101";
    private static final String CHUNK_A = "00000000-0000-0000-0000-00000000a201";
    private static final String CHUNK_B = "00000000-0000-0000-0000-00000000b201";
    private static final String VALID_HASH = "a".repeat(64);

    @Test
    void rejectsInvalidAssetPropertiesAndCrossDocumentAssetChunkRelations() throws Exception {
        withMigratedSchema(connection -> {
            execute(connection, "INSERT INTO document (id) VALUES ('" + DOCUMENT_A + "'), ('" + DOCUMENT_B + "')");
            execute(connection, "INSERT INTO chunk_bge_m3 (id, doc_id) VALUES ('" + CHUNK_A + "', '" + DOCUMENT_A + "'), ('" + CHUNK_B + "', '" + DOCUMENT_B + "')");

            assertThatThrownBy(() -> execute(connection, assetInsertSql("00000000-0000-0000-0000-00000000a102", DOCUMENT_A, "z".repeat(64))))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> execute(connection, """
                    INSERT INTO document_asset (asset_id, document_id, asset_type, asset_key, page_number, content_hash, parser_version, status)
                    VALUES ('00000000-0000-0000-0000-00000000a103', '%s', 'PDF_PAGE_TEXT', 'page-invalid', 0, '%s', 'pdf-text-v1', 'READY')
                    """.formatted(DOCUMENT_A, VALID_HASH)))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> execute(connection, """
                    INSERT INTO document_asset (asset_id, document_id, asset_type, asset_key, content_hash, parser_version, status)
                    VALUES ('00000000-0000-0000-0000-00000000a104', '%s', 'PDF_PAGE_TEXT', 'status-invalid', '%s', 'pdf-text-v1', 'INVALID')
                    """.formatted(DOCUMENT_A, VALID_HASH)))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> execute(connection, """
                    INSERT INTO document_asset (asset_id, document_id, asset_type, asset_key, content_hash, parser_version, status)
                    VALUES ('00000000-0000-0000-0000-00000000a105', '%s', 'UNKNOWN', 'type-invalid', '%s', 'pdf-text-v1', 'READY')
                    """.formatted(DOCUMENT_A, VALID_HASH)))
                    .isInstanceOf(SQLException.class);

            execute(connection, assetInsertSql(ASSET_A, DOCUMENT_A, VALID_HASH));
            assertThatThrownBy(() -> execute(connection, relationInsertSql(ASSET_A, CHUNK_B, DOCUMENT_A, DOCUMENT_B)))
                    .isInstanceOf(SQLException.class);
            execute(connection, relationInsertSql(ASSET_A, CHUNK_A, DOCUMENT_A, DOCUMENT_A));
            assertThatThrownBy(() -> execute(connection, """
                    UPDATE document_asset
                    SET document_id = '%s'
                    WHERE asset_id = '%s'
                    """.formatted(DOCUMENT_B, ASSET_A)))
                    .isInstanceOf(SQLException.class);
            assertThatThrownBy(() -> execute(connection, """
                    UPDATE chunk_bge_m3
                    SET doc_id = '%s'
                    WHERE id = '%s'
                    """.formatted(DOCUMENT_B, CHUNK_A)))
                    .isInstanceOf(SQLException.class);
        });
    }

    @Test
    void cascadesRelationsWhenDocumentAssetOrChunkIsDeleted() throws Exception {
        withMigratedSchema(connection -> {
            execute(connection, "INSERT INTO document (id) VALUES ('" + DOCUMENT_A + "'), ('" + DOCUMENT_B + "')");
            execute(connection, "INSERT INTO chunk_bge_m3 (id, doc_id) VALUES ('" + CHUNK_A + "', '" + DOCUMENT_A + "'), ('" + CHUNK_B + "', '" + DOCUMENT_B + "')");

            execute(connection, assetInsertSql(ASSET_A, DOCUMENT_A, VALID_HASH));
            execute(connection, relationInsertSql(ASSET_A, CHUNK_A, DOCUMENT_A, DOCUMENT_A));
            execute(connection, "DELETE FROM document_asset WHERE asset_id = '" + ASSET_A + "'");
            assertThat(countRelations(connection)).isZero();

            String assetB = "00000000-0000-0000-0000-00000000b101";
            execute(connection, assetInsertSql(assetB, DOCUMENT_B, VALID_HASH));
            execute(connection, relationInsertSql(assetB, CHUNK_B, DOCUMENT_B, DOCUMENT_B));
            execute(connection, "DELETE FROM chunk_bge_m3 WHERE id = '" + CHUNK_B + "'");
            assertThat(countRelations(connection)).isZero();

            String assetC = "00000000-0000-0000-0000-00000000a104";
            execute(connection, assetInsertSql(assetC, DOCUMENT_A, VALID_HASH));
            execute(connection, relationInsertSql(assetC, CHUNK_A, DOCUMENT_A, DOCUMENT_A));
            execute(connection, "DELETE FROM document WHERE id = '" + DOCUMENT_A + "'");
            assertThat(countAssetsByDocument(connection, DOCUMENT_A)).isZero();
            assertThat(countRelations(connection)).isZero();
        });
    }

    private void withMigratedSchema(ThrowingConnectionConsumer consumer) throws Exception {
        String jdbcUrl = System.getProperty(JDBC_URL_PROPERTY, DEFAULT_JDBC_URL);
        try (Connection connection = DriverManager.getConnection(jdbcUrl, "postgres", "")) {
            assertThat(databaseName(connection)).isEqualTo(ISOLATED_DATABASE);
            try {
                createParentTables(connection);
                executeMigration(connection);
                consumer.accept(connection);
            } finally {
                dropTables(connection);
            }
        }
    }

    private void createParentTables(Connection connection) throws SQLException {
        execute(connection, "CREATE TABLE document (id UUID PRIMARY KEY)");
        execute(connection, "CREATE TABLE chunk_bge_m3 (id UUID PRIMARY KEY, doc_id UUID NOT NULL)");
    }

    private void executeMigration(Connection connection) throws Exception {
        execute(connection, Files.readString(MIGRATION));
    }

    private String assetInsertSql(String assetId, String documentId, String contentHash) {
        return """
                INSERT INTO document_asset (asset_id, document_id, asset_type, asset_key, content_hash, parser_version, status)
                VALUES ('%s', '%s', 'PDF_PAGE_TEXT', 'page-1', '%s', 'pdf-text-v1', 'READY')
                """.formatted(assetId, documentId, contentHash);
    }

    private String relationInsertSql(
            String assetId,
            String chunkId,
            String assetDocumentId,
            String chunkDocumentId
    ) {
        return """
                INSERT INTO document_asset_chunk (asset_id, chunk_id, asset_document_id, chunk_document_id)
                VALUES ('%s', '%s', '%s', '%s')
                """.formatted(assetId, chunkId, assetDocumentId, chunkDocumentId);
    }

    private int countRelations(Connection connection) throws SQLException {
        return count(connection, "SELECT COUNT(*) FROM document_asset_chunk");
    }

    private int countAssetsByDocument(Connection connection, String documentId) throws SQLException {
        return count(connection, "SELECT COUNT(*) FROM document_asset WHERE document_id = '" + documentId + "'");
    }

    private int count(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getInt(1);
        }
    }

    private String databaseName(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery("SELECT current_database()")) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString(1);
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void dropTables(Connection connection) throws SQLException {
        execute(connection, "ROLLBACK");
        execute(connection, "DROP TABLE IF EXISTS document_asset_chunk CASCADE");
        execute(connection, "DROP TABLE IF EXISTS document_asset CASCADE");
        execute(connection, "DROP TABLE IF EXISTS chunk_bge_m3 CASCADE");
        execute(connection, "DROP TABLE IF EXISTS document CASCADE");
    }

    @FunctionalInterface
    private interface ThrowingConnectionConsumer {
        void accept(Connection connection) throws Exception;
    }
}
