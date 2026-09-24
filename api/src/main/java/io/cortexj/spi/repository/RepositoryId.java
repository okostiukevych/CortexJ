package io.cortexj.spi.repository;

public final class RepositoryId {

    private final String value;

    private RepositoryId(String value) {
        this.value = value;
    }

    public static RepositoryId of(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Repository id must not be empty");
        }
        return new RepositoryId(value.trim().toLowerCase());
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof RepositoryId && value.equals(((RepositoryId) other).value);
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
