package io.cortexj.stream;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class Token {

    private final String text;
    private final int index;
    private final Map<String, String> metadata;

    public Token(String text, int index, Map<String, String> metadata) {
        this.text = text == null ? "" : text;
        this.index = index;
        this.metadata = metadata == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(metadata));
    }

    public String text() {
        return text;
    }

    public int index() {
        return index;
    }

    public Map<String, String> metadata() {
        return metadata;
    }
}
