package io.cortexj.planner.test;

import io.cortexj.hardware.EnvironmentProfile;
import io.cortexj.hardware.HardwareProfile;
import io.cortexj.model.ModelArtifact;
import io.cortexj.model.ModelDescriptor;
import io.cortexj.model.ModelReference;
import io.cortexj.spi.repository.ModelRepositoryProvider;
import io.cortexj.spi.repository.RepositoryId;
import io.cortexj.spi.repository.ResolvedArtifact;
import io.cortexj.spi.repository.ResolvedModel;
import io.cortexj.spi.repository.ResolutionContext;
import io.cortexj.spi.repository.TokenSupplier;
import io.cortexj.observability.PlatformEventListener;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;

public final class TestRepositoryProvider implements ModelRepositoryProvider {

    private final RepositoryId id = RepositoryId.of("test-repo");
    private final List<ModelArtifact> artifacts = new ArrayList<ModelArtifact>();

    public TestRepositoryProvider(ModelArtifact... artifacts) {
        this.artifacts.addAll(Arrays.asList(artifacts));
    }

    public static ResolvedArtifact remoteArtifact(ModelArtifact artifact) {
        return new ResolvedArtifact() {
            @Override
            public ModelArtifact artifact() {
                return artifact;
            }

            @Override
            public URI location() {
                return URI.create("https://example.com/" + artifact.id());
            }

            @Override
            public java.util.Map<String, String> downloadHeaders() {
                return Collections.emptyMap();
            }
        };
    }

    public ResolvedModel resolve(ModelReference reference) {
        ModelDescriptor.Builder builder = ModelDescriptor.builder(reference.value())
                .revision("testrev")
                .task(io.cortexj.model.TaskCapability.TEXT_GENERATION);
        for (ModelArtifact artifact : artifacts) {
            builder.artifact(artifact);
        }
        List<ResolvedArtifact> resolvedArtifacts = new ArrayList<ResolvedArtifact>();
        for (ModelArtifact artifact : artifacts) {
            resolvedArtifacts.add(remoteArtifact(artifact));
        }
        return new ResolvedModel(builder.build(), "testrev", id, resolvedArtifacts);
    }

    @Override
    public RepositoryId id() {
        return id;
    }

    @Override
    public boolean supports(ModelReference reference) {
        return true;
    }

    @Override
    public ResolvedModel resolve(ModelReference reference, ResolutionContext context) {
        if (context.isOffline()) {
            throw new io.cortexj.error.ModelResolutionException("CORTEXJ-MODEL-1003", "offline");
        }
        return resolve(reference);
    }

    public static EnvironmentProfile environment(int featureVersion) {
        return new EnvironmentProfile(
                "TestVendor", "TestJVM", featureVersion, featureVersion + ".0.0",
                "linux", "x86_64", 8, 4L * 1024L * 1024L * 1024L,
                64L * 1024L * 1024L * 1024L, 32L * 1024L * 1024L * 1024L, true, null);
    }

    public static HardwareProfile hardware(boolean withGpu) {
        List<io.cortexj.runtime.DeviceDescriptor> devices = new ArrayList<io.cortexj.runtime.DeviceDescriptor>();
        devices.add(io.cortexj.runtime.DeviceDescriptor.of(io.cortexj.runtime.DeviceType.CPU, "cpu", 32L * 1024L * 1024L * 1024L));
        if (withGpu) {
            devices.add(io.cortexj.runtime.DeviceDescriptor.of(io.cortexj.runtime.DeviceType.GPU, "test-gpu", 24L * 1024L * 1024L * 1024L));
        }
        return new HardwareProfile(devices, 64L * 1024L * 1024L * 1024L,
                withGpu, false, false, false, Collections.<String>emptyList());
    }
}
