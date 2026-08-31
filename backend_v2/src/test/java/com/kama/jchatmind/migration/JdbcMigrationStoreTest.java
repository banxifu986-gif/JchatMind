package com.kama.jchatmind.migration;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JdbcMigrationStoreTest {

    @Test
    void shouldReturnFreshStateOnlyWhenDatabaseHasNoUserTables() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet ledgerTables = mock(ResultSet.class);
        PreparedStatement objectStatement = mock(PreparedStatement.class);
        ResultSet objectRows = mock(ResultSet.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getTables(null, "public", "jchatmind_schema_migration_ledger", new String[]{"TABLE"}))
                .thenReturn(ledgerTables);
        when(ledgerTables.next()).thenReturn(false);
        when(connection.prepareStatement(anyString())).thenReturn(objectStatement);
        when(objectStatement.executeQuery()).thenReturn(objectRows);
        when(objectRows.next()).thenReturn(false);

        SchemaMigrationExecutor.MigrationState state = new JdbcMigrationStore(dataSource).readState();

        assertThat(state).isEqualTo(SchemaMigrationExecutor.MigrationState.empty());
    }

    @Test
    void shouldTreatUserSchemaObjectsWithoutLedgerAsUnknownState() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet ledgerTables = mock(ResultSet.class);
        PreparedStatement objectStatement = mock(PreparedStatement.class);
        ResultSet objectRows = mock(ResultSet.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getTables(null, "public", "jchatmind_schema_migration_ledger", new String[]{"TABLE"}))
                .thenReturn(ledgerTables);
        when(ledgerTables.next()).thenReturn(false);
        when(connection.prepareStatement(anyString())).thenReturn(objectStatement);
        when(objectStatement.executeQuery()).thenReturn(objectRows);
        when(objectRows.next()).thenReturn(true);
        when(objectRows.getBoolean(1)).thenReturn(true);

        SchemaMigrationExecutor.MigrationState state = new JdbcMigrationStore(dataSource).readState();

        assertThat(state.ledgerPresent()).isTrue();
        assertThat(state.baselineSha256()).isNull();
        assertThat(state.entries()).isEmpty();
    }

    @Test
    void shouldFailClosedWhenLedgerTableExistsWithUnexpectedColumns() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet ledgerTables = mock(ResultSet.class);
        ResultSet columns = mock(ResultSet.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getTables(null, "public", "jchatmind_schema_migration_ledger", new String[]{"TABLE"}))
                .thenReturn(ledgerTables);
        when(ledgerTables.next()).thenReturn(true, false);
        when(metadata.getColumns(null, "public", "jchatmind_schema_migration_ledger", "%"))
                .thenReturn(columns);
        when(columns.next()).thenReturn(true, false);
        when(columns.getString("COLUMN_NAME")).thenReturn("migration_id");

        assertThatThrownBy(() -> new JdbcMigrationStore(dataSource).readState())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ledger");
    }

    @Test
    void shouldReadBaselineAndAppliedRowsFromVerifiedLedger() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet ledgerTables = mock(ResultSet.class);
        ResultSet columns = mock(ResultSet.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);

        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getTables(null, "public", "jchatmind_schema_migration_ledger", new String[]{"TABLE"}))
                .thenReturn(ledgerTables);
        when(ledgerTables.next()).thenReturn(true, false);
        when(metadata.getColumns(null, "public", "jchatmind_schema_migration_ledger", "%"))
                .thenReturn(columns);
        when(columns.next()).thenReturn(true, true, true, true, true, true, false);
        when(columns.getString("COLUMN_NAME")).thenReturn(
                "migration_id", "migration_order", "migration_sha256", "status", "started_at", "completed_at"
        );
        when(columns.getString("TYPE_NAME")).thenReturn(
                "varchar", "int4", "bpchar", "varchar", "timestamp", "timestamp"
        );
        when(columns.getInt("NULLABLE")).thenReturn(
                DatabaseMetaData.columnNoNulls,
                DatabaseMetaData.columnNoNulls,
                DatabaseMetaData.columnNoNulls,
                DatabaseMetaData.columnNoNulls,
                DatabaseMetaData.columnNoNulls,
                DatabaseMetaData.columnNullable
        );
        ResultSet primaryKeys = mock(ResultSet.class);
        when(metadata.getPrimaryKeys(null, "public", "jchatmind_schema_migration_ledger"))
                .thenReturn(primaryKeys);
        when(primaryKeys.next()).thenReturn(true, false);
        when(primaryKeys.getString("COLUMN_NAME")).thenReturn("migration_id");
        PreparedStatement constraintStatement = mock(PreparedStatement.class);
        ResultSet constraints = mock(ResultSet.class);
        when(constraintStatement.executeQuery()).thenReturn(constraints);
        when(constraints.next()).thenReturn(true, true, false);
        when(constraints.getString(1)).thenReturn(
                "chk_jchatmind_schema_migration_status",
                "chk_jchatmind_schema_migration_order"
        );
        when(connection.prepareStatement(anyString())).thenAnswer(invocation ->
                invocation.getArgument(0, String.class).contains("pg_constraint")
                        ? constraintStatement
                        : statement
        );
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(true, true, false);
        when(rows.getString(1)).thenReturn("__baseline__", "auth.create-user-table");
        when(rows.getInt(2)).thenReturn(0, 10);
        when(rows.getString(3)).thenReturn(
                "baseline-sha256",
                "b82f050023e0e9cfdb0d01ca3e10bdc97c83843cc2d9bb95991a8ec5767f84a4"
        );
        when(rows.getString(4)).thenReturn("APPLIED", "APPLIED");
        when(rows.getTimestamp(5)).thenReturn(new Timestamp(0), new Timestamp(0));
        when(rows.getTimestamp(6)).thenReturn(new Timestamp(1), new Timestamp(1));

        SchemaMigrationExecutor.MigrationState state = new JdbcMigrationStore(dataSource).readState();

        assertThat(state.ledgerPresent()).isTrue();
        assertThat(state.baselineSha256()).isEqualTo("baseline-sha256");
        assertThat(state.entries()).containsExactly(new SchemaMigrationExecutor.LedgerEntry(
                "auth.create-user-table",
                10,
                "b82f050023e0e9cfdb0d01ca3e10bdc97c83843cc2d9bb95991a8ec5767f84a4",
                SchemaMigrationExecutor.MigrationStatus.APPLIED
        ));
    }

    @Test
    void shouldCommitTransactionalMigrationAfterExecutingVerifiedSql() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection ledgerConnection = mock(Connection.class);
        Connection recordConnection = mock(Connection.class);
        Connection executionConnection = mock(Connection.class);
        Connection completionConnection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet ledgerTables = mock(ResultSet.class);
        Statement createLedgerStatement = mock(Statement.class);
        PreparedStatement recordStatement = mock(PreparedStatement.class);
        Statement executionStatement = mock(Statement.class);
        PreparedStatement completionStatement = mock(PreparedStatement.class);

        when(dataSource.getConnection()).thenReturn(
                ledgerConnection, recordConnection, executionConnection, completionConnection
        );
        when(ledgerConnection.getMetaData()).thenReturn(metadata);
        when(metadata.getTables(any(), any(), any(), any())).thenReturn(ledgerTables);
        when(ledgerTables.next()).thenReturn(false);
        when(ledgerConnection.createStatement()).thenReturn(createLedgerStatement);
        when(recordConnection.prepareStatement(anyString())).thenReturn(recordStatement);
        when(recordStatement.executeUpdate()).thenReturn(1);
        when(executionConnection.createStatement()).thenReturn(executionStatement);
        when(executionStatement.execute("CREATE TABLE migration_marker(id INTEGER);"))
                .thenReturn(false);
        when(completionConnection.prepareStatement(anyString())).thenReturn(completionStatement);
        when(completionStatement.executeUpdate()).thenReturn(1);

        SchemaMigrationExecutor.Migration migration = new SchemaMigrationExecutor.Migration(
                "test.migration",
                10,
                "sql/test-migration.sql",
                "b".repeat(64),
                true,
                List.of("baseline.application-schema")
        );

        new JdbcMigrationStore(dataSource).apply(migration, "CREATE TABLE migration_marker(id INTEGER);");

        var order = inOrder(executionConnection, executionStatement);
        order.verify(executionConnection).setAutoCommit(false);
        order.verify(executionStatement).execute("SET search_path TO public");
        order.verify(executionStatement).execute("CREATE TABLE migration_marker(id INTEGER);");
        order.verify(executionConnection).commit();
    }

    @Test
    void shouldRollbackTransactionalMigrationWhenSqlExecutionFails() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection ledgerConnection = mock(Connection.class);
        Connection recordConnection = mock(Connection.class);
        Connection executionConnection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet ledgerTables = mock(ResultSet.class);
        Statement createLedgerStatement = mock(Statement.class);
        PreparedStatement recordStatement = mock(PreparedStatement.class);
        Statement executionStatement = mock(Statement.class);

        when(dataSource.getConnection()).thenReturn(ledgerConnection, recordConnection, executionConnection);
        when(ledgerConnection.getMetaData()).thenReturn(metadata);
        when(metadata.getTables(any(), any(), any(), any())).thenReturn(ledgerTables);
        when(ledgerTables.next()).thenReturn(false);
        when(ledgerConnection.createStatement()).thenReturn(createLedgerStatement);
        when(recordConnection.prepareStatement(anyString())).thenReturn(recordStatement);
        when(recordStatement.executeUpdate()).thenReturn(1);
        when(executionConnection.createStatement()).thenReturn(executionStatement);
        when(executionStatement.execute("BROKEN SQL")).thenThrow(new SQLException("simulated sql failure"));

        SchemaMigrationExecutor.Migration migration = new SchemaMigrationExecutor.Migration(
                "test.migration",
                10,
                "sql/test-migration.sql",
                "b".repeat(64),
                true,
                List.of("baseline.application-schema")
        );

        assertThatThrownBy(() -> new JdbcMigrationStore(dataSource).apply(migration, "BROKEN SQL"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Migration script failed");
        org.mockito.InOrder order = inOrder(executionConnection);
        order.verify(executionConnection).setAutoCommit(false);
        order.verify(executionConnection).rollback();
    }

    @Test
    void shouldLeaveNonTransactionalMigrationInAutocommitMode() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection ledgerConnection = mock(Connection.class);
        Connection recordConnection = mock(Connection.class);
        Connection executionConnection = mock(Connection.class);
        Connection completionConnection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet ledgerTables = mock(ResultSet.class);
        Statement createLedgerStatement = mock(Statement.class);
        PreparedStatement recordStatement = mock(PreparedStatement.class);
        Statement executionStatement = mock(Statement.class);
        PreparedStatement completionStatement = mock(PreparedStatement.class);
        String sql = "CREATE INDEX migration_marker_idx ON migration_marker(id);";

        when(dataSource.getConnection()).thenReturn(
                ledgerConnection, recordConnection, executionConnection, completionConnection
        );
        when(ledgerConnection.getMetaData()).thenReturn(metadata);
        when(metadata.getTables(any(), any(), any(), any())).thenReturn(ledgerTables);
        when(ledgerTables.next()).thenReturn(false);
        when(ledgerConnection.createStatement()).thenReturn(createLedgerStatement);
        when(recordConnection.prepareStatement(anyString())).thenReturn(recordStatement);
        when(recordStatement.executeUpdate()).thenReturn(1);
        when(executionConnection.createStatement()).thenReturn(executionStatement);
        when(executionStatement.execute(sql)).thenReturn(false);
        when(completionConnection.prepareStatement(anyString())).thenReturn(completionStatement);
        when(completionStatement.executeUpdate()).thenReturn(1);

        SchemaMigrationExecutor.Migration migration = new SchemaMigrationExecutor.Migration(
                "test.non-transactional-migration",
                10,
                "sql/test-migration.sql",
                sha256(sql),
                false,
                List.of("baseline.application-schema")
        );

        new JdbcMigrationStore(dataSource).apply(migration, sql);

        org.mockito.InOrder order = inOrder(executionConnection, executionStatement);
        order.verify(executionConnection).setAutoCommit(true);
        order.verify(executionStatement).execute(sql);
        org.mockito.Mockito.verify(executionConnection, org.mockito.Mockito.never()).commit();
    }

    @Test
    void shouldRollbackCompletionUpdateWhenLedgerRowCountIsUnexpected() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection ledgerConnection = mock(Connection.class);
        Connection recordConnection = mock(Connection.class);
        Connection executionConnection = mock(Connection.class);
        Connection completionConnection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet ledgerTables = mock(ResultSet.class);
        Statement createLedgerStatement = mock(Statement.class);
        PreparedStatement recordStatement = mock(PreparedStatement.class);
        Statement executionStatement = mock(Statement.class);
        PreparedStatement completionStatement = mock(PreparedStatement.class);
        String sql = "CREATE TABLE migration_marker(id INTEGER);";

        when(dataSource.getConnection()).thenReturn(
                ledgerConnection, recordConnection, executionConnection, completionConnection
        );
        when(ledgerConnection.getMetaData()).thenReturn(metadata);
        when(metadata.getTables(any(), any(), any(), any())).thenReturn(ledgerTables);
        when(ledgerTables.next()).thenReturn(false);
        when(ledgerConnection.createStatement()).thenReturn(createLedgerStatement);
        when(recordConnection.prepareStatement(anyString())).thenReturn(recordStatement);
        when(recordStatement.executeUpdate()).thenReturn(1);
        when(executionConnection.createStatement()).thenReturn(executionStatement);
        when(executionStatement.execute(sql)).thenReturn(false);
        when(completionConnection.prepareStatement(anyString())).thenReturn(completionStatement);
        when(completionStatement.executeUpdate()).thenReturn(0);

        SchemaMigrationExecutor.Migration migration = new SchemaMigrationExecutor.Migration(
                "test.migration",
                10,
                "sql/test-migration.sql",
                sha256(sql),
                true,
                List.of("baseline.application-schema")
        );

        assertThatThrownBy(() -> new JdbcMigrationStore(dataSource).apply(migration, sql))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not updated");
        org.mockito.Mockito.verify(completionConnection).rollback();
    }

    private String sha256(String value) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))
        );
    }
}
