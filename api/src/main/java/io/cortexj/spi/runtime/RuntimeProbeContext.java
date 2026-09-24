package io.cortexj.spi.runtime;

import io.cortexj.hardware.EnvironmentProfile;
import io.cortexj.hardware.HardwareProfile;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RuntimeProbeContext {

    private final EnvironmentProfile environment;
    private final HardwareProfile hardware;
    private final boolean offline;
    private final Map<String, String> configuration;

    public RuntimeProbeContext(EnvironmentProfile environment,
                              HardwareProfile hardware,
                              boolean offline,
                              Map<String, String> configuration) {
        this.environment = environment;
        this.hardware = hardware;
        this.offline = offline;
        this.configuration = configuration == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(configuration));
    }

    public EnvironmentProfile environment() {
        return environment;
    }

    public HardwareProfile hardware() {
        return hardware;
    }

    public boolean isOffline() {
        return offline;
    }

    public Map<String, String> configuration() {
        return configuration;
    }

    public String configurationValue(String key) {
        return configuration.get(key);
    }
}
