package io.cortexj.convert.gguf.test;

import io.cortexj.convert.gguf.internal.GgufBinaryReader;
import io.cortexj.convert.gguf.internal.GgufBinaryWriter;
import io.cortexj.convert.gguf.internal.GgufDocument;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelArtifact;
import io.cortexj.model.ModelReference;
import io.cortexj.model.TaskCapability;
import io.cortexj.planner.ExecutionPlan;
import io.cortexj.planner.ExecutionRequest;
import io.cortexj.planner.MemoryEstimate;
import io.cortexj.planner.PlanReason;
import io.cortexj.planner.ReasonCode;
import io.cortexj.planner.internal.DefaultExecutionPlanner;
import io.cortexj.planner.internal.PlannerInputs;
import io.cortexj.model.artifact.ArtifactInspectorRegistry;
import io.cortexj.runtime.DeviceType;
import io.cortexj.runtime.RuntimeDescriptor;
import io.cortexj.runtime.RuntimeId;
import io.cortexj.spi.policy.PlanningContext;
import io.cortexj.spi.repository.ModelRepositoryProvider;
import io.cortexj.spi.repository.RepositoryId;
import io.cortexj.spi.repository.ResolvedArtifact;
import io.cortexj.spi.repository.ResolvedModel;
import io.cortexj.spi.repository.ResolutionContext;
import io.cortexj.spi.runtime.RuntimeEvaluationRequest;
import io.cortexj.spi.runtime.RuntimeOpenRequest;
import io.cortexj.spi.runtime.RuntimeProbeContext;
import io.cortexj.spi.runtime.RuntimeProbeResult;
import io.cortexj.spi.runtime.RuntimeProvider;
import io.cortexj.spi.runtime.RuntimeSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerInspectionTest {

    private static final int HEADER_CONTEXT_LENGTH = 32768;

    @TempDir
    Path tempDir;

    static final class LlamaRuntimeProvider implements RuntimeProvider {

        private final RuntimeId id = RuntimeId.of("llamacpp");

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
            return RuntimeProbeResult.available("test");
        }

        @Override
        public RuntimeSupport evaluate(RuntimeEvaluationRequest request) {
            if (request.artifact() == null || request.artifact().format() != ArtifactFormat.GGUF) {
                return RuntimeSupport.unsupported(PlanReason.negative(ReasonCode.FORMAT_UNSUPPORTED,
                        "llamacpp executes GGUF only"));
            }
            return RuntimeSupport.full(DeviceType.CPU);
        }

        @Override
        public io.cortexj.spi.runtime.ModelRuntime open(RuntimeOpenRequest request) {
            throw new UnsupportedOperationException("test provider cannot open");
        }
    }

    /** GGUF fixture with a real header: llama.context_length = 32768, file_type = q4_k_m (15). */
    private Path writeGgufWithKnownHeader(String fileName) throws IOException {
        GgufDocument document = new GgufDocument();
        document.version = 3;
        document.metadata.add(new GgufDocument.KeyValue(
                "general.architecture", GgufBinaryReader.Types.STRING, "llama"));
        document.metadata.add(new GgufDocument.KeyValue(
                "general.file_type", GgufBinaryReader.Types.INT32, Integer.valueOf(15)));
        document.metadata.add(new GgufDocument.KeyValue(
                "llama.context_length", GgufBinaryReader.Types.UINT32,
                Long.valueOf(HEADER_CONTEXT_LENGTH)));
        document.tensors.add(new GgufDocument.TensorInfo(
                "weights", new long[]{4L, 1L}, GgufDocument.TENSOR_F32, 0L));
        Path file = tempDir.resolve(fileName);
        try (OutputStream raw = Files.newOutputStream(file);
             OutputStream buffered = new BufferedOutputStream(raw, 1 << 16)) {
            GgufBinaryWriter writer = new GgufBinaryWriter(buffered);
            document.writeHeader(writer);
            for (int i = 0; i < 4; i++) {
                writer.writeFloat32(1.0f);
            }
            writer.flush();
        }
        return file;
    }

    private ModelRepositoryProvider fileRepository(final Path file, final URI location) throws IOException {
        ModelArtifact artifact = new ModelArtifact.Abstract(
                file.getFileName().toString(), ArtifactFormat.GGUF, Files.size(file), null, null) {
        };
        io.cortexj.model.ModelDescriptor descriptor = io.cortexj.model.ModelDescriptor
                .builder("test/inspected-model")
                .revision("rev1")
                .task(TaskCapability.TEXT_GENERATION)
                .artifact(artifact)
                .build();
        ResolvedArtifact resolvedArtifact = new ResolvedArtifact() {
            @Override
            public ModelArtifact artifact() {
                return artifact;
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
        final ResolvedModel resolved = new ResolvedModel(descriptor, "rev1",
                RepositoryId.of("test-repo"), Collections.singletonList(resolvedArtifact));
        return new ModelRepositoryProvider() {
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
                return resolved;
            }
        };
    }

    private static List<RuntimeProvider> runtimeProviders() {
        List<RuntimeProvider> providers = new ArrayList<RuntimeProvider>();
        providers.add(new LlamaRuntimeProvider());
        return providers;
    }

    private static DefaultExecutionPlanner planner(ModelRepositoryProvider repository,
                                                   ArtifactInspectorRegistry inspector,
                                                   PlanningContext.CacheLookup cacheLookup) {
        return new DefaultExecutionPlanner(new PlannerInputs(
                runtimeProviders(),
                Arrays.<ModelRepositoryProvider>asList(repository),
                new ArrayList<io.cortexj.spi.conversion.ModelConverter>(),
                new ArrayList<io.cortexj.spi.policy.ExecutionPolicy>(),
                inspector,
                new io.cortexj.hardware.EnvironmentProfile(
                        "TestVendor", "TestJVM", 25, "25.0.0", "linux", "x86_64", 8,
                        4L * 1024L * 1024L * 1024L, 64L * 1024L * 1024L * 1024L,
                        32L * 1024L * 1024L * 1024L, true, null),
                new io.cortexj.hardware.HardwareProfile(
                        Collections.singletonList(io.cortexj.runtime.DeviceDescriptor.of(
                                DeviceType.CPU, "cpu", 32L * 1024L * 1024L * 1024L)),
                        64L * 1024L * 1024L * 1024L, false, false, false, false,
                        Collections.<String>emptyList()),
                cacheLookup,
                null,
                null,
                null,
                false,
                true,
                "0.1.0-test",
                0L));
    }

    @Test
    void planForLocalGgufReflectsRealHeaderContextAndQuantization() throws IOException {
        Path file = writeGgufWithKnownHeader("model.bin");
        DefaultExecutionPlanner planner = planner(fileRepository(file, file.toUri()),
                new ArtifactInspectorRegistry(), null);
        ExecutionPlan plan = planner.plan(
                ExecutionRequest.forModel(ModelReference.of("test/inspected-model")));

        assertEquals("q4_k_m", plan.selected().quantization(),
                "the quantization must come from the real GGUF header (file_type 15), "
                        + "not from the neutral filename");

        MemoryEstimate estimate = plan.selected().memoryEstimate();
        assertTrue(estimate.isKnown(), "the estimate must be known");
        Long kvCache = estimate.components().get("kvCache");
        assertTrue(kvCache != null && kvCache > 0, "the kvCache component must be present");
    }

    @Test
    void realHeaderContextLengthGroundsTheMemoryEstimate() throws IOException {
        Path file = writeGgufWithKnownHeader("model.bin");
        DefaultExecutionPlanner inspected = planner(fileRepository(file, file.toUri()),
                new ArtifactInspectorRegistry(), null);
        DefaultExecutionPlanner heuristic = planner(fileRepository(file, file.toUri()), null, null);

        ExecutionRequest request = ExecutionRequest.forModel(ModelReference.of("test/inspected-model"));
        Long kvCacheInspected = inspected.plan(request).selected().memoryEstimate()
                .components().get("kvCache");
        Long kvCacheHeuristic = heuristic.plan(request).selected().memoryEstimate()
                .components().get("kvCache");

        assertTrue(kvCacheInspected != null && kvCacheHeuristic != null);
        assertTrue(kvCacheInspected > kvCacheHeuristic,
                "the real header context length (" + HEADER_CONTEXT_LENGTH
                        + ") must ground the kvCache estimate: inspected=" + kvCacheInspected
                        + " heuristic=" + kvCacheHeuristic);
    }

    @Test
    void cachedArtifactIsInspectedThroughTheCacheLookup() throws IOException {
        Path file = writeGgufWithKnownHeader("model.bin");
        final java.nio.file.Path cachedPath = file;
        PlanningContext.CacheLookup cacheLookup = new PlanningContext.CacheLookup() {
            @Override
            public boolean isCached(String repositoryId, String revision, String artifactId, String checksum) {
                return true;
            }

            @Override
            public java.nio.file.Path cachedArtifactPath(String repositoryId, String revision,
                                                         String artifactId, String checksum) {
                return cachedPath;
            }
        };
        final URI remoteLocation = URI.create("http://127.0.0.1:1/models/model.bin");
        DefaultExecutionPlanner planner = planner(fileRepository(file, remoteLocation),
                new ArtifactInspectorRegistry(), cacheLookup);
        ExecutionPlan plan = planner.plan(
                ExecutionRequest.forModel(ModelReference.of("test/inspected-model")));

        assertEquals("q4_k_m", plan.selected().quantization(),
                "the inspection must reach the real headers through the cache path "
                        + "(the artifact location is remote, the filename is neutral)");
    }

    @Test
    void remoteArtifactWithoutCachePathFallsBackToHeuristics() throws IOException {
        Path file = writeGgufWithKnownHeader("model.bin");
        final URI remoteLocation = URI.create("http://127.0.0.1:1/models/model.bin");
        DefaultExecutionPlanner planner = planner(fileRepository(file, remoteLocation),
                new ArtifactInspectorRegistry(), null);
        ExecutionPlan plan = planner.plan(
                ExecutionRequest.forModel(ModelReference.of("test/inspected-model")));

        assertTrue(plan.selected().quantization() == null,
                "an uninspectable remote artifact must fall back to heuristics "
                        + "(no fabricated quantization)");
    }
}
