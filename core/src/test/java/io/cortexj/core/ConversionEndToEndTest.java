package io.cortexj.core;

import io.cortexj.CortexJ;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelArtifact;
import io.cortexj.model.ModelDescriptor;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelLifecycleState;
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

import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConversionEndToEndTest {

    @TempDir
    Path tempDir;

    private static final byte[] SAFETENSORS_BYTES = "fake-safetensors-weights".getBytes();
    private static final byte[] CONVERTED_BYTES = "converted-gguf-weights".getBytes();

    static final class RecordingRuntimeProvider implements RuntimeProvider {

        final List<Path> loadedPaths = Collections.synchronizedList(new ArrayList<Path>());

        @Override
        public io.cortexj.runtime.RuntimeId id() {
            return io.cortexj.runtime.RuntimeId.of("gguf-runtime");
        }

        @Override
        public io.cortexj.runtime.RuntimeDescriptor descriptor() {
            return io.cortexj.runtime.RuntimeDescriptor.builder(id())
                    .version("1.0")
                    .format(ArtifactFormat.GGUF)
                    .device(io.cortexj.runtime.DeviceType.CPU)
                    .task(TaskCapability.TEXT_GENERATION)
                    .maturity(io.cortexj.runtime.RuntimeDescriptor.Maturity.STABLE)
                    .build();
        }

        @Override
        public RuntimeProbeResult probe(RuntimeProbeContext context) {
            return RuntimeProbeResult.available("gguf runtime ready");
        }

        @Override
        public RuntimeSupport evaluate(RuntimeEvaluationRequest request) {
            if (request.artifact() != null && request.artifact().format() == ArtifactFormat.GGUF) {
                return RuntimeSupport.full(io.cortexj.runtime.DeviceType.CPU);
            }
            return RuntimeSupport.unsupported();
        }

        @Override
        public ModelRuntime open(RuntimeOpenRequest request) {
            return new ModelRuntime() {
                @Override
                public io.cortexj.runtime.RuntimeId runtimeId() {
                    return id();
                }

                @Override
                public RuntimeModel load(RuntimeModelRequest loadRequest) {
                    loadedPaths.add(loadRequest.artifactPath());
                    return new io.cortexj.core.EndToEndLoadTest.FakeRuntimeModel();
                }

                @Override
                public void close() {
                }
            };
        }
    }

    static final class FakeConverter implements ModelConverter {

        int conversions;

        @Override
        public ConverterId id() {
            return ConverterId.of("e2e-st-gguf");
        }

        @Override
        public ConversionSupport evaluate(ConversionRequest request) {
            if (request.sourceArtifact().format() == ArtifactFormat.SAFETENSORS
                    && request.targetFormat() == ArtifactFormat.GGUF) {
                return ConversionSupport.supported();
            }
            return ConversionSupport.unsupported(
                    io.cortexj.planner.ReasonCode.FORMAT_UNSUPPORTED, "e2e: safetensors only");
        }

        @Override
        public ConversionResult convert(ConversionRequest request) {
            conversions++;
            try {
                Path output = request.outputDirectory().resolve("model.safetensors.gguf");
                Files.write(output, CONVERTED_BYTES);
                ModelArtifact out = new io.cortexj.model.ModelArtifact.Abstract(
                        "model.safetensors.gguf", ArtifactFormat.GGUF, CONVERTED_BYTES.length,
                        null, null) {
                };
                return new ConversionResult(out, output,
                        Collections.singletonMap("converterVersion", "1.0.0-e2e"));
            } catch (java.io.IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }

    private ResolvedModel resolvedModel(Path safetensorsFile) {
        ModelArtifact source = new io.cortexj.model.ModelArtifact.Abstract(
                "model.safetensors", ArtifactFormat.SAFETENSORS, SAFETENSORS_BYTES.length,
                null, null) {
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

    @Test
    void conversionIsMaterializedWithProvenanceAndOriginalStaysImmutable() throws Exception {
        Path safetensors = tempDir.resolve("model.safetensors");
        Files.write(safetensors, SAFETENSORS_BYTES);
        byte[] before = Files.readAllBytes(safetensors);

        RecordingRuntimeProvider runtime = new RecordingRuntimeProvider();
        FakeConverter converter = new FakeConverter();
        Path workDir = tempDir.resolve("cortexj-work");

        final ResolvedModel resolved = resolvedModel(safetensors);
        ModelRepositoryProvider repository = new ModelRepositoryProvider() {
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

        CortexJ platform = CortexJ.builder()
                .runtimeProvider(runtime)
                .repositoryProvider(repository)
                .converter(converter)
                .workingDirectory(workDir)
                .build();
        try {
            ModelHandle handle = platform.models().load(ModelReference.of("e2e/convertible-model"));
            handle.close();

            assertTrue(handle.plan().selected().requiresConversion(),
                    "plan must route through a conversion step");
            assertEquals(1, runtime.loadedPaths.size());
            Path runtimePath = runtime.loadedPaths.get(0);
            assertTrue(runtimePath.toString().endsWith("model.safetensors.gguf"),
                    "runtime must receive the converted artifact: " + runtimePath);
            assertArrayEquals(CONVERTED_BYTES, Files.readAllBytes(runtimePath));

            Path provenance = workDir.resolve("converted").resolve("e2e-repo").resolve("rev1")
                    .resolve("model.safetensors.gguf.provenance.properties");
            assertTrue(Files.isRegularFile(provenance), "provenance manifest must exist: " + provenance);
            Properties manifest = new Properties();
            try (InputStream input = Files.newInputStream(provenance)) {
                manifest.load(input);
            }
            assertEquals("rev1", manifest.getProperty("sourceRevision"));
            assertEquals("model.safetensors", manifest.getProperty("sourceArtifact"));
            assertEquals("e2e-st-gguf", manifest.getProperty("converterId"));
            assertEquals("GGUF", manifest.getProperty("targetFormat"));
            assertEquals("model.safetensors.gguf", manifest.getProperty("outputArtifact"));
            assertTrue(manifest.getProperty("outputChecksum").startsWith("sha256:"));
            assertEquals("0.1.0", manifest.getProperty("platformVersion"));
            assertEquals("1.0.0-e2e", manifest.getProperty("converter.converterVersion"));
            assertEquals("true", manifest.getProperty("originalArtifactImmutable"));

            assertArrayEquals(before, Files.readAllBytes(safetensors),
                    "the original artifact must never be modified");

            ModelHandle second = platform.models().load(ModelReference.of("e2e/convertible-model"));
            second.close();
            assertEquals(1, converter.conversions,
                    "second load must reuse the cached conversion output");
        } finally {
            platform.close();
        }
    }

    @Test
    void maliciousManifestIdsKeepProvenanceWriteInsideWorkingDirectory() throws Exception {
        Path safetensors = tempDir.resolve("model.safetensors");
        Files.write(safetensors, SAFETENSORS_BYTES);

        RecordingRuntimeProvider runtime = new RecordingRuntimeProvider();
        FakeConverter converter = new FakeConverter();
        Path workDir = tempDir.resolve("cortexj-work");

        ModelArtifact source = new io.cortexj.model.ModelArtifact.Abstract(
                "../.ssh/escape", ArtifactFormat.SAFETENSORS, SAFETENSORS_BYTES.length, null, null) {
        };
        ModelDescriptor descriptor = ModelDescriptor.builder("evil/escape-model")
                .revision("../../../outside")
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
        final ResolvedModel resolved = new ResolvedModel(descriptor, "../../../outside",
                RepositoryId.of("evil-repo"), Collections.singletonList(artifact));
        ModelRepositoryProvider repository = new ModelRepositoryProvider() {
            @Override
            public RepositoryId id() {
                return RepositoryId.of("evil-repo");
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

        CortexJ platform = CortexJ.builder()
                .runtimeProvider(runtime)
                .repositoryProvider(repository)
                .converter(converter)
                .workingDirectory(workDir)
                .build();
        try {
            ModelHandle handle = platform.models().load(ModelReference.of("evil/escape-model"));
            handle.close();
            assertEquals(ModelLifecycleState.CLOSED, handle.state(),
                    "the conversion pipeline must survive attacker-supplied ids");
            assertEquals(1, runtime.loadedPaths.size(), "the runtime must receive the converted artifact");

            assertFalse(Files.exists(tempDir.resolve("outside")),
                    "the malicious revision must not escape the working directory");
            assertFalse(Files.exists(tempDir.resolve(".ssh")),
                    "the malicious artifact id must not escape the working directory");

            Path workRoot = workDir.toAbsolutePath().normalize();
            Path original = safetensors.toAbsolutePath().normalize();
            final List<Path> written = new ArrayList<Path>();
            Files.walkFileTree(tempDir, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file,
                                                                java.nio.file.attribute.BasicFileAttributes attrs) {
                    written.add(file);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
            for (Path file : written) {
                Path normalized = file.toAbsolutePath().normalize();
                assertTrue(normalized.startsWith(workRoot) || normalized.equals(original),
                        "no file may be written outside the working directory, found: " + normalized);
            }

            final List<Path> provenanceFiles = new ArrayList<Path>();
            Files.walkFileTree(workDir.resolve("converted"), new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(Path file,
                                                                java.nio.file.attribute.BasicFileAttributes attrs) {
                    if (file.getFileName() != null
                            && file.getFileName().toString().endsWith(".provenance.properties")) {
                        provenanceFiles.add(file);
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
            assertEquals(1, provenanceFiles.size(),
                    "exactly one provenance manifest must be written under the working directory");
            assertTrue(provenanceFiles.get(0).toAbsolutePath().normalize().startsWith(workRoot),
                    "the provenance manifest must stay inside the working directory");
        } finally {
            platform.close();
        }
    }
}
