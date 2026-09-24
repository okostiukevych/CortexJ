package io.cortexj.inference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class InferenceResponse {

    private final List<String> outputs;
    private final Map<String, Object> metadata;

    public InferenceResponse(List<String> outputs, Map<String, Object> metadata) {
        this.outputs = Collections.unmodifiableList(new ArrayList<String>(outputs));
        this.metadata = metadata == null
                ? Collections.<String, Object>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Object>(metadata));
    }

    public static InferenceResponse single(String output) {
        List<String> outputs = new ArrayList<String>(1);
        outputs.add(output == null ? "" : output);
        return new InferenceResponse(outputs, Collections.<String, Object>emptyMap());
    }

    public List<String> outputs() {
        return outputs;
    }

    public String singleOutput() {
        return outputs.isEmpty() ? "" : outputs.get(0);
    }

    public Map<String, Object> metadata() {
        return metadata;
    }
}
