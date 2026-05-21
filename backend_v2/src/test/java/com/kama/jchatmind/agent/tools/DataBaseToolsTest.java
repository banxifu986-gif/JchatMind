package com.kama.jchatmind.agent.tools;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;

import javax.sql.DataSource;
import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataBaseToolsTest {

    @Test
    void shouldReturnExpectedName() {
        DataBaseTools tools = new DataBaseTools(null);
        assertEquals("dataBaseTool", tools.getName());
    }

    @Test
    void shouldReturnNonEmptyDescription() {
        DataBaseTools tools = new DataBaseTools(null);
        assertNotNull(tools.getDescription());
    }

    @Test
    void shouldBeOptionalToolType() {
        DataBaseTools tools = new DataBaseTools(null);
        assertEquals(ToolType.OPTIONAL, tools.getType());
    }

    @Test
    void shouldRejectNonSelectSql() {
        DataBaseTools tools = new DataBaseTools(null);

        String result = tools.query("INSERT INTO users VALUES (1, 'test')");
        assertTrue(result.contains("仅支持 SELECT 查询"));

        result = tools.query("UPDATE users SET name = 'test'");
        assertTrue(result.contains("仅支持 SELECT 查询"));

        result = tools.query("DELETE FROM users WHERE id = 1");
        assertTrue(result.contains("仅支持 SELECT 查询"));

        result = tools.query("DROP TABLE users");
        assertTrue(result.contains("仅支持 SELECT 查询"));

        result = tools.query("CREATE TABLE test (id INT)");
        assertTrue(result.contains("仅支持 SELECT 查询"));
    }

    @Test
    void shouldTrimAndAcceptSelectWithLeadingWhitespace() {
        JdbcTemplate stub = newJdbcTemplateWithOneRow();
        DataBaseTools tools = new DataBaseTools(stub);

        String result = tools.query("   SELECT 1 AS num");
        assertTrue(result.contains("查询结果"));
    }

    @Test
    void shouldReturnErrorMessageWhenQueryFails() {
        JdbcTemplate failing = new FailingJdbcTemplate();
        DataBaseTools tools = new DataBaseTools(failing);

        String result = tools.query("SELECT * FROM nonexistent");
        assertTrue(result.contains("错误：操作失败"));
    }

    @Test
    void shouldFormatQueryResultsAsTable() {
        JdbcTemplate stub = newJdbcTemplateWithOneRow();
        DataBaseTools tools = new DataBaseTools(stub);

        String result = tools.query("SELECT id, name FROM users");

        assertTrue(result.contains("查询结果"));
        assertTrue(result.contains("id"));
        assertTrue(result.contains("name"));
        assertTrue(result.contains("1"));
        assertTrue(result.contains("张三"));
    }

    private static JdbcTemplate newJdbcTemplateWithOneRow() {
        return new JdbcTemplate(new StubDataSource()) {
            @Override
            @SuppressWarnings("unchecked")
            public <T> T query(String sql, ResultSetExtractor<T> rse) {
                ResultSet rs = (ResultSet) Proxy.newProxyInstance(
                        ResultSet.class.getClassLoader(),
                        new Class[]{ResultSet.class},
                        new OneRowResultSetHandler()
                );
                try {
                    return (T) rse.extractData(rs);
                } catch (java.sql.SQLException e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }

    private static class FailingJdbcTemplate extends JdbcTemplate {
        FailingJdbcTemplate() {
            super(new StubDataSource());
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T query(String sql, ResultSetExtractor<T> rse) {
            throw new RuntimeException("模拟数据库异常");
        }
    }

    /**
     * 仅拦截 DataBaseTools.query() 实际调用的 ResultSet 方法。
     * 其余方法由 Proxy 自动抛出 UndeclaredThrowableException，能及早暴露未预期调用。
     */
    private static class OneRowResultSetHandler implements InvocationHandler {
        private boolean hasNext = true;

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "next" -> {
                    if (hasNext) {
                        hasNext = false;
                        yield true;
                    }
                    yield false;
                }
                case "getObject" -> {
                    int columnIndex = (int) args[0];
                    if (columnIndex == 1) yield 1;
                    if (columnIndex == 2) yield "张三";
                    yield null;
                }
                case "getMetaData" -> Proxy.newProxyInstance(
                        ResultSetMetaData.class.getClassLoader(),
                        new Class[]{ResultSetMetaData.class},
                        new TwoColumnMetaDataHandler()
                );
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
    }

    private static class TwoColumnMetaDataHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return switch (method.getName()) {
                case "getColumnCount" -> 2;
                case "getColumnName" -> {
                    int column = (int) args[0];
                    if (column == 1) yield "id";
                    if (column == 2) yield "name";
                    yield null;
                }
                default -> throw new UnsupportedOperationException(method.getName());
            };
        }
    }

    private static class StubDataSource implements DataSource {
        @Override
        public Connection getConnection() { throw new UnsupportedOperationException(); }

        @Override
        public Connection getConnection(String username, String password) { throw new UnsupportedOperationException(); }

        @Override
        public PrintWriter getLogWriter() { return null; }

        @Override
        public void setLogWriter(PrintWriter out) {}

        @Override
        public void setLoginTimeout(int seconds) {}

        @Override
        public int getLoginTimeout() { return 0; }

        @Override
        public Logger getParentLogger() { return null; }

        @Override
        public <T> T unwrap(Class<T> iface) { return null; }

        @Override
        public boolean isWrapperFor(Class<?> iface) { return false; }

        @Override
        public java.sql.ConnectionBuilder createConnectionBuilder() { throw new UnsupportedOperationException(); }
    }
}
