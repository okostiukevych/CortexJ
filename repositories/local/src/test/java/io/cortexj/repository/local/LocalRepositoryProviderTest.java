package io.cortexj.repository.local;

import io.cortexj.error.ArtifactResolutionException;
import io.cortexj.error.ModelResolutionException;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelReference;
import io.cortexj.model.TaskCapability;
import io.cortexj.spi.repository.ResolvedModel;
import io.cortexj.spi.repository.ResolutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalRepositoryProviderTest {

    @TempDir
    Path tempDir;

    private final LocalRepositoryProvider provider = new LocalRepositoryProvider();

    private ResolutionContext context() {
        return ResolutionContext.create(false, null, null, null);
    }

    @Test
    void supportsMatchesFileSchemes() {
        assertTrue(provider.supports(ModelReference.of("file:/opt/models/model.gguf")));
        assertTrue(provider.supports(ModelReference.of("/opt/models/model.gguf")));
        assertFalse(provider.supports(ModelReference.of("hf:Qwen/Qwen3-8B")));
        assertFalse(provider.supports(ModelReference.of("Qwen/Qwen3-8B")));
    }

    @Test
    void resolvesSingleGgufFile() throws IOException {
        Path file = tempDir.resolve("model-q4_k.gguf");
        Files.write(file, "gguf-bytes".getBytes(StandardCharsets.UTF_8));

        ResolvedModel model = provider.resolve(ModelReference.ofFile(file.toString()), context());
        assertEquals("model-q4_k.gguf", model.descriptor().canonicalId());
        assertTrue(model.descriptor().supports(TaskCapability.TEXT_GENERATION));
        assertEquals(1, model.artifacts().size());
        assertEquals(ArtifactFormat.GGUF, model.artifacts().get(0).artifact().format());
        assertNotNull(model.artifacts().get(0).artifact().checksum());
        assertTrue(model.hasRevision());
        assertEquals("q4_k", model.artifacts().get(0).artifact().metadata().get("quantization"));
    }

    @Test
    void resolvesDirectoryManifest() throws IOException {
        Path directory = tempDir.resolve("company-model");
        Files.createDirectories(directory);
        Files.write(directory.resolve("weights.gguf"), "weights".getBytes(StandardCharsets.UTF_8));
        Files.write(directory.resolve("model.onnx"), "onnx".getBytes(StandardCharsets.UTF_8));
        String manifest = "{\"id\":\"company/document-model\",\"revision\":\"v1.2\","
                + "\"architecture\":\"llama\",\"license\":\"apache-2.0\",\"contextLength\":8192,"
                + "\"tasks\":[\"embeddings\",\"text-generation\"],"
                + "\"artifacts\":[{\"file\":\"weights.gguf\"},{\"file\":\"model.onnx\",\"sha256\":\"deadbeef\"}]}";
        Files.write(directory.resolve(LocalRepositoryProvider.MANIFEST_FILE), manifest.getBytes(StandardCharsets.UTF_8));

        ResolvedModel model = provider.resolve(ModelReference.ofFile(directory.toString()), context());
        assertEquals("company/document-model", model.descriptor().canonicalId());
        assertEquals("v1.2", model.revision());
        assertEquals(2, model.artifacts().size());
        assertTrue(model.descriptor().supports(TaskCapability.EMBEDDING));
        assertEquals(8192, model.descriptor().contextLength());
        assertEquals("apache-2.0", model.descriptor().license());
        assertNotNull(model.artifacts().get(1).artifact().checksum());
    }

    @Test
    void missingPathYieldsActionableError() {
        ModelReference reference = ModelReference.ofFile(tempDir.resolve("missing.gguf").toString());
        ModelResolutionException exception = assertThrows(ModelResolutionException.class,
                () -> provider.resolve(reference, context()));
        assertEquals("CORTEXJ-MODEL-1002", exception.code());
    }

    @Test
    void manifestWithoutManifestFileFails() throws IOException {
        Path directory = tempDir.resolve("empty-model");
        Files.createDirectories(directory);
        ModelResolutionException exception = assertThrows(ModelResolutionException.class,
                () -> provider.resolve(ModelReference.ofFile(directory.toString()), context()));
        assertTrue(exception.getMessage().contains(LocalRepositoryProvider.MANIFEST_FILE));
    }

    @Test
    void pathTraversalInManifestRejected() throws IOException {
        Path directory = tempDir.resolve("evil-model");
        Files.createDirectories(directory);
        String manifest = "{\"id\":\"evil\",\"artifacts\":[{\"file\":\"../../escape.gguf\"}]}";
        Files.write(directory.resolve(LocalRepositoryProvider.MANIFEST_FILE), manifest.getBytes(StandardCharsets.UTF_8));
        ArtifactResolutionException exception = assertThrows(ArtifactResolutionException.class,
                () -> provider.resolve(ModelReference.ofFile(directory.toString()), context()));
        assertEquals("CORTEXJ-ARTIFACT-2005", exception.code());
    }

    @Test
    void taskMappingByFormat() {
        assertEquals(TaskCapability.TEXT_GENERATION, LocalRepositoryProvider.taskForFormat(ArtifactFormat.GGUF));
        assertEquals(TaskCapability.EMBEDDING, LocalRepositoryProvider.taskForFormat(ArtifactFormat.ONNX));
    }

    @Test
    void symlinkSubdirectoryWithMissingFileInManifestRejected() throws IOException {
        Path directory = tempDir.resolve("symlink-model");
        Files.createDirectories(directory);
        Path outside = tempDir.resolve("outside-model-dir");
        Files.createDirectories(outside);
        try {
            Files.createSymbolicLink(directory.resolve("sub"), outside);
        } catch (UnsupportedOperationException | IOException symlinkNotSupported) {
            return;
        }
        String manifest = "{\"id\":\"evil\",\"artifacts\":[{\"file\":\"sub/missing.gguf\"}]}";
        Files.write(directory.resolve(LocalRepositoryProvider.MANIFEST_FILE),
                manifest.getBytes(StandardCharsets.UTF_8));
        ArtifactResolutionException exception = assertThrows(ArtifactResolutionException.class,
                () -> provider.resolve(ModelReference.ofFile(directory.toString()), context()));
        assertEquals("CORTEXJ-ARTIFACT-2005", exception.code());
    }
}
