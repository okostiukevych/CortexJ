package io.cortexj.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SimpleFrame implements Frame {

    private final Schema schema;
    private final List<Column<?>> columns;

    public SimpleFrame(Schema schema, List<Column<?>> columns) {
        if (schema == null || columns == null) {
            throw new IllegalArgumentException("schema and columns must not be null");
        }
        if (schema.size() != columns.size()) {
            throw new IllegalArgumentException("schema and columns disagree on width");
        }
        int rows = -1;
        for (int i = 0; i < columns.size(); i++) {
            Column<?> column = columns.get(i);
            if (!column.name().equals(schema.names().get(i))) {
                throw new IllegalArgumentException("column order must follow the schema");
            }
            if (rows < 0) {
                rows = column.size();
            } else if (rows != column.size()) {
                throw new IllegalArgumentException("ragged columns are not frames");
            }
        }
        this.schema = schema;
        this.columns = Collections.unmodifiableList(new ArrayList<Column<?>>(columns));
    }

    @Override
    public Schema schema() {
        return schema;
    }

    @Override
    public int rowCount() {
        return columns.isEmpty() ? 0 : columns.get(0).size();
    }

    @Override
    public Column<?> column(String name) {
        return columns.get(schema.indexOf(name));
    }

    @Override
    public Frame head(int rows) {
        return slice(0, Math.max(0, Math.min(rows, rowCount())));
    }

    @Override
    public Frame select(String... names) {
        List<String> selected = new ArrayList<String>();
        List<ColType> types = new ArrayList<ColType>();
        List<Column<?>> kept = new ArrayList<Column<?>>();
        for (String name : names) {
            int index = schema.indexOf(name);
            selected.add(name);
            types.add(schema.types().get(index));
            kept.add(columns.get(index));
        }
        return new SimpleFrame(new Schema(selected, types), kept);
    }

    @Override
    public List<List<String>> rows() {
        List<List<String>> rows = new ArrayList<List<String>>(rowCount());
        for (int r = 0; r < rowCount(); r++) {
            List<String> row = new ArrayList<String>(columns.size());
            for (Column<?> column : columns) {
                Object value = column.get(r);
                row.add(value == null ? null : String.valueOf(value));
            }
            rows.add(Collections.unmodifiableList(row));
        }
        return Collections.unmodifiableList(rows);
    }

    private Frame slice(int from, int to) {
        List<Column<?>> sliced = new ArrayList<Column<?>>(columns.size());
        for (Column<?> column : columns) {
            sliced.add(sliceColumn(column, from, to));
        }
        return new SimpleFrame(schema, sliced);
    }

    private static <T> Column<T> sliceColumn(Column<T> column, int from, int to) {
        List<T> values = new ArrayList<T>(to - from);
        for (int i = from; i < to; i++) {
            values.add(column.get(i));
        }
        return new SimpleColumn<T>(column.name(), column.type(), values);
    }
}
