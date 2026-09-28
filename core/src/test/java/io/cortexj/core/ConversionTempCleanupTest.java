package io.cortexj.core;

import io.cortexj.CortexJ;
import io.cortexj.error.ModelLoadException;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelArtifact;
import io.cortexj.model.ModelDescriptor;
import io.cortexj.model.ModelReference;
import io.cortexj.model.TaskCapability;
import io.cortexj.spi.conversion.ConversionRequest;
import io.cortexj.spi.conversion.ConversionResult;
import io.cortexj.spi.conversion.ConversionSupport;
import io.cortexj.spi.conversion.ConverterId;
import io.cortexj.spi.conversion.ModelConverter;
import io.cortexj.spi.repository.ModelRepositoryProvider;
import io.cortexj.spi.repository.RepositoryId;
import io.cortexj.spi.repository.ResolvedArtifact;
import io.cortexj.spi.repository.ResolvedModel;
import io.cortexj.spi.repository.ResolutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversionTempCleanupTest {

    @TempDir
    Path tempDir;

    private static final byte[] SAFETENSORS_BYTES = "fake-safetensors-weights".getBytes();

    private static int conversionTempDirs() throws java.io.IOException {
        Path tmp = Paths.get(System.getProperty("java.io.tmpdir"));
        int count = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(tmp, "cortexj-conversion-*")) {
            for (Path ignored : stream) {
                count++;
            }
        }
        return count;
    }

    private ResolvedModel resolvedModel(Path safetensorsFile) {
        final ModelArtifact source = new ModelArtifact.Abstract(
                "model.safetensors", ArtifactFormat.SAFETENSORS, SAFETENSORS_BYTES.length, null, null) {
        };
        ModelDescriptor descriptor = ModelDescriptor.builder("e2e/convertible-model")
                .revision("rev1")
                .architecture("llama")
                .task(TaskCapability.TEXT_GENERATION)
                .artifact(source)
                .build();
        final URI location = safetensorsFile.toUri();
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
        return new ResolvedModel(descriptor, "rev1", RepositoryId.of("e2e-repo"),
                Collections.singletonList(artifact));
    }

    private ModelRepositoryProvider repository(ResolvedModel resolved) {
        return new ModelRepositoryProvider() {
            @Override
            public RepositoryId id() {
                return RepositoryId.of("e2e-repo");
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

    @Test
    void successfulConversionLeavesNoTempDirectoryBehind() throws Exception {
        int before = conversionTempDirs();
        Path safetensors = tempDir.resolve("model.safetensors");
        Files.write(safetensors, SAFETENSORS_BYTES);

        CortexJ platform = CortexJ.builder()
                .runtimeProvider(new ConversionEndToEndTest.RecordingRuntimeProvider())
                .repositoryProvider(repository(resolvedModel(safetensors)))
                .converter(new ConversionEndToEndTest.FakeConverter())
                .workingDirectory(tempDir.resolve("cortexj-work"))
                .build();
        try {
            platform.models().load(ModelReference.of("e2e/convertible-model")).close();
        } finally {
            platform.close();
        }
        assertEquals(before, conversionTempDirs(),
                "a successful conversion must delete its temp working directory");
    }

    @Test
    void failedConversionLeavesNoTempDirectoryBehind() throws Exception {
        int before = conversionTempDirs();
        Path safetensors = tempDir.resolve("model.safetensors");
        Files.write(safetensors, SAFETENSORS_BYTES);

        ModelConverter failing = new ModelConverter() {
            @Override
            public ConverterId id() {
                return ConverterId.of("e2e-st-gguf");
            }

            @Override
            public ConversionSupport evaluate(ConversionRequest request) {
                return ConversionSupport.supported();
            }

            @Override
            public ConversionResult convert(ConversionRequest request) {
                throw new IllegalStateException("converter exploded mid-run");
            }
        };

        CortexJ platform = CortexJ.builder()
                .runtimeProvider(new ConversionEndToEndTest.RecordingRuntimeProvider())
                .repositoryProvider(repository(resolvedModel(safetensors)))
                .converter(failing)
                .workingDirectory(tempDir.resolve("cortexj-work"))
                .build();
        try {
            assertThrows(ModelLoadException.class,
                    () -> platform.models().load(ModelReference.of("e2e/convertible-model")));
        } finally {
            platform.close();
        }
        assertEquals(before, conversionTempDirs(),
                "a failed conversion must delete its temp working directory");
        assertTrue(Files.exists(safetensors), "the original artifact must stay untouched");
    }
}
