package io.cortexj.train.djl.models;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Loaded base weights: tensor name -> decoded float[] (row-major HF layout). */
public final class LoadedWeights {

    private final Map<String, float[]> tensors;

    LoadedWeights(Map<String, float[]> tensors) {
        this.tensors = new LinkedHashMap<String, float[]>(tensors);
    }

    public Set<String> names() {
        return Collections.unmodifiableSet(tensors.keySet());
    }

    public boolean has(String name) {
        return tensors.containsKey(name);
    }

    public float[] tensor(String name) {
        float[] values = tensors.get(name);
        if (values == null) {
            throw new IllegalStateException("tensor not loaded: " + name);
        }
        return values;
    }
}
