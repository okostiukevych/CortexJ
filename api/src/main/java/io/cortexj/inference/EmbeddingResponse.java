package io.cortexj.inference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class EmbeddingResponse {

    private final List<float[]> vectors;

    public EmbeddingResponse(List<float[]> vectors) {
        List<float[]> copy = new ArrayList<float[]>(vectors.size());
        for (float[] vector : vectors) {
            copy.add(vector == null ? new float[0] : vector.clone());
        }
        this.vectors = Collections.unmodifiableList(copy);
    }

    public static EmbeddingResponse single(float[] vector) {
        List<float[]> vectors = new ArrayList<float[]>(1);
        vectors.add(vector);
        return new EmbeddingResponse(vectors);
    }

    public List<float[]> vectors() {
        return vectors;
    }

    public float[] singleVector() {
        return vectors.isEmpty() ? new float[0] : vectors.get(0);
    }
}
