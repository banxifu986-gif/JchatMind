package com.kama.jchatmind.typehandler;

import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PgVectorTypeHandlerTest {

    @Test
    void shouldSerializeVectorToPgVectorLiteral() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        PgVectorTypeHandler handler = new PgVectorTypeHandler();

        handler.setNonNullParameter(ps, 1, new float[]{1.0f, 2.5f}, JdbcType.OTHER);

        verify(ps).setObject(1, "[1.0,2.5]", Types.OTHER);
    }

    @Test
    void shouldSerializeSingleElementVectorWithoutSeparator() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        PgVectorTypeHandler handler = new PgVectorTypeHandler();

        handler.setNonNullParameter(ps, 1, new float[]{3.25f}, JdbcType.OTHER);

        verify(ps).setObject(1, "[3.25]", Types.OTHER);
    }

    @Test
    void shouldSerializeEmptyVectorToEmptyBrackets() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        PgVectorTypeHandler handler = new PgVectorTypeHandler();

        handler.setNonNullParameter(ps, 1, new float[0], JdbcType.OTHER);

        verify(ps).setObject(1, "[]", Types.OTHER);
    }

    @Test
    void shouldParsePgVectorLiteralToFloatArray() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("embedding")).thenReturn("[1.0,2.5]");
        PgVectorTypeHandler handler = new PgVectorTypeHandler();

        float[] result = handler.getNullableResult(rs, "embedding");

        assertThat(result).containsExactly(1.0f, 2.5f);
    }

    @Test
    void shouldParsePgVectorLiteralByColumnIndex() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(2)).thenReturn("[1.0,2.5]");
        PgVectorTypeHandler handler = new PgVectorTypeHandler();

        float[] result = handler.getNullableResult(rs, 2);

        assertThat(result).containsExactly(1.0f, 2.5f);
    }

    @Test
    void shouldReturnNullForNullColumnValue() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("embedding")).thenReturn(null);
        PgVectorTypeHandler handler = new PgVectorTypeHandler();

        float[] result = handler.getNullableResult(rs, "embedding");

        assertThat(result).isNull();
    }

    @Test
    void shouldParseBlankLiteralToEmptyArray() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("embedding")).thenReturn("[]").thenReturn("");
        PgVectorTypeHandler handler = new PgVectorTypeHandler();

        float[] bracketOnly = handler.getNullableResult(rs, "embedding");
        float[] emptyString = handler.getNullableResult(rs, "embedding");

        assertThat(bracketOnly).isEmpty();
        assertThat(emptyString).isEmpty();
    }

    @Test
    void shouldThrowNumberFormatExceptionForNonNumericLiteral() throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("embedding")).thenReturn("[abc]");
        PgVectorTypeHandler handler = new PgVectorTypeHandler();

        assertThatThrownBy(() -> handler.getNullableResult(rs, "embedding"))
                .isInstanceOf(NumberFormatException.class);
    }

    @Test
    void shouldSetNullForNullVectorOnWrite() throws SQLException {
        PreparedStatement ps = mock(PreparedStatement.class);
        PgVectorTypeHandler handler = new PgVectorTypeHandler();

        handler.setParameter(ps, 1, null, JdbcType.OTHER);

        verify(ps).setNull(1, Types.OTHER);
    }
}
