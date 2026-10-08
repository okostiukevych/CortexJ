package io.cortexj.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SimpleColumn<T> implements Column<T> {

    private final String name;
    private final Class<T> type;
    private final List<T> values;

    public SimpleColumn(String name, Class<T> type, List<T> values) {
        if (name == null || type == null || values == null) {
            throw new IllegalArgumentException("column name, type and values must not be null");
        }
        this.name = name;
        this.type = type;
        this.values = Collections.unmodifiableList(new ArrayList<T>(values));
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public Class<T> type() {
        return type;
    }

    @Override
    public int size() {
        return values.size();
    }

    @Override
    public T get(int index) {
        return values.get(index);
    }
}
