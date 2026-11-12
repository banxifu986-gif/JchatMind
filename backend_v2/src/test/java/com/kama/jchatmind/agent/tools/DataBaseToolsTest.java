package com.kama.jchatmind.agent.tools;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DataBaseToolsTest {

    @Test
    void shouldReturnExpectedName() {
        DataBaseTools tools = new DataBaseTools(mock(DataSource.class));
        assertThat(tools.getName()).isEqualTo("dataBaseTool");
    }

    @Test
    void shouldReturnNonEmptyDescription() {
        DataBaseTools tools = new DataBaseTools(mock(DataSource.class));
        assertThat(tools.getDescription()).isNotEmpty();
    }

    @Test
    void shouldBeOptionalToolType() {
        DataBaseTools tools = new DataBaseTools(mock(DataSource.class));
        assertThat(tools.getType()).isEqualTo(ToolType.OPTIONAL);
    }

    @Test
    void shouldRejectNonSelectSqlWithoutTouchingJdbcTemplate() throws java.sql.SQLException {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new RuntimeException("模拟数据库异常"));
        DataBaseTools tools = new DataBaseTools(dataSource);

        assertThat(tools.query("INSERT INTO users VALUES (1, 'test')")).contains("仅支持 SELECT 查询");
        assertThat(tools.query("UPDATE users SET name = 'test'")).contains("仅支持 SELECT 查询");
        assertThat(tools.query("DELETE FROM users WHERE id = 1")).contains("仅支持 SELECT 查询");
        assertThat(tools.query("DROP TABLE users")).contains("仅支持 SELECT 查询");
        assertThat(tools.query("CREATE TABLE test (id INT)")).contains("仅支持 SELECT 查询");

        verify(dataSource, never()).getConnection();
    }

    @Test
    void shouldExecuteSelectAndReturnFormattedTable() {
        DataBaseTools tools = new DataBaseTools(newOneRowDataSource());

        String result = tools.query("SELECT id, name FROM users");

        assertThat(result).contains("查询结果");
        assertThat(result).contains("id");
        assertThat(result).contains("name");
        assertThat(result).contains("张三");
    }

    @Test
    void shouldTrimAndAcceptSelectWithLeadingWhitespace() {
        DataBaseTools tools = new DataBaseTools(newOneRowDataSource());

        String result = tools.query("   SELECT id, name FROM users");

        assertThat(result).contains("查询结果");
    }

    @Test
    void shouldReturnErrorMessageWhenQueryFails() throws java.sql.SQLException {
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new RuntimeException("模拟数据库异常"));
        DataBaseTools tools = new DataBaseTools(dataSource);

        String result = tools.query("SELECT * FROM nonexistent");

        assertThat(result).contains("错误：操作失败");
    }

    /**
     * 拦截 query 实际调用的 JDBC 调用链：DataSource -> Connection -> Statement -> 单行 ResultSet。
     * 其余方法由 Mockito 默认返回空值，未预期的行为会在断言处暴露。
     */
    private static DataSource newOneRowDataSource() {
        try {
            DataSource dataSource = mock(DataSource.class);
            Connection connection = mock(Connection.class);
            Statement statement = mock(Statement.class);
            ResultSet resultSet = mock(ResultSet.class);
            ResultSetMetaData metaData = mock(ResultSetMetaData.class);

            when(dataSource.getConnection()).thenReturn(connection);
            when(connection.createStatement()).thenReturn(statement);
            when(statement.executeQuery(anyString())).thenReturn(resultSet);
            when(resultSet.getMetaData()).thenReturn(metaData);
            when(metaData.getColumnCount()).thenReturn(2);
            when(metaData.getColumnName(1)).thenReturn("id");
            when(metaData.getColumnName(2)).thenReturn("name");
            when(resultSet.next()).thenReturn(true, false);
            when(resultSet.getObject(1)).thenReturn(1);
            when(resultSet.getObject(2)).thenReturn("张三");

            return dataSource;
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }
}
