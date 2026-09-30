package io.cortexj.spi.policy;

import io.cortexj.hardware.EnvironmentProfile;
import io.cortexj.hardware.HardwareProfile;
import io.cortexj.planner.ExecutionRequest;

public final class PlanningContext {

    public interface CacheLookup {
        boolean isCached(String repositoryId, String revision, String artifactId, String checksum);

        /**
         * Local cache path for the artifact, or null when unavailable or not
         * cached. Lets the planner inspect real artifact headers for cached
         * (downloaded) models instead of relying on filename heuristics.
         */
        default java.nio.file.Path cachedArtifactPath(String repositoryId, String revision,
                                                      String artifactId, String checksum) {
            return null;
        }
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
            public boolean isCached(String repositoryId, String revision, String artifactId, String checksum) {
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
