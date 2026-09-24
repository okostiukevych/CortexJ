package io.cortexj.spi.conversion;

public final class ConverterId {

    private final String value;

    private ConverterId(String value) {
        this.value = value;
    }

    public static ConverterId of(String value) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException("Converter id must not be empty");
        }
        return new ConverterId(value.trim().toLowerCase());
    }

    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ConverterId && value.equals(((ConverterId) other).value);
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
