package io.cortexj.runtime;

public final class RuntimeHealthReport {

    private final RuntimeId runtimeId;
    private final RuntimeHealth health;
    private final String message;

    public RuntimeHealthReport(RuntimeId runtimeId, RuntimeHealth health, String message) {
        this.runtimeId = runtimeId;
        this.health = health;
        this.message = message == null ? "" : message;
    }

    public RuntimeId runtimeId() {
        return runtimeId;
    }

    public RuntimeHealth health() {
        return health;
    }

    public String message() {
        return message;
    }

    @Override
    public String toString() {
        return runtimeId + ": " + health.name().toLowerCase()
                + (message.isEmpty() ? "" : " (" + message + ")");
    }
}
