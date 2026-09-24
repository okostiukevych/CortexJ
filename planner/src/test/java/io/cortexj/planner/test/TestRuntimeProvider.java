package io.cortexj.planner.test;

import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelArtifact;
import io.cortexj.model.TaskCapability;
import io.cortexj.planner.PlanReason;
import io.cortexj.planner.ReasonCode;
import io.cortexj.runtime.DeviceType;
import io.cortexj.runtime.RuntimeDescriptor;
import io.cortexj.runtime.RuntimeId;
import io.cortexj.spi.runtime.ModelRuntime;
import io.cortexj.spi.runtime.RuntimeEvaluationRequest;
import io.cortexj.spi.runtime.RuntimeOpenRequest;
import io.cortexj.spi.runtime.RuntimeProbeContext;
import io.cortexj.spi.runtime.RuntimeProbeResult;
import io.cortexj.spi.runtime.RuntimeProvider;
import io.cortexj.spi.runtime.RuntimeSupport;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class TestRuntimeProvider implements RuntimeProvider {

    private final RuntimeId id;
    private final Set<ArtifactFormat> formats = new LinkedHashSet<ArtifactFormat>();
    private final Set<TaskCapability> tasks = new LinkedHashSet<TaskCapability>();
    private final Set<DeviceType> devices = new LinkedHashSet<DeviceType>();
    private int minJavaFeatureVersion = 8;
    private RuntimeDescriptor.Maturity maturity = RuntimeDescriptor.Maturity.STABLE;
    private RuntimeProbeResult probeResult = RuntimeProbeResult.available("test");
    private boolean supportsEverything;

    public TestRuntimeProvider(String id, ArtifactFormat... formats) {
        this.id = RuntimeId.of(id);
        Collections.addAll(this.formats, formats);
    }

    public TestRuntimeProvider minJava(int featureVersion) {
        this.minJavaFeatureVersion = featureVersion;
        return this;
    }

    public TestRuntimeProvider devices(DeviceType... deviceTypes) {
        this.devices.clear();
        Collections.addAll(this.devices, deviceTypes);
        return this;
    }

    public TestRuntimeProvider task(TaskCapability task) {
        this.tasks.add(task);
        return this;
    }

    public TestRuntimeProvider maturity(RuntimeDescriptor.Maturity maturity) {
        this.maturity = maturity;
        return this;
    }

    public TestRuntimeProvider withProbe(RuntimeProbeResult result) {
        this.probeResult = result;
        return this;
    }

    public TestRuntimeProvider supportsEverything(boolean everything) {
        this.supportsEverything = everything;
        return this;
    }

    @Override
    public RuntimeId id() {
        return id;
    }

    @Override
    public RuntimeDescriptor descriptor() {
        RuntimeDescriptor.Builder builder = RuntimeDescriptor.builder(id)
                .version("1.0")
                .minJavaFeatureVersion(minJavaFeatureVersion)
                .maturity(maturity);
        for (ArtifactFormat format : formats) {
            builder.format(format);
        }
        for (TaskCapability task : tasks) {
            builder.task(task);
        }
        for (DeviceType device : devices) {
            builder.device(device);
        }
        return builder.build();
    }

    @Override
    public RuntimeProbeResult probe(RuntimeProbeContext context) {
        return probeResult;
    }

    @Override
    public RuntimeSupport evaluate(RuntimeEvaluationRequest request) {
        if (request.artifact() == null) {
            return RuntimeSupport.unsupported(PlanReason.negative(ReasonCode.ARTIFACT_NOT_FOUND, "no artifact"));
        }
        if (supportsEverything || formats.contains(request.artifact().format())) {
            if (request.artifact().format() == ArtifactFormat.REMOTE && devices.contains(DeviceType.REMOTE)) {
                return RuntimeSupport.full(DeviceType.REMOTE);
            }
            DeviceType device = devices.contains(DeviceType.CPU)
                    ? DeviceType.CPU
                    : devices.isEmpty() ? DeviceType.CPU : devices.iterator().next();
            return RuntimeSupport.full(device);
        }
        return RuntimeSupport.unsupported(PlanReason.negative(ReasonCode.FORMAT_UNSUPPORTED,
                "runtime " + id.value() + " does not support " + request.artifact().format()));
    }

    @Override
    public ModelRuntime open(RuntimeOpenRequest request) {
        throw new UnsupportedOperationException("test provider cannot open");
    }

    public static ModelArtifact artifact(String id, ArtifactFormat format, long sizeBytes, String quantization) {
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        if (quantization != null) {
            metadata.put("quantization", quantization);
        }
        return new ModelArtifact.Abstract(id, format, sizeBytes, null, metadata) {
        };
    }
}
