package io.cortexj.diagnostics;

public final class Recommendation {

    private final String code;
    private final String message;

    public Recommendation(String code, String message) {
        this.code = code;
        this.message = message;
    }

    public String code() {
        return code;
    }

    public String message() {
        return message;
    }

    @Override
    public String toString() {
        return "[" + code + "] " + message;
    }
}
