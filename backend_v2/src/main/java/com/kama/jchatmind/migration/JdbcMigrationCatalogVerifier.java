package com.kama.jchatmind.migration;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Set;

public final class JdbcMigrationCatalogVerifier {

    private final DataSource dataSource;
    private final JdbcMigrationStore migrationStore;
    private final MigrationCatalogVerifier verifier;
    private final String schema;

    public JdbcMigrationCatalogVerifier(DataSource dataSource, MigrationCatalogContract contract) {
        this.dataSource = dataSource;
        this.migrationStore = null;
        this.verifier = new MigrationCatalogVerifier(contract);
        this.schema = contract.schema();
    }

    public JdbcMigrationCatalogVerifier(JdbcMigrationStore migrationStore, MigrationCatalogContract contract) {
        this.dataSource = null;
        this.migrationStore = migrationStore;
        this.verifier = new MigrationCatalogVerifier(contract);
        this.schema = contract.schema();
    }

    public MigrationCatalogVerifier.VerificationResult verify() {
        if (migrationStore != null) {
            return migrationStore.withMigrationConnection(this::verifyConnection);
        }
        try (Connection connection = dataSource.getConnection()) {
            return verifyConnection(connection);
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot inspect managed migration catalog", e);
        }
    }

    private MigrationCatalogVerifier.VerificationResult verifyConnection(Connection connection) throws SQLException {
        return verifier.verify(readSnapshot(connection));
    }

    private MigrationCatalogSnapshot readSnapshot(Connection connection) throws SQLException {
        Set<MigrationCatalogContract.CatalogObject> objects = new HashSet<>();
        readExtensions(connection, objects);
        readTables(connection, objects);
        readColumns(connection, objects);
        readConstraints(connection, objects);
        readIndexes(connection, objects);
        readFunctions(connection, objects);
        readTriggers(connection, objects);
        return new MigrationCatalogSnapshot(objects);
    }

    private void readExtensions(
            Connection connection,
            Set<MigrationCatalogContract.CatalogObject> objects
    ) throws SQLException {
        query(connection, "SELECT extname FROM pg_catalog.pg_extension", resultSet ->
                objects.add(new MigrationCatalogContract.CatalogObject(
                        "extension", "", "", resultSet.getString(1), ""
                )));
    }

    private void readTables(
            Connection connection,
            Set<MigrationCatalogContract.CatalogObject> objects
    ) throws SQLException {
        query(connection, """
                SELECT n.nspname, c.relname
                FROM pg_catalog.pg_class c
                JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ? AND c.relkind IN ('r', 'p')
                """, statement -> statement.setString(1, schema), resultSet ->
                objects.add(new MigrationCatalogContract.CatalogObject(
                        "table", resultSet.getString(1), "", resultSet.getString(2), ""
                )));
    }

    private void readColumns(
            Connection connection,
            Set<MigrationCatalogContract.CatalogObject> objects
    ) throws SQLException {
        query(connection, """
                SELECT n.nspname, c.relname, a.attname,
                       pg_catalog.format_type(a.atttypid, a.atttypmod), NOT a.attnotnull,
                       COALESCE(pg_catalog.pg_get_expr(default_value.adbin, default_value.adrelid), ''),
                       a.attidentity, a.attgenerated
                FROM pg_catalog.pg_attribute a
                JOIN pg_catalog.pg_class c ON c.oid = a.attrelid
                JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                LEFT JOIN pg_catalog.pg_attrdef default_value
                    ON default_value.adrelid = a.attrelid
                   AND default_value.adnum = a.attnum
                WHERE n.nspname = ?
                  AND c.relkind IN ('r', 'p')
                  AND a.attnum > 0
                  AND NOT a.attisdropped
                """, statement -> statement.setString(1, schema), resultSet -> {
            String schema = resultSet.getString(1);
            String table = resultSet.getString(2);
            String column = resultSet.getString(3);
            objects.add(new MigrationCatalogContract.CatalogObject("column", schema, table, column, ""));
            objects.add(new MigrationCatalogContract.CatalogObject(
                    "column-definition",
                    schema,
                    table,
                    column,
                    resultSet.getString(4) + ":"
                            + resultSet.getBoolean(5) + ":"
                            + resultSet.getString(6) + ":"
                            + resultSet.getString(7) + ":"
                            + resultSet.getString(8)
            ));
        });
    }

    private void readConstraints(
            Connection connection,
            Set<MigrationCatalogContract.CatalogObject> objects
    ) throws SQLException {
        query(connection, """
                SELECT n.nspname, c.relname, con.conname, con.contype, con.convalidated,
                       pg_catalog.pg_get_constraintdef(con.oid)
                FROM pg_catalog.pg_constraint con
                JOIN pg_catalog.pg_class c ON c.oid = con.conrelid
                JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ?
                """, statement -> statement.setString(1, schema), resultSet -> {
            String schema = resultSet.getString(1);
            String table = resultSet.getString(2);
            String name = resultSet.getString(3);
            objects.add(new MigrationCatalogContract.CatalogObject(
                    "constraint", schema, table, name,
                    resultSet.getString(4) + ":" + resultSet.getBoolean(5)
            ));
            objects.add(new MigrationCatalogContract.CatalogObject(
                    "constraint-definition", schema, table, name, resultSet.getString(6)
            ));
        });
    }

    private void readIndexes(
            Connection connection,
            Set<MigrationCatalogContract.CatalogObject> objects
    ) throws SQLException {
        query(connection, """
                SELECT n.nspname, table_class.relname, index_class.relname, access_method.amname,
                       index_info.indisunique, pg_catalog.pg_get_indexdef(index_class.oid)
                FROM pg_catalog.pg_index index_info
                JOIN pg_catalog.pg_class index_class ON index_class.oid = index_info.indexrelid
                JOIN pg_catalog.pg_class table_class ON table_class.oid = index_info.indrelid
                JOIN pg_catalog.pg_namespace n ON n.oid = table_class.relnamespace
                JOIN pg_catalog.pg_am access_method ON access_method.oid = index_class.relam
                WHERE n.nspname = ?
                """, statement -> statement.setString(1, schema), resultSet -> {
            String schema = resultSet.getString(1);
            String table = resultSet.getString(2);
            String name = resultSet.getString(3);
            objects.add(new MigrationCatalogContract.CatalogObject(
                    "index", schema, table, name,
                    resultSet.getString(4) + ":" + resultSet.getBoolean(5)
            ));
            objects.add(new MigrationCatalogContract.CatalogObject(
                    "index-definition", schema, table, name, resultSet.getString(6)
            ));
        });
    }

    private void readFunctions(
            Connection connection,
            Set<MigrationCatalogContract.CatalogObject> objects
    ) throws SQLException {
        query(connection, """
                SELECT n.nspname, p.proname,
                       pg_catalog.pg_get_function_identity_arguments(p.oid),
                       pg_catalog.pg_get_functiondef(p.oid)
                FROM pg_catalog.pg_proc p
                JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
                LEFT JOIN pg_catalog.pg_depend extension_dependency
                    ON extension_dependency.classid = 'pg_catalog.pg_proc'::pg_catalog.regclass
                   AND extension_dependency.objid = p.oid
                   AND extension_dependency.refclassid = 'pg_catalog.pg_extension'::pg_catalog.regclass
                   AND extension_dependency.deptype = 'e'
                WHERE n.nspname = ?
                  AND p.prokind = 'f'
                  AND extension_dependency.objid IS NULL
                """, statement -> statement.setString(1, schema), resultSet -> {
            String schema = resultSet.getString(1);
            String name = resultSet.getString(2);
            objects.add(new MigrationCatalogContract.CatalogObject("function", schema, "", name, ""));
            objects.add(new MigrationCatalogContract.CatalogObject(
                    "function-signature", schema, "", name, resultSet.getString(3)
            ));
            objects.add(new MigrationCatalogContract.CatalogObject(
                    "function-definition", schema, "", name, resultSet.getString(4)
            ));
        });
    }

    private void readTriggers(
            Connection connection,
            Set<MigrationCatalogContract.CatalogObject> objects
    ) throws SQLException {
        query(connection, """
                SELECT n.nspname, c.relname, trigger_info.tgname,
                       pg_catalog.pg_get_triggerdef(trigger_info.oid)
                FROM pg_catalog.pg_trigger trigger_info
                JOIN pg_catalog.pg_class c ON c.oid = trigger_info.tgrelid
                JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = ? AND NOT trigger_info.tgisinternal
                """, statement -> statement.setString(1, schema), resultSet -> {
            String schema = resultSet.getString(1);
            String table = resultSet.getString(2);
            String name = resultSet.getString(3);
            objects.add(new MigrationCatalogContract.CatalogObject("trigger", schema, table, name, ""));
            objects.add(new MigrationCatalogContract.CatalogObject(
                    "trigger-definition", schema, table, name, resultSet.getString(4)
            ));
        });
    }

    private void query(
            Connection connection,
            String sql,
            StatementBinder binder,
            ResultConsumer consumer
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    consumer.accept(resultSet);
                }
            }
        }
    }

    private void query(
            Connection connection,
            String sql,
            ResultConsumer consumer
    ) throws SQLException {
        query(connection, sql, statement -> {
        }, consumer);
    }

    @FunctionalInterface
    private interface StatementBinder {

        void bind(PreparedStatement statement) throws SQLException;
    }

    @FunctionalInterface
    private interface ResultConsumer {

        void accept(ResultSet resultSet) throws SQLException;
    }
}
