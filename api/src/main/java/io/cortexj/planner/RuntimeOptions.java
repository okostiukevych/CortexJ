package io.cortexj.planner;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RuntimeOptions {

    private final Map<String, Map<String, Object>> optionsByRuntime = new LinkedHashMap<String, Map<String, Object>>();

    private RuntimeOptions() {
    }

    public static OptionsForRuntime forRuntime(String runtimeId) {
        return new OptionsForRuntime(new RuntimeOptions(), runtimeId);
    }

    public static RuntimeOptions empty() {
        return new RuntimeOptions();
    }

    void merge(String runtimeId, Map<String, Object> values) {
        Map<String, Object> existing = optionsByRuntime.get(runtimeId);
        if (existing == null) {
            optionsByRuntime.put(runtimeId, new LinkedHashMap<String, Object>(values));
        } else {
            existing.putAll(values);
        }
    }

    public Map<String, Map<String, Object>> asMap() {
        Map<String, Map<String, Object>> copy = new LinkedHashMap<String, Map<String, Object>>();
        for (Map.Entry<String, Map<String, Object>> entry : optionsByRuntime.entrySet()) {
            copy.put(entry.getKey(), Collections.unmodifiableMap(new LinkedHashMap<String, Object>(entry.getValue())));
        }
        return copy;
    }

    public static final class OptionsForRuntime {

        private final RuntimeOptions owner;
        private final String runtimeId;
        private final Map<String, Object> values = new LinkedHashMap<String, Object>();

        OptionsForRuntime(RuntimeOptions owner, String runtimeId) {
            this.owner = owner;
            this.runtimeId = runtimeId;
        }

        public OptionsForRuntime put(String key, Object value) {
            if (key != null && value != null) {
                values.put(key, value);
            }
            return this;
        }

        public RuntimeOptions build() {
            owner.merge(runtimeId, values);
            return owner;
        }
    }
}
