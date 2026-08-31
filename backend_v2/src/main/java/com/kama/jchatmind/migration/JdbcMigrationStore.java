package com.kama.jchatmind.migration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

public final class JdbcMigrationStore implements SchemaMigrationExecutor.MigrationStore {

    private static final String LEDGER_TABLE = "jchatmind_schema_migration_ledger";
    private static final String LEDGER_SCHEMA = "public";
    private static final String LEDGER_TABLE_REFERENCE = LEDGER_SCHEMA + "." + LEDGER_TABLE;
    private static final String BASELINE_ID = "__baseline__";
    private static final String MIGRATION_LOCK_SQL =
            "SELECT pg_advisory_lock(hashtextextended('jchatmind.schema.migration', 0))";
    private static final String MIGRATION_UNLOCK_SQL =
            "SELECT pg_advisory_unlock(hashtextextended('jchatmind.schema.migration', 0))";
    private static final Set<String> LEDGER_COLUMNS = Set.of(
            "migration_id",
            "migration_order",
            "migration_sha256",
            "status",
            "started_at",
            "completed_at"
    );
    private static final Map<String, ColumnDefinition> EXPECTED_COLUMNS = Map.of(
            "migration_id", new ColumnDefinition("varchar", false),
            "migration_order", new ColumnDefinition("integer", false),
            "migration_sha256", new ColumnDefinition("char", false),
            "status", new ColumnDefinition("varchar", false),
            "started_at", new ColumnDefinition("timestamp", false),
            "completed_at", new ColumnDefinition("timestamp", true)
    );
    private static final Set<String> EXPECTED_CHECK_CONSTRAINTS = Set.of(
            "chk_jchatmind_schema_migration_status",
            "chk_jchatmind_schema_migration_order"
    );

    private record ColumnDefinition(String type, boolean nullable) {
    }

    private final DataSource dataSource;

    public JdbcMigrationStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public SchemaMigrationExecutor.MigrationState readState() {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            if (!tableExists(metadata)) {
                return hasUserSchemaObjects(connection)
                        ? new SchemaMigrationExecutor.MigrationState(true, null, List.of())
                        : SchemaMigrationExecutor.MigrationState.empty();
            }

            verifyLedgerCatalog(connection, metadata);
            return readLedger(connection);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot inspect migration ledger", e);
        }
    }

    @Override
    public void installBaseline(String sql, String baselineSha256) {
        ensureLedgerTable();
        recordRunning(BASELINE_ID, 0, baselineSha256);
        executeSql("approved baseline", sql, true);
        markApplied(BASELINE_ID);
    }

    @Override
    public void apply(SchemaMigrationExecutor.Migration migration, String sql) {
        ensureLedgerTable();
        recordRunning(migration.id(), migration.order(), migration.sha256());
        executeSql(migration.path(), sql, migration.transactional());
        markApplied(migration.id());
    }

    @Override
    public <T> T withMigrationLock(Supplier<T> operation) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(true);
            executeLockStatement(connection, MIGRATION_LOCK_SQL);
            RuntimeException operationFailure = null;
            try {
                return operation.get();
            } catch (RuntimeException e) {
                operationFailure = e;
                throw e;
            } finally {
                try {
                    executeLockStatement(connection, MIGRATION_UNLOCK_SQL);
                } catch (SQLException e) {
                    if (operationFailure != null) {
                        operationFailure.addSuppressed(e);
                    } else {
                        throw e;
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot acquire migration lock", e);
        }
    }

    private SchemaMigrationExecutor.MigrationState readLedger(Connection connection) throws SQLException {
        String sql = "SELECT migration_id, migration_order, migration_sha256, status, started_at, completed_at "
                + "FROM " + LEDGER_TABLE_REFERENCE + " ORDER BY migration_order";
        String baselineSha256 = null;
        List<SchemaMigrationExecutor.LedgerEntry> entries = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                String id = resultSet.getString(1);
                int order = resultSet.getInt(2);
                String sha256 = resultSet.getString(3);
                SchemaMigrationExecutor.MigrationStatus status = parseStatus(resultSet.getString(4));
                java.sql.Timestamp startedAt = resultSet.getTimestamp(5);
                java.sql.Timestamp completedAt = resultSet.getTimestamp(6);
                if (startedAt == null
                        || (status == SchemaMigrationExecutor.MigrationStatus.APPLIED
                        ? completedAt == null
                        : completedAt != null)
                        || (completedAt != null && completedAt.before(startedAt))) {
                    throw new IllegalStateException("Migration ledger timestamps are inconsistent");
                }
                if (BASELINE_ID.equals(id)) {
                    if (baselineSha256 != null || order != 0 || status != SchemaMigrationExecutor.MigrationStatus.APPLIED) {
                        throw new IllegalStateException("Migration ledger baseline row is invalid");
                    }
                    baselineSha256 = sha256;
                } else {
                    entries.add(new SchemaMigrationExecutor.LedgerEntry(id, order, sha256, status));
                }
            }
        }
        return new SchemaMigrationExecutor.MigrationState(true, baselineSha256, entries);
    }

    private SchemaMigrationExecutor.MigrationStatus parseStatus(String value) {
        try {
            return SchemaMigrationExecutor.MigrationStatus.valueOf(value);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Migration ledger contains an unknown status", e);
        }
    }

    private void ensureLedgerTable() {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            if (tableExists(metadata)) {
                verifyLedgerCatalog(connection, metadata);
                return;
            }
            connection.setAutoCommit(true);
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE " + LEDGER_TABLE_REFERENCE + " ("
                        + "migration_id VARCHAR(128) PRIMARY KEY,"
                        + "migration_order INTEGER NOT NULL,"
                        + "migration_sha256 CHAR(64) NOT NULL,"
                        + "status VARCHAR(16) NOT NULL,"
                        + "started_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,"
                        + "completed_at TIMESTAMP DEFAULT NULL,"
                        + "CONSTRAINT chk_jchatmind_schema_migration_status "
                        + "CHECK (status IN ('RUNNING', 'APPLIED')),"
                        + "CONSTRAINT chk_jchatmind_schema_migration_order CHECK (migration_order >= 0)"
                        + ")");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot initialize migration ledger", e);
        }
    }

    private void verifyLedgerCatalog(Connection connection, DatabaseMetaData metadata) throws SQLException {
        verifyLedgerColumns(metadata);
        Set<String> primaryKeyColumns = new HashSet<>();
        try (ResultSet keys = metadata.getPrimaryKeys(null, LEDGER_SCHEMA, LEDGER_TABLE)) {
            while (keys.next()) {
                primaryKeyColumns.add(keys.getString("COLUMN_NAME").toLowerCase());
            }
        }
        if (!primaryKeyColumns.equals(Set.of("migration_id"))) {
            throw new IllegalStateException("Migration ledger primary key does not match the expected schema");
        }

        Set<String> checkConstraints = new HashSet<>();
        String sql = "SELECT conname FROM pg_catalog.pg_constraint "
                + "WHERE conrelid = '" + LEDGER_SCHEMA + "." + LEDGER_TABLE + "'::regclass AND contype = 'c'";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet constraints = statement.executeQuery()) {
            while (constraints.next()) {
                checkConstraints.add(constraints.getString(1));
            }
        }
        if (!checkConstraints.equals(EXPECTED_CHECK_CONSTRAINTS)) {
            throw new IllegalStateException("Migration ledger constraints do not match the expected schema");
        }
    }

    private void verifyLedgerColumns(DatabaseMetaData metadata) throws SQLException {
        Map<String, ColumnDefinition> actualColumns = new HashMap<>();
        try (ResultSet columns = metadata.getColumns(null, LEDGER_SCHEMA, LEDGER_TABLE, "%")) {
            while (columns.next()) {
                String name = columns.getString("COLUMN_NAME").toLowerCase();
                actualColumns.put(name, new ColumnDefinition(
                        normalizeType(columns.getString("TYPE_NAME")),
                        columns.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls
                ));
            }
        }
        if (!actualColumns.keySet().equals(LEDGER_COLUMNS) || !actualColumns.equals(EXPECTED_COLUMNS)) {
            throw new IllegalStateException("Migration ledger catalog does not match the expected schema");
        }
    }

    private String normalizeType(String type) {
        if (type == null) {
            return "";
        }
        return switch (type.toLowerCase()) {
            case "int4", "integer" -> "integer";
            case "bpchar", "char" -> "char";
            case "timestamp", "timestamp without time zone" -> "timestamp";
            default -> type.toLowerCase();
        };
    }

    private boolean tableExists(DatabaseMetaData metadata) throws SQLException {
        try (ResultSet tables = metadata.getTables(null, LEDGER_SCHEMA, LEDGER_TABLE, new String[]{"TABLE"})) {
            return tables.next();
        }
    }

    private boolean hasUserSchemaObjects(Connection connection) throws SQLException {
        String sql = "SELECT EXISTS ("
                + "SELECT 1 FROM pg_catalog.pg_class c "
                + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE n.nspname NOT IN ('pg_catalog', 'information_schema', 'pg_toast') "
                + "AND c.relkind IN ('r', 'p', 'v', 'm', 'f', 'S', 'c', 't')"
                + ")";
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() && resultSet.getBoolean(1);
        }
    }

    private void recordRunning(String id, int order, String sha256) {
        String sql = "INSERT INTO " + LEDGER_TABLE_REFERENCE
                + " (migration_id, migration_order, migration_sha256, status) VALUES (?, ?, ?, 'RUNNING')";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            connection.setAutoCommit(false);
            try {
                statement.setString(1, id);
                statement.setInt(2, order);
                statement.setString(3, sha256);
                statement.executeUpdate();
                connection.commit();
            } catch (SQLException e) {
                rollback(connection, e);
                throw e;
            } catch (RuntimeException e) {
                rollback(connection, e);
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot record migration start: " + id, e);
        }
    }

    private void markApplied(String id) {
        String sql = "UPDATE " + LEDGER_TABLE_REFERENCE
                + " SET status = 'APPLIED', completed_at = CURRENT_TIMESTAMP WHERE migration_id = ?";
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            connection.setAutoCommit(false);
            try {
                statement.setString(1, id);
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("Migration ledger row was not updated: " + id);
                }
                connection.commit();
            } catch (SQLException e) {
                rollback(connection, e);
                throw e;
            } catch (RuntimeException e) {
                rollback(connection, e);
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot record migration completion: " + id, e);
        }
    }

    private void executeSql(String path, String sql, boolean transactional) {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            connection.setAutoCommit(!transactional);
            try {
                statement.execute("SET search_path TO public");
                statement.execute(sql);
                if (transactional) {
                    connection.commit();
                }
            } catch (SQLException e) {
                if (transactional) {
                    rollback(connection, e);
                }
                throw e;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Migration script failed: " + path, e);
        }
    }

    private void executeLockStatement(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void rollback(Connection connection, Throwable failure) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }
}
