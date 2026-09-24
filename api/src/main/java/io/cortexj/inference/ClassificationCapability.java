package io.cortexj.inference;

import java.util.LinkedHashMap;
import java.util.Map;

public interface ClassificationCapability {

    Map<String, Double> classify(String text);

    static ClassificationCapability ofLabels(final String... labels) {
        return new ClassificationCapability() {
            @Override
            public Map<String, Double> classify(String text) {
                Map<String, Double> result = new LinkedHashMap<String, Double>();
                for (String label : labels) {
                    result.put(label, Double.valueOf(0.0d));
                }
                return result;
            }
        };
    }
}
