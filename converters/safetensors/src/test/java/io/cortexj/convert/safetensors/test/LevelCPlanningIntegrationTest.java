package io.cortexj.convert.safetensors.test;

import io.cortexj.convert.safetensors.SafetensorsToGgufConverter;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelArtifact;
import io.cortexj.model.ModelDescriptor;
import io.cortexj.model.ModelReference;
import io.cortexj.model.TaskCapability;
import io.cortexj.planner.ExecutionPlan;
import io.cortexj.planner.ExecutionRequest;
import io.cortexj.runtime.DeviceType;
import io.cortexj.runtime.RuntimeDescriptor;
import io.cortexj.runtime.RuntimeId;
import io.cortexj.spi.repository.ModelRepositoryProvider;
import io.cortexj.spi.repository.RepositoryId;
import io.cortexj.spi.repository.ResolvedArtifact;
import io.cortexj.spi.repository.ResolvedModel;
import io.cortexj.spi.repository.ResolutionContext;
import io.cortexj.spi.runtime.ModelRuntime;
import io.cortexj.spi.runtime.RuntimeEvaluationRequest;
import io.cortexj.spi.runtime.RuntimeModel;
import io.cortexj.spi.runtime.RuntimeModelRequest;
import io.cortexj.spi.runtime.RuntimeOpenRequest;
import io.cortexj.spi.runtime.RuntimeProbeContext;
import io.cortexj.spi.runtime.RuntimeProbeResult;
import io.cortexj.spi.runtime.RuntimeProvider;
import io.cortexj.spi.runtime.RuntimeSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Level C with a production converter: a safetensors-only model plus a
 * GGUF-only runtime is only executable through the registered deterministic
 * conversion - and the planner says so.
 */
class LevelCPlanningIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void plannerRoutesSafetensorsModelThroughConversion() throws Exception {
        Path safetensors = writeMinimalSafetensors();
        ModelArtifact source = new ModelArtifact.Abstract(
                "weights.safetensors", ArtifactFormat.SAFETENSORS, Files.size(safetensors),
                null, null) {
        };

        RuntimeProvider ggufRuntime = new RuntimeProvider() {
            private final RuntimeId id = RuntimeId.of("gguf-runtime");

            @Override
            public RuntimeId id() {
                return id;
            }

            @Override
            public RuntimeDescriptor descriptor() {
                return RuntimeDescriptor.builder(id)
                        .version("1.0")
                        .format(ArtifactFormat.GGUF)
                        .device(DeviceType.CPU)
                        .task(TaskCapability.TEXT_GENERATION)
                        .maturity(RuntimeDescriptor.Maturity.STABLE)
                        .build();
            }

            @Override
            public RuntimeProbeResult probe(RuntimeProbeContext context) {
                return RuntimeProbeResult.available("gguf runtime ready");
            }

            @Override
            public RuntimeSupport evaluate(RuntimeEvaluationRequest request) {
                if (request.artifact() != null
                        && request.artifact().format() == ArtifactFormat.GGUF) {
                    return RuntimeSupport.full(DeviceType.CPU);
                }
                return RuntimeSupport.unsupported(io.cortexj.planner.PlanReason.negative(
                        io.cortexj.planner.ReasonCode.FORMAT_UNSUPPORTED,
                        "gguf runtime only executes GGUF"));
            }

            @Override
            public ModelRuntime open(RuntimeOpenRequest request) {
                return new ModelRuntime() {
                    @Override
                    public RuntimeId runtimeId() {
                        return id;
                    }

                    @Override
                    public RuntimeModel load(RuntimeModelRequest loadRequest) {
                        throw new UnsupportedOperationException("not needed for planning test");
                    }

                    @Override
                    public void close() {
                    }
                };
            }
        };

        ModelRepositoryProvider repository = new ModelRepositoryProvider() {
            @Override
            public RepositoryId id() {
                return RepositoryId.of("test-repo");
            }

            @Override
            public boolean supports(ModelReference reference) {
                return true;
            }

            @Override
            public ResolvedModel resolve(ModelReference reference, ResolutionContext context) {
                ModelDescriptor descriptor = ModelDescriptor.builder(reference.value())
                        .revision("rev1")
                        .task(TaskCapability.TEXT_GENERATION)
                        .artifact(source)
                        .build();
                final URI location = safetensors.toUri();
                ResolvedArtifact artifact = new ResolvedArtifact() {
                    @Override
                    public ModelArtifact artifact() {
                        return source;
                    }

                    @Override
                    public URI location() {
                        return location;
                    }

                    @Override
                    public java.util.Map<String, String> downloadHeaders() {
                        return Collections.emptyMap();
                    }
                };
                return new ResolvedModel(descriptor, "rev1", id(),
                        Collections.singletonList(artifact));
            }
        };

        io.cortexj.planner.internal.DefaultExecutionPlanner planner =
                new io.cortexj.planner.internal.DefaultExecutionPlanner(
                        new io.cortexj.planner.internal.PlannerInputs(
                                Collections.singletonList(ggufRuntime),
                                Collections.singletonList(repository),
                                Collections.<io.cortexj.spi.conversion.ModelConverter>singletonList(
                                        new SafetensorsToGgufConverter()),
                                new ArrayList<io.cortexj.spi.policy.ExecutionPolicy>(),
                                null,
                                new io.cortexj.hardware.EnvironmentProfile(
                                        "TestVendor", "TestJVM", 25, "25.0.0",
                                        "linux", "x86_64", 8,
                                        4L * 1024L * 1024L * 1024L,
                                        64L * 1024L * 1024L * 1024L,
                                        32L * 1024L * 1024L * 1024L, true, null),
                                new io.cortexj.hardware.HardwareProfile(
                                        Collections.singletonList(
                                                io.cortexj.runtime.DeviceDescriptor.of(
                                                        DeviceType.CPU, "cpu",
                                                        32L * 1024L * 1024L * 1024L)),
                                        64L * 1024L * 1024L * 1024L,
                                        false, false, false, false,
                                        Collections.<String>emptyList()),
                                null, null, null, null,
                                false, true, "0.1.0-test", 0L));

        ExecutionPlan plan = planner.plan(ExecutionRequest.forModel(
                ModelReference.of("safetensors-only/model")));
        assertTrue(plan.selected().requiresConversion(),
                "the plan must route through the registered converter");
        assertEquals("safetensors-to-gguf", plan.selected().conversion().converterId());
        assertEquals("gguf-runtime", plan.selected().runtimeId());
        assertTrue(plan.explanation().contains("conversion:"),
                "explanation must mention the deterministic conversion:\n" + plan.explanation());
    }

    private Path writeMinimalSafetensors() throws Exception {
        String headerJson = "{\"w\":{\"dtype\":\"F32\",\"shape\":[2],\"data_offsets\":[0,8]}}";
        byte[] json = headerJson.getBytes(StandardCharsets.UTF_8);
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        java.nio.ByteBuffer size = java.nio.ByteBuffer.allocate(8)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        size.putLong(json.length).flip();
        buffer.write(size.array());
        buffer.write(json);
        java.nio.ByteBuffer data = java.nio.ByteBuffer.allocate(8)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        data.putFloat(1.0f).putFloat(-1.0f);
        buffer.write(data.array());
        Path file = tempDir.resolve("weights.safetensors");
        Files.write(file, buffer.toByteArray());
        return file;
    }
}
