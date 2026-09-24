package io.cortexj.runtime;

public final class RuntimeId {

    private final String value;

    private RuntimeId(String value) {
        this.value = value;
    }

    public static RuntimeId of(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Runtime id must not be empty");
        }
        return new RuntimeId(value.trim().toLowerCase());
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RuntimeId && value.equals(((RuntimeId) other).value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
