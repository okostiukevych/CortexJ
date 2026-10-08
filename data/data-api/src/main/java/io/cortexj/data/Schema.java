package io.cortexj.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class Schema {

    private final List<String> names;
    private final List<ColType> types;

    public Schema(List<String> names, List<ColType> types) {
        if (names == null || types == null || names.size() != types.size()) {
            throw new IllegalArgumentException("schema needs parallel names and types");
        }
        this.names = Collections.unmodifiableList(new ArrayList<String>(names));
        this.types = Collections.unmodifiableList(new ArrayList<ColType>(types));
    }

    public List<String> names() {
        return names;
    }

    public List<ColType> types() {
        return types;
    }

    public int size() {
        return names.size();
    }

    public int indexOf(String name) {
        int index = names.indexOf(name);
        if (index < 0) {
            throw new IllegalArgumentException("unknown column: " + name);
        }
        return index;
    }
}
