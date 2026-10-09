package io.cortexj.data.tablesaw;

import io.cortexj.data.ColType;
import io.cortexj.data.Column;
import io.cortexj.data.Frame;
import io.cortexj.data.Schema;
import io.cortexj.data.SimpleColumn;
import io.cortexj.data.SimpleFrame;
import tech.tablesaw.api.ColumnType;
import tech.tablesaw.api.Table;
import tech.tablesaw.io.csv.CsvReadOptions;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Tablesaw adapter (ADR-019): reads a Tablesaw table as a CortexJ
 * frame. The library never leaks into data-api signatures.
 */
public final class TablesawFrame {

    private TablesawFrame() {
    }

    public static Frame read(Table table) {
        List<String> names = new ArrayList<String>();
        List<ColType> types = new ArrayList<ColType>();
        for (tech.tablesaw.columns.Column<?> column : table.columns()) {
            names.add(column.name());
            types.add(columnType(column.type()));
        }
        List<Column<?>> columns = new ArrayList<Column<?>>(names.size());
        for (int c = 0; c < names.size(); c++) {
            columns.add(typedColumn(names.get(c), types.get(c), table.column(c)));
        }
        return new SimpleFrame(new Schema(names, types), columns);
    }

    public static Frame readCsv(java.nio.file.Path path) throws IOException {
        return read(Table.read().csv(
                CsvReadOptions.builder(path.toFile())));
    }

    private static ColType columnType(ColumnType type) {
        if (type == ColumnType.INTEGER || type == ColumnType.LONG
                || type == ColumnType.SHORT) {
            return ColType.LONG;
        }
        if (type == ColumnType.DOUBLE || type == ColumnType.FLOAT) {
            return ColType.DOUBLE;
        }
        if (type == ColumnType.BOOLEAN) {
            return ColType.BOOLEAN;
        }
        return ColType.STRING;
    }

    private static Column<?> typedColumn(String name, ColType type,
                                         tech.tablesaw.columns.Column<?> source) {
        switch (type) {
            case LONG: {
                List<Long> values = new ArrayList<Long>(source.size());
                for (int r = 0; r < source.size(); r++) {
                    Object value = source.get(r);
                    values.add(value == null ? null : ((Number) value).longValue());
                }
                return new SimpleColumn<Long>(name, Long.class, values);
            }
            case DOUBLE: {
                List<Double> values = new ArrayList<Double>(source.size());
                for (int r = 0; r < source.size(); r++) {
                    Object value = source.get(r);
                    values.add(value == null ? null : ((Number) value).doubleValue());
                }
                return new SimpleColumn<Double>(name, Double.class, values);
            }
            case BOOLEAN: {
                List<Boolean> values = new ArrayList<Boolean>(source.size());
                for (int r = 0; r < source.size(); r++) {
                    Object value = source.get(r);
                    values.add(value == null ? null : (Boolean) value);
                }
                return new SimpleColumn<Boolean>(name, Boolean.class, values);
            }
            case STRING:
            default: {
                List<String> values = new ArrayList<String>(source.size());
                for (int r = 0; r < source.size(); r++) {
                    Object value = source.get(r);
                    values.add(value == null ? null : String.valueOf(value));
                }
                return new SimpleColumn<String>(name, String.class, values);
            }
        }
    }
}
