package io.cortexj.inference;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class EmbeddingRequest {

    private final List<String> texts;

    private EmbeddingRequest(List<String> texts) {
        this.texts = Collections.unmodifiableList(new ArrayList<String>(texts));
    }

    public static EmbeddingRequest text(String text) {
        List<String> texts = new ArrayList<String>(1);
        texts.add(text == null ? "" : text);
        return new EmbeddingRequest(texts);
    }

    public static EmbeddingRequest texts(List<String> texts) {
        return new EmbeddingRequest(texts);
    }

    public List<String> texts() {
        return texts;
    }

    public String singleText() {
        return texts.isEmpty() ? "" : texts.get(0);
    }
}
