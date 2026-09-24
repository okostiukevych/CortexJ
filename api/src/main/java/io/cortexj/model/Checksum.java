package io.cortexj.model;

import java.util.Locale;

public final class Checksum {

    private final String algorithm;
    private final String value;

    private Checksum(String algorithm, String value) {
        this.algorithm = algorithm.toLowerCase(Locale.ROOT);
        this.value = value.toLowerCase(Locale.ROOT);
    }

    public static Checksum of(String algorithm, String value) {
        if (algorithm == null || algorithm.trim().isEmpty()) {
            throw new IllegalArgumentException("Checksum algorithm must not be empty");
        }
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Checksum value must not be empty");
        }
        return new Checksum(algorithm.trim(), value.trim());
    }

    public static Checksum sha256(String value) {
        return of("sha256", value);
    }

    public String algorithm() {
        return algorithm;
    }

    public String value() {
        return value;
    }

    public boolean matches(Checksum other) {
        return other != null && algorithm.equals(other.algorithm) && value.equals(other.value);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Checksum && matches((Checksum) other);
    }

    @Override
    public int hashCode() {
        return 31 * algorithm.hashCode() + value.hashCode();
    }

    @Override
    public String toString() {
        return algorithm + ":" + value;
    }
}
