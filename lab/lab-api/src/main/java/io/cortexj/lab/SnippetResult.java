package io.cortexj.lab;

import java.util.Collections;
import java.util.List;

/**
 * Result of one lab evaluation: the printed value (empty when the snippet
 * produces none) plus the collected errors (empty on success).
 */
public final class SnippetResult {

    private final String value;
    private final List<String> errors;

    public SnippetResult(String value, List<String> errors) {
        this.value = value == null ? "" : value;
        this.errors = errors == null
                ? Collections.<String>emptyList() : errors;
    }

    public String value() {
        return value;
    }

    public List<String> errors() {
        return errors;
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }
}
