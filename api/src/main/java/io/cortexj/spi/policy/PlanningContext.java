package io.cortexj.spi.policy;

import io.cortexj.hardware.EnvironmentProfile;
import io.cortexj.hardware.HardwareProfile;
import io.cortexj.planner.ExecutionRequest;

public final class PlanningContext {

    public interface CacheLookup {
        boolean isCached(String artifactId, String checksum);
    }

    private final EnvironmentProfile environment;
    private final HardwareProfile hardware;
    private final ExecutionRequest request;
    private final CacheLookup cacheLookup;

    public PlanningContext(EnvironmentProfile environment, HardwareProfile hardware, ExecutionRequest request, CacheLookup cacheLookup) {
        this.environment = environment;
        this.hardware = hardware;
        this.request = request;
        this.cacheLookup = cacheLookup == null ? new CacheLookup() {
            @Override
            public boolean isCached(String artifactId, String checksum) {
                return false;
            }
        } : cacheLookup;
    }

    public EnvironmentProfile environment() {
        return environment;
    }

    public HardwareProfile hardware() {
        return hardware;
    }

    public ExecutionRequest request() {
        return request;
    }

    public CacheLookup cacheLookup() {
        return cacheLookup;
    }
}
