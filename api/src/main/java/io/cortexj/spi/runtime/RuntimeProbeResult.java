package io.cortexj.spi.runtime;

import io.cortexj.error.CortexJException;
import io.cortexj.runtime.RuntimeHealth;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RuntimeProbeResult {

    private final RuntimeHealth health;
    private final String message;
    private final Map<String, String> details;
    private final CortexJException error;

    private RuntimeProbeResult(RuntimeHealth health, String message, Map<String, String> details, CortexJException error) {
        this.health = health;
        this.message = message == null ? "" : message;
        this.details = Collections.unmodifiableMap(new LinkedHashMap<String, String>(details));
        this.error = error;
    }

    public static RuntimeProbeResult available(String message) {
        return new RuntimeProbeResult(RuntimeHealth.AVAILABLE, message, Collections.<String, String>emptyMap(), null);
    }

    public static RuntimeProbeResult available(String message, Map<String, String> details) {
        return new RuntimeProbeResult(RuntimeHealth.AVAILABLE, message, details, null);
    }

    public static RuntimeProbeResult degraded(String message) {
        return new RuntimeProbeResult(RuntimeHealth.DEGRADED, message, Collections.<String, String>emptyMap(), null);
    }

    public static RuntimeProbeResult misconfigured(String message, CortexJException error) {
        return new RuntimeProbeResult(RuntimeHealth.MISCONFIGURED, message, Collections.<String, String>emptyMap(), error);
    }

    public static RuntimeProbeResult unavailable(String message) {
        return new RuntimeProbeResult(RuntimeHealth.UNAVAILABLE, message, Collections.<String, String>emptyMap(), null);
    }

    public static RuntimeProbeResult unavailable(String message, Map<String, String> details) {
        return new RuntimeProbeResult(RuntimeHealth.UNAVAILABLE, message, details, null);
    }

    public static RuntimeProbeResult unavailable(String message, CortexJException error) {
        return new RuntimeProbeResult(RuntimeHealth.UNAVAILABLE, message, Collections.<String, String>emptyMap(), error);
    }

    public RuntimeHealth health() {
        return health;
    }

    public String message() {
        return message;
    }

    public Map<String, String> details() {
        return details;
    }

    public CortexJException error() {
        return error;
    }

    public boolean isUsable() {
        return health == RuntimeHealth.AVAILABLE || health == RuntimeHealth.DEGRADED;
    }
}
