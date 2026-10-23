package io.cortexj.model;

public interface ModelReference {

    String value();

    static ModelReference of(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Model reference must not be empty");
        }
        final String normalized = value.trim();
        return new ModelReference() {
            @Override
            public String value() {
                return normalized;
            }

            @Override
            public String toString() {
                return normalized;
            }

            @Override
            public boolean equals(Object other) {
                return other instanceof ModelReference && normalized.equals(((ModelReference) other).value());
            }

            @Override
            public int hashCode() {
                return normalized.hashCode();
            }
        };
    }

    static ModelReference ofHuggingFace(String ownerAndName) {
        return of("hf:" + ownerAndName);
    }

    static ModelReference ofFile(String path) {
        // Path.toUri() produces a valid hierarchical file URI on every platform;
        // "file:" + path breaks on Windows (backslashes in an opaque URI part).
        return of(java.nio.file.Paths.get(path).toAbsolutePath().toUri().toString());
    }
}
