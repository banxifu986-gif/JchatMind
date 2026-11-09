package com.kama.jchatmind.typehandler;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedTypes;

import java.sql.Array;
import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Arrays;

@MappedTypes(double[].class)
public class PgDoubleArrayTypeHandler extends BaseTypeHandler<double[]> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, double[] parameter, JdbcType jdbcType) throws SQLException {
        Double[] boxed = Arrays.stream(parameter).boxed().toArray(Double[]::new);
        Array array = ps.getConnection().createArrayOf("float8", boxed);
        ps.setArray(i, array);
    }

    @Override
    public double[] getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parse(rs.getArray(columnName));
    }

    @Override
    public double[] getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parse(rs.getArray(columnIndex));
    }

    @Override
    public double[] getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parse(cs.getArray(columnIndex));
    }

    private double[] parse(Array array) throws SQLException {
        if (array == null) {
            return null;
        }
        Object[] values = (Object[]) array.getArray();
        double[] result = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = ((Number) values[i]).doubleValue();
        }
        return result;
    }
}