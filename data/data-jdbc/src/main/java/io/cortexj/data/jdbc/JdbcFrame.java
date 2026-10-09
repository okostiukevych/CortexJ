package io.cortexj.data.jdbc;

import io.cortexj.data.ColType;
import io.cortexj.data.Column;
import io.cortexj.data.Frame;
import io.cortexj.data.Schema;
import io.cortexj.data.SimpleColumn;
import io.cortexj.data.SimpleFrame;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * JDBC adapter (ADR-019): reads a query result as a CortexJ frame.
 * Types map from JDBC metadata: BIGINT/INTEGER → LONG, DOUBLE/FLOAT/REAL
 * → DOUBLE, BOOLEAN → BOOLEAN, everything else → STRING. Zero
 * third-party dependencies.
 */
public final class JdbcFrame {

    private JdbcFrame() {
    }

    public static Frame read(Connection connection, String query, Object... parameters)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(query)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                return read(resultSet);
            }
        }
    }

    public static Frame read(ResultSet resultSet) throws SQLException {
        ResultSetMetaData metadata = resultSet.getMetaData();
        int width = metadata.getColumnCount();
        List<String> names = new ArrayList<String>(width);
        List<ColType> types = new ArrayList<ColType>(width);
        for (int c = 1; c <= width; c++) {
            names.add(metadata.getColumnLabel(c));
            types.add(columnType(metadata.getColumnType(c)));
        }
        List<List<Object>> columns = new ArrayList<List<Object>>(width);
        for (int c = 0; c < width; c++) {
            columns.add(new ArrayList<Object>());
        }
        while (resultSet.next()) {
            for (int c = 1; c <= width; c++) {
                columns.get(c - 1).add(resultSet.getObject(c));
            }
        }
        List<Column<?>> frameColumns = new ArrayList<Column<?>>(width);
        for (int c = 0; c < width; c++) {
            frameColumns.add(typedColumn(names.get(c), types.get(c), columns.get(c)));
        }
        return new SimpleFrame(new Schema(names, types), frameColumns);
    }

    private static ColType columnType(int jdbcType) {
        return switch (jdbcType) {
            case java.sql.Types.BIGINT, java.sql.Types.INTEGER,
                 java.sql.Types.SMALLINT, java.sql.Types.TINYINT -> ColType.LONG;
            case java.sql.Types.DOUBLE, java.sql.Types.FLOAT,
                 java.sql.Types.REAL, java.sql.Types.DECIMAL,
                 java.sql.Types.NUMERIC -> ColType.DOUBLE;
            case java.sql.Types.BOOLEAN, java.sql.Types.BIT -> ColType.BOOLEAN;
            default -> ColType.STRING;
        };
    }

    private static Column<?> typedColumn(String name, ColType type, List<Object> values) {
        switch (type) {
            case LONG: {
                List<Long> typed = new ArrayList<Long>(values.size());
                for (Object value : values) {
                    typed.add(value == null ? null : ((Number) value).longValue());
                }
                return new SimpleColumn<Long>(name, Long.class, typed);
            }
            case DOUBLE: {
                List<Double> typed = new ArrayList<Double>(values.size());
                for (Object value : values) {
                    typed.add(value == null ? null : ((Number) value).doubleValue());
                }
                return new SimpleColumn<Double>(name, Double.class, typed);
            }
            case BOOLEAN: {
                List<Boolean> typed = new ArrayList<Boolean>(values.size());
                for (Object value : values) {
                    typed.add(value == null ? null : (Boolean) value);
                }
                return new SimpleColumn<Boolean>(name, Boolean.class, typed);
            }
            case STRING:
            default: {
                List<String> typed = new ArrayList<String>(values.size());
                for (Object value : values) {
                    typed.add(value == null ? null : String.valueOf(value));
                }
                return new SimpleColumn<String>(name, String.class, typed);
            }
        }
    }
}
