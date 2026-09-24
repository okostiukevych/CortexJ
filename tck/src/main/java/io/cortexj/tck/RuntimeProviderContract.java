package io.cortexj.tck;

import io.cortexj.hardware.EnvironmentProfile;
import io.cortexj.hardware.HardwareProfile;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelDescriptor;
import io.cortexj.model.ModelReference;
import io.cortexj.model.TaskCapability;
import io.cortexj.planner.ExecutionRequest;
import io.cortexj.runtime.DeviceDescriptor;
import io.cortexj.runtime.DeviceType;
import io.cortexj.spi.runtime.RuntimeEvaluationRequest;
import io.cortexj.spi.runtime.RuntimeProbeContext;
import io.cortexj.spi.runtime.RuntimeProbeResult;
import io.cortexj.spi.runtime.RuntimeProvider;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public abstract class RuntimeProviderContract {

    protected abstract RuntimeProvider provider();

    public static EnvironmentProfile testEnvironment() {
        return new EnvironmentProfile(
                "TCKVendor", "TCKJvm", 21, "21.0.0", "linux", "x86_64",
                8, 2L * 1024L * 1024L * 1024L, 16L * 1024L * 1024L * 1024L,
                8L * 1024L * 1024L * 1024L, true, null);
    }

    public static HardwareProfile testHardware(boolean withGpu) {
        List<DeviceDescriptor> devices = new ArrayList<DeviceDescriptor>();
        devices.add(DeviceDescriptor.of(DeviceType.CPU, "tck-cpu", 16L * 1024L * 1024L * 1024L));
        if (withGpu) {
            devices.add(DeviceDescriptor.of(DeviceType.GPU, "tck-gpu", 8L * 1024L * 1024L * 1024L));
        }
        return new HardwareProfile(devices, 16L * 1024L * 1024L * 1024L,
                withGpu, false, false, false, Collections.<String>emptyList());
    }

    public static RuntimeProbeContext probeContext() {
        return new RuntimeProbeContext(testEnvironment(), testHardware(true), false, Collections.<String, String>emptyMap());
    }

    public static RuntimeEvaluationRequest evaluationRequest(ArtifactFormat format) {
        ModelDescriptor descriptor = ModelDescriptor.builder("tck/model")
                .revision("tckrev")
                .task(TaskCapability.TEXT_GENERATION)
                .build();
        return new RuntimeEvaluationRequest(
                descriptor,
                new io.cortexj.model.ModelArtifact() {
                    @Override
                    public String id() {
                        return "tck-artifact";
                    }

                    @Override
                    public ArtifactFormat format() {
                        return format;
                    }

                    @Override
                    public long sizeBytes() {
                        return 1024L;
                    }

                    @Override
                    public io.cortexj.model.Checksum checksum() {
                        return null;
                    }

                    @Override
                    public java.util.Map<String, String> metadata() {
                        return Collections.emptyMap();
                    }
                },
                testEnvironment(), testHardware(true),
                ExecutionRequest.forModel(ModelReference.of("tck/model")));
    }

    @Test
    public void descriptorIdentityIsConsistent() {
        assertNotNull(provider().id(), "runtime id must not be null");
        assertEquals(provider().id(), provider().descriptor().id(),
                "descriptor id must equal provider id");
        assertTrue(!provider().descriptor().devices().isEmpty(),
                "descriptor must declare at least one device");
        assertTrue(!provider().descriptor().tasks().isEmpty(),
                "descriptor must declare at least one task");
        assertTrue(provider().descriptor().minJavaFeatureVersion() >= 8,
                "descriptor must declare a minimum Java version");
        assertNotNull(provider().descriptor().maturity());
    }

    @Test
    public void probeNeverThrows() {
        RuntimeProbeResult result = provider().probe(probeContext());
        assertNotNull(result, "probe must always return a result");
        assertNotNull(result.health(), "probe must report a health state");
        if (!result.isUsable()) {
            assertNotNull(result.message(), "unusable probe must explain why");
        }
    }

    @Test
    public void evaluationRejectsUnsupportedFormatWithReason() {
        ArtifactFormat alien = alienFormatFor(provider());
        io.cortexj.spi.runtime.RuntimeSupport support = provider().evaluate(evaluationRequest(alien));
        assertNotNull(support);
        assertTrue(!support.isSupported(), "unsupported format must be reported as unsupported");
        assertTrue(!support.reasons().isEmpty(), "unsupported evaluation must carry reasons");
    }

    private static ArtifactFormat alienFormatFor(RuntimeProvider provider) {
        for (ArtifactFormat format : ArtifactFormat.values()) {
            if (format.isExecutableWeights() && !provider.descriptor().formats().contains(format)) {
                return format;
            }
        }
        return ArtifactFormat.UNKNOWN;
    }
}
