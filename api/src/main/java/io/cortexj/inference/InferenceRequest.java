package io.cortexj.inference;

import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.TaskCapability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InferenceRequest {

    private final List<String> inputs;
    private final Map<String, Object> parameters;
    private final Map<String, String> metadata;

    private InferenceRequest(Builder builder) {
        this.inputs = Collections.unmodifiableList(new ArrayList<String>(builder.inputs));
        this.parameters = Collections.unmodifiableMap(new LinkedHashMap<String, Object>(builder.parameters));
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<String, String>(builder.metadata));
    }

    public static Builder builder() {
        return new Builder();
    }

    public List<String> inputs() {
        return inputs;
    }

    public Map<String, Object> parameters() {
        return parameters;
    }

    public Map<String, String> metadata() {
        return metadata;
    }

    public String singleInput() {
        return inputs.isEmpty() ? "" : inputs.get(0);
    }

    public static final class Builder {

        private final List<String> inputs = new ArrayList<String>();
        private final Map<String, Object> parameters = new LinkedHashMap<String, Object>();
        private final Map<String, String> metadata = new LinkedHashMap<String, String>();

        public Builder input(String value) {
            if (value != null) {
                inputs.add(value);
            }
            return this;
        }

        public Builder inputs(Iterable<String> values) {
            for (String value : values) {
                input(value);
            }
            return this;
        }

        public Builder parameter(String key, Object value) {
            parameters.put(key, value);
            return this;
        }

        public Builder meta(String key, String value) {
            metadata.put(key, value);
            return this;
        }

        public InferenceRequest build() {
            return new InferenceRequest(this);
        }
    }
}
