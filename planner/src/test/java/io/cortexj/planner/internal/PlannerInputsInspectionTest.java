package io.cortexj.planner.internal;

import io.cortexj.ErrorCode;
import io.cortexj.error.CortexJException;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelArtifact;
import io.cortexj.model.ModelDescriptor;
import io.cortexj.model.ModelReference;
import io.cortexj.model.TaskCapability;
import io.cortexj.model.artifact.ArtifactInspectorRegistry;
import io.cortexj.planner.ExecutionRequest;
import io.cortexj.planner.test.TestRepositoryProvider;
import io.cortexj.planner.test.TestRuntimeProvider;
import io.cortexj.runtime.DeviceType;
import io.cortexj.spi.artifact.ArtifactInspection;
import io.cortexj.spi.artifact.ArtifactInspector;
import io.cortexj.spi.conversion.ModelConverter;
import io.cortexj.spi.policy.ExecutionPolicy;
import io.cortexj.spi.repository.ModelRepositoryProvider;
import io.cortexj.spi.repository.RepositoryId;
import io.cortexj.spi.repository.ResolvedArtifact;
import io.cortexj.spi.repository.ResolvedModel;
import io.cortexj.spi.repository.ResolutionContext;
import io.cortexj.spi.runtime.RuntimeProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlannerInputsInspectionTest {

    private static final ModelReference MODEL = ModelReference.of("test/inspected-model");

    @TempDir
    Path tempDir;

    private static final class ThrowingInspector implements ArtifactInspector {

        @Override
        public boolean supports(ArtifactFormat format) {
            return true;
        }

        @Override
        public ArtifactInspection inspect(Path artifact) {
            throw new StackOverflowError("malicious gguf");
        }
    }

    private Path ggufFile() throws Exception {
        Path file = tempDir.resolve("model-q4_k.gguf");
        Files.write(file, new byte[] {1, 2, 3, 4});
        return file;
    }

    private static ModelArtifact artifactFor(Path file) throws Exception {
        return new ModelArtifact.Abstract(file.getFileName().toString(), ArtifactFormat.GGUF,
                Files.size(file), null, null) {
        };
    }

    private static ResolvedArtifact resolvedArtifact(final ModelArtifact artifact, final URI location) {
        return new ResolvedArtifact() {
            @Override
            public ModelArtifact artifact() {
                return artifact;
            }

            @Override
            public URI location() {
                return location;
            }

            @Override
            public Map<String, String> downloadHeaders() {
                return Collections.emptyMap();
            }
        };
    }

    private static ArtifactInspectorRegistry throwingRegistry() {
        return new ArtifactInspectorRegistry(
                Collections.<ArtifactInspector>singletonList(new ThrowingInspector()));
    }

    private PlannerInputs inputs(ModelRepositoryProvider repository, ArtifactInspectorRegistry registry) {
        return inputs(Arrays.<RuntimeProvider>asList(
                (RuntimeProvider) new TestRuntimeProvider("llamacpp", ArtifactFormat.GGUF)
                        .devices(DeviceType.CPU)
                        .task(TaskCapability.TEXT_GENERATION)),
                repository, registry);
    }

    private PlannerInputs inputs(java.util.List<RuntimeProvider> runtimes,
                                 ModelRepositoryProvider repository,
                                 ArtifactInspectorRegistry registry) {
        return new PlannerInputs(
                runtimes,
                Arrays.asList(repository),
                new ArrayList<ModelConverter>(),
                new ArrayList<ExecutionPolicy>(),
                registry,
                TestRepositoryProvider.environment(25),
                TestRepositoryProvider.hardware(false),
                null, null, null, null,
                false, true, "0.1.0-test", 0L);
    }

    @Test
    void inspectionErrorBecomesTypedCortexJException() throws Exception {
        Path file = ggufFile();
        ModelArtifact artifact = artifactFor(file);
        ResolvedArtifact resolved = resolvedArtifact(artifact, file.toUri());
        ModelDescriptor descriptor = ModelDescriptor.builder(MODEL.value())
                .revision("rev1")
                .task(TaskCapability.TEXT_GENERATION)
                .artifact(artifact)
                .build();
        ResolvedModel resolvedModel = new ResolvedModel(descriptor, "rev1",
                RepositoryId.of("test-repo"), Collections.<ResolvedArtifact>singletonList(resolved));
        PlannerInputs inputs = inputs(new UnresolvableRepository(), throwingRegistry());

        CortexJException exception = assertThrows(CortexJException.class,
                () -> inputs.inspectIfLocal(resolvedModel, resolved));
        assertEquals(ErrorCode.ARTIFACT_RESOLUTION_FAILED, exception.code());
        assertTrue(exception.getCause() instanceof StackOverflowError,
                "cause must be the original StackOverflowError, was: " + exception.getCause());
        assertTrue(exception.getMessage().contains("inspection"),
                "message must name the inspection failure: " + exception.getMessage());
    }

    @Test
    void planFailsCleanlyWhenInspectionThrowsError() throws Exception {
        Path file = ggufFile();
        ModelArtifact artifact = artifactFor(file);
        PlannerInputs inputs = inputs(fileRepository(artifact, file.toUri()), throwingRegistry());
        DefaultExecutionPlanner planner = new DefaultExecutionPlanner(inputs);

        CortexJException exception = assertThrows(CortexJException.class,
                () -> planner.plan(ExecutionRequest.forModel(MODEL)));
        assertEquals(ErrorCode.ARTIFACT_RESOLUTION_FAILED, exception.code());
        assertTrue(exception.getCause() instanceof StackOverflowError,
                "cause must be the original StackOverflowError, was: " + exception.getCause());
        assertTrue(exception.getMessage().contains("inspection"),
                "message must name the inspection failure: " + exception.getMessage());
    }

    private static final class UnresolvableRepository implements ModelRepositoryProvider {

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
            throw new UnsupportedOperationException("not used in this test");
        }
    }

    private static ModelRepositoryProvider fileRepository(final ModelArtifact artifact, final URI location) {
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
                ModelDescriptor descriptor = ModelDescriptor.builder(reference.value())
                        .revision("rev1")
                        .task(TaskCapability.TEXT_GENERATION)
                        .artifact(artifact)
                        .build();
                return new ResolvedModel(descriptor, "rev1", id(),
                        Collections.<ResolvedArtifact>singletonList(
                                resolvedArtifact(artifact, location)));
            }
        };
    }
}
