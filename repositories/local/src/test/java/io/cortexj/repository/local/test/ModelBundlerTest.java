package io.cortexj.repository.local.test;

import io.cortexj.cache.CacheStorage;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelReference;
import io.cortexj.repository.local.LocalRepositoryProvider;
import io.cortexj.repository.local.bundle.BundleReport;
import io.cortexj.repository.local.bundle.ModelBundler;
import io.cortexj.spi.repository.ResolvedModel;
import io.cortexj.spi.repository.ResolutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelBundlerTest {

    @TempDir
    Path tempDir;

    private LocalRepositoryProvider localProvider() {
        return new LocalRepositoryProvider();
    }

    private ResolutionContext context(boolean offline) {
        return ResolutionContext.create(offline, io.cortexj.spi.repository.TokenSupplier.none(),
                null, null);
    }

    @Test
    void createBundleFromLocalFileAndVerify() throws Exception {
        Path source = tempDir.resolve("model-q4_k_m.gguf");
        byte[] payload = new byte[1024];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 251);
        }
        Files.write(source, payload);
        CacheStorage cache = new CacheStorage(tempDir.resolve("cache"));

        ModelBundler bundler = new ModelBundler(
                Collections.<io.cortexj.spi.repository.ModelRepositoryProvider>singletonList(localProvider()),
                cache);
        Path bundleDir = tempDir.resolve("bundle");
        bundler.create(ModelReference.of(source.toString()), bundleDir, context(false));

        assertTrue(Files.isRegularFile(bundleDir.resolve("cortexj-model.json")));
        assertTrue(Files.isRegularFile(bundleDir.resolve("model-q4_k_m.gguf")));

        BundleReport report = bundler.verify(bundleDir);
        assertTrue(report.isOk(), "expected clean verify, issues: " + report.issues());
        assertEquals(1, report.verifiedArtifacts());
        assertEquals("model-q4_k_m.gguf", report.modelId());
        assertFalse(report.revision() == null || report.revision().isEmpty());
    }

    @Test
    void corruptedBundleFailsVerification() throws Exception {
        Path source = tempDir.resolve("model-q6_k.gguf");
        Files.write(source, new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        ModelBundler bundler = new ModelBundler(
                Collections.<io.cortexj.spi.repository.ModelRepositoryProvider>singletonList(localProvider()),
                null);
        Path bundleDir = tempDir.resolve("bundle");
        bundler.create(ModelReference.of(source.toString()), bundleDir, context(false));

        Path artifact = bundleDir.resolve("model-q6_k.gguf");
        byte[] tampered = Files.readAllBytes(artifact);
        tampered[3] ^= 0x7F;
        Files.write(artifact, tampered);

        BundleReport report = bundler.verify(bundleDir);
        assertFalse(report.isOk());
        assertEquals(1, report.issues().size());
        assertTrue(report.issues().get(0).contains("checksum mismatch"));
    }

    @Test
    void missingArtifactFailsVerification() throws Exception {
        Path source = tempDir.resolve("model-f16.gguf");
        Files.write(source, new byte[]{9, 8, 7, 6});
        ModelBundler bundler = new ModelBundler(
                Arrays.<io.cortexj.spi.repository.ModelRepositoryProvider>asList(localProvider()),
                null);
        Path bundleDir = tempDir.resolve("bundle");
        bundler.create(ModelReference.of(source.toString()), bundleDir, context(false));
        Files.delete(bundleDir.resolve("model-f16.gguf"));

        BundleReport report = bundler.verify(bundleDir);
        assertFalse(report.isOk());
        assertTrue(report.issues().get(0).contains("artifact missing"));
    }

    @Test
    void bundleResolvesOfflineViaLocalRepository() throws Exception {
        Path source = tempDir.resolve("model-iq2_x.gguf");
        Files.write(source, new byte[]{11, 12, 13, 14, 15});
        ModelBundler bundler = new ModelBundler(
                Collections.<io.cortexj.spi.repository.ModelRepositoryProvider>singletonList(localProvider()),
                null);
        Path bundleDir = tempDir.resolve("bundle");
        bundler.create(ModelReference.of(source.toString()), bundleDir, context(false));

        LocalRepositoryProvider provider = localProvider();
        ResolvedModel resolved = provider.resolve(ModelReference.of(bundleDir.toString()), context(true));
        assertEquals(1, resolved.artifacts().size());
        assertEquals(ArtifactFormat.GGUF, resolved.artifacts().get(0).artifact().format());
    }

    @Test
    void emptyBundleDirectoryReportsMissingManifest() {
        ModelBundler bundler = new ModelBundler(
                Collections.<io.cortexj.spi.repository.ModelRepositoryProvider>emptyList(), null);
        BundleReport report = bundler.verify(tempDir.resolve("empty"));
        assertFalse(report.isOk());
        assertTrue(report.issues().get(0).contains("no cortexj-model.json"));
    }

    @Test
    void manifestVersionIsWrittenAndUnknownMajorFails() throws Exception {
        // audit v3.0 §125.8 (issue #159)
        Path source = tempDir.resolve("vmodel.gguf");
        Files.write(source, new byte[]{1, 2, 3, 4});
        CacheStorage cache = new CacheStorage(tempDir.resolve("cache"));
        ModelBundler bundler = new ModelBundler(
                Collections.<io.cortexj.spi.repository.ModelRepositoryProvider>singletonList(localProvider()),
                cache);
        Path bundleDir = tempDir.resolve("vbundle");
        bundler.create(ModelReference.of(source.toString()), bundleDir, context(false));
        String manifest = new String(
                Files.readAllBytes(bundleDir.resolve("cortexj-model.json")),
                java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(manifest.contains("\"manifestVersion\" : \"1.0\""),
                "created bundles must stamp the schema version, got: " + manifest);
        assertTrue(bundler.verify(bundleDir).isOk());

        Path futureDir = tempDir.resolve("future");
        Files.createDirectories(futureDir);
        Files.write(futureDir.resolve("cortexj-model.json"),
                "{\"manifestVersion\":\"2.0\",\"id\":\"m\",\"revision\":\"r\",\"artifacts\":[]}"
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        BundleReport future = bundler.verify(futureDir);
        assertFalse(future.isOk(), "unknown manifest majors must fail verification");
        assertTrue(future.issues().get(0).contains("major version 2"));
    }
}
