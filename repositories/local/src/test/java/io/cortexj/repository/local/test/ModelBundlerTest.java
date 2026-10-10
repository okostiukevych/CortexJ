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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        // audit (issue #159)
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

    @Test
    void bundleFollowsRedirectsToCdn() throws Exception {
        // real-model verification 2026-10-10: Hugging Face /resolve/ URLs
        // answer 302/307 to the CDN - bundle create must follow them
        final byte[] payload = new byte[4096];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 13 + 5);
        }
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(2));
        final int port = server.getAddress().getPort();
        server.createContext("/resolve/config.json",
                new com.sun.net.httpserver.HttpHandler() {
                    @Override
                    public void handle(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
                        exchange.getResponseHeaders().add("Location",
                                "http://127.0.0.1:" + port + "/cdn/config.json");
                        exchange.sendResponseHeaders(307, -1);
                        exchange.close();
                    }
                });
        server.createContext("/cdn/config.json",
                new com.sun.net.httpserver.HttpHandler() {
                    @Override
                    public void handle(com.sun.net.httpserver.HttpExchange exchange) throws java.io.IOException {
                        exchange.getResponseHeaders().add("Connection", "close");
                        exchange.sendResponseHeaders(200, payload.length);
                        java.io.OutputStream out = exchange.getResponseBody();
                        try {
                            out.write(payload);
                        } finally {
                            out.close();
                            exchange.close();
                        }
                    }
                });
        server.start();
        System.setProperty("cortexj.http.allowPrivateHosts", "true");
        try {
            final java.net.URI location =
                    java.net.URI.create("http://127.0.0.1:" + port + "/resolve/config.json");
            final io.cortexj.model.ModelArtifact artifact =
                    new io.cortexj.model.ModelArtifact.Abstract(
                            "test-bundle/config.json", io.cortexj.model.ArtifactFormat.CONFIG,
                            payload.length, null,
                            java.util.Collections.<String, String>emptyMap()) {
                    };
            final io.cortexj.spi.repository.ResolvedArtifact resolved =
                    new io.cortexj.spi.repository.ResolvedArtifact() {
                        @Override
                        public io.cortexj.model.ModelArtifact artifact() {
                            return artifact;
                        }

                        @Override
                        public java.net.URI location() {
                            return location;
                        }

                        @Override
                        public java.util.Map<String, String> downloadHeaders() {
                            return java.util.Collections.emptyMap();
                        }
                    };
            io.cortexj.spi.repository.ModelRepositoryProvider repository =
                    new io.cortexj.spi.repository.ModelRepositoryProvider() {
                        @Override
                        public io.cortexj.spi.repository.RepositoryId id() {
                            return io.cortexj.spi.repository.RepositoryId.of("redirect-repo");
                        }

                        @Override
                        public boolean supports(io.cortexj.model.ModelReference reference) {
                            return true;
                        }

                        @Override
                        public io.cortexj.spi.repository.ResolvedModel resolve(
                                io.cortexj.model.ModelReference reference,
                                io.cortexj.spi.repository.ResolutionContext context) {
                            io.cortexj.model.ModelDescriptor descriptor =
                                    io.cortexj.model.ModelDescriptor.builder("redirect/model")
                                            .revision("rev1")
                                            .task(io.cortexj.model.TaskCapability.TEXT_GENERATION)
                                            .artifact(artifact)
                                            .build();
                            return new io.cortexj.spi.repository.ResolvedModel(descriptor, "rev1",
                                    io.cortexj.spi.repository.RepositoryId.of("redirect-repo"),
                                    java.util.Collections.singletonList(resolved));
                        }
                    };
            ModelBundler bundler = new ModelBundler(
                    java.util.Collections.singletonList(repository), null);
            Path bundleDir = tempDir.resolve("redirect-bundle");
            bundler.create(ModelReference.of("redirect/model"), bundleDir, context(false));

            Path staged = bundleDir.resolve("test-bundle/config.json");
            assertTrue(Files.isRegularFile(staged), "redirected artifact must land in the bundle");
            assertTrue(java.util.Arrays.equals(payload, Files.readAllBytes(staged)),
                    "redirected artifact bytes must be intact");
            BundleReport report = bundler.verify(bundleDir);
            assertTrue(report.isOk(), "expected clean verify, issues: " + report.issues());
        } finally {
            System.clearProperty("cortexj.http.allowPrivateHosts");
            server.stop(0);
        }
    }

    @Test
    void sameNamedFilesInDifferentDirectoriesDoNotCollide() throws Exception {
        // real-model verification 2026-10-10: sentence-transformers repos
        // ship root config.json AND 1_Pooling/config.json - flattening both
        // to config.json overwrote one and broke bundle verify
        byte[] rootConfig = "{\"root\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] poolingConfig = "{\"pooling\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path rootFile = tempDir.resolve("root-config.json");
        Path poolingFile = tempDir.resolve("pooling-config.json");
        Files.write(rootFile, rootConfig);
        Files.write(poolingFile, poolingConfig);

        final io.cortexj.model.ModelArtifact root = new io.cortexj.model.ModelArtifact.Abstract(
                "owner/name/config.json", io.cortexj.model.ArtifactFormat.CONFIG,
                rootConfig.length, null,
                java.util.Collections.<String, String>emptyMap()) {
        };
        final io.cortexj.model.ModelArtifact pooling = new io.cortexj.model.ModelArtifact.Abstract(
                "owner/name/1_Pooling/config.json", io.cortexj.model.ArtifactFormat.CONFIG,
                poolingConfig.length, null,
                java.util.Collections.<String, String>emptyMap()) {
        };
        io.cortexj.spi.repository.ModelRepositoryProvider repository =
                new io.cortexj.spi.repository.ModelRepositoryProvider() {
                    @Override
                    public io.cortexj.spi.repository.RepositoryId id() {
                        return io.cortexj.spi.repository.RepositoryId.of("hierarchy-repo");
                    }

                    @Override
                    public boolean supports(io.cortexj.model.ModelReference reference) {
                        return true;
                    }

                    @Override
                    public io.cortexj.spi.repository.ResolvedModel resolve(
                            io.cortexj.model.ModelReference reference,
                            io.cortexj.spi.repository.ResolutionContext context) {
                        io.cortexj.model.ModelDescriptor descriptor =
                                io.cortexj.model.ModelDescriptor.builder("owner/name")
                                        .revision("rev1")
                                        .task(io.cortexj.model.TaskCapability.TEXT_GENERATION)
                                        .artifact(root)
                                        .artifact(pooling)
                                        .build();
                        java.util.List<io.cortexj.spi.repository.ResolvedArtifact> artifacts =
                                new java.util.ArrayList<io.cortexj.spi.repository.ResolvedArtifact>();
                        artifacts.add(fileArtifact(root, rootFile.toUri()));
                        artifacts.add(fileArtifact(pooling, poolingFile.toUri()));
                        return new io.cortexj.spi.repository.ResolvedModel(descriptor, "rev1",
                                io.cortexj.spi.repository.RepositoryId.of("hierarchy-repo"), artifacts);
                    }

                    private io.cortexj.spi.repository.ResolvedArtifact fileArtifact(
                            final io.cortexj.model.ModelArtifact artifact, final java.net.URI location) {
                        return new io.cortexj.spi.repository.ResolvedArtifact() {
                            @Override
                            public io.cortexj.model.ModelArtifact artifact() {
                                return artifact;
                            }

                            @Override
                            public java.net.URI location() {
                                return location;
                            }

                            @Override
                            public java.util.Map<String, String> downloadHeaders() {
                                return java.util.Collections.emptyMap();
                            }
                        };
                    }
                };
        ModelBundler bundler = new ModelBundler(
                java.util.Collections.singletonList(repository), null);
        Path bundleDir = tempDir.resolve("hierarchy-bundle");
        bundler.create(ModelReference.of("owner/name"), bundleDir, context(false));

        Path stagedRoot = bundleDir.resolve("config.json");
        Path stagedPooling = bundleDir.resolve("1_Pooling/config.json");
        assertTrue(Files.isRegularFile(stagedRoot), "root config.json keeps its flat name");
        assertTrue(Files.isRegularFile(stagedPooling), "subdirectory config.json keeps its hierarchy");
        assertTrue(java.util.Arrays.equals(rootConfig, Files.readAllBytes(stagedRoot)));
        assertTrue(java.util.Arrays.equals(poolingConfig, Files.readAllBytes(stagedPooling)));
        BundleReport report = bundler.verify(bundleDir);
        assertTrue(report.isOk(), "expected clean verify, issues: " + report.issues());

        // the hierarchical bundle re-resolves offline with both artifacts
        LocalRepositoryProvider provider = localProvider();
        io.cortexj.spi.repository.ResolvedModel resolved =
                provider.resolve(ModelReference.of(bundleDir.toString()), context(true));
        assertEquals(2, resolved.artifacts().size());
    }

    @Test
    void tokenizerIsStagedNextToWeightsInSubdirectories() throws Exception {
        // real-model verification 2026-10-10: runtimes discover
        // tokenizer.json as a sibling of the weight file, but hierarchy
        // puts onnx/model.onnx away from root tokenizer.json
        byte[] weight = new byte[]{1, 2, 3, 4};
        byte[] tokenizer = "{\"tokenizer\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Path weightFile = tempDir.resolve("model.onnx");
        Path tokenizerFile = tempDir.resolve("real-tokenizer.json");
        Files.write(weightFile, weight);
        Files.write(tokenizerFile, tokenizer);

        final io.cortexj.model.ModelArtifact onnx = new io.cortexj.model.ModelArtifact.Abstract(
                "owner/name/onnx/model.onnx", io.cortexj.model.ArtifactFormat.ONNX,
                weight.length, null,
                java.util.Collections.<String, String>emptyMap()) {
        };
        final io.cortexj.model.ModelArtifact tok = new io.cortexj.model.ModelArtifact.Abstract(
                "owner/name/tokenizer.json", io.cortexj.model.ArtifactFormat.TOKENIZER,
                tokenizer.length, null,
                java.util.Collections.<String, String>emptyMap()) {
        };
        io.cortexj.spi.repository.ModelRepositoryProvider repository =
                new io.cortexj.spi.repository.ModelRepositoryProvider() {
                    @Override
                    public io.cortexj.spi.repository.RepositoryId id() {
                        return io.cortexj.spi.repository.RepositoryId.of("sibling-repo");
                    }

                    @Override
                    public boolean supports(io.cortexj.model.ModelReference reference) {
                        return true;
                    }

                    @Override
                    public io.cortexj.spi.repository.ResolvedModel resolve(
                            io.cortexj.model.ModelReference reference,
                            io.cortexj.spi.repository.ResolutionContext context) {
                        io.cortexj.model.ModelDescriptor descriptor =
                                io.cortexj.model.ModelDescriptor.builder("owner/name")
                                        .revision("rev1")
                                        .task(io.cortexj.model.TaskCapability.TEXT_GENERATION)
                                        .artifact(onnx)
                                        .artifact(tok)
                                        .build();
                        java.util.List<io.cortexj.spi.repository.ResolvedArtifact> artifacts =
                                new java.util.ArrayList<io.cortexj.spi.repository.ResolvedArtifact>();
                        artifacts.add(fileArtifact(onnx, weightFile.toUri()));
                        artifacts.add(fileArtifact(tok, tokenizerFile.toUri()));
                        return new io.cortexj.spi.repository.ResolvedModel(descriptor, "rev1",
                                io.cortexj.spi.repository.RepositoryId.of("sibling-repo"), artifacts);
                    }

                    private io.cortexj.spi.repository.ResolvedArtifact fileArtifact(
                            final io.cortexj.model.ModelArtifact artifact, final java.net.URI location) {
                        return new io.cortexj.spi.repository.ResolvedArtifact() {
                            @Override
                            public io.cortexj.model.ModelArtifact artifact() {
                                return artifact;
                            }

                            @Override
                            public java.net.URI location() {
                                return location;
                            }

                            @Override
                            public java.util.Map<String, String> downloadHeaders() {
                                return java.util.Collections.emptyMap();
                            }
                        };
                    }
                };
        ModelBundler bundler = new ModelBundler(
                java.util.Collections.singletonList(repository), null);
        Path bundleDir = tempDir.resolve("sibling-bundle");
        bundler.create(ModelReference.of("owner/name"), bundleDir, context(false));

        assertTrue(Files.isRegularFile(bundleDir.resolve("onnx/model.onnx")));
        assertTrue(Files.isRegularFile(bundleDir.resolve("tokenizer.json")));
        Path staged = bundleDir.resolve("onnx/tokenizer.json");
        assertTrue(Files.isRegularFile(staged),
                "tokenizer.json must be staged next to weights in subdirectories");
        assertTrue(java.util.Arrays.equals(tokenizer, Files.readAllBytes(staged)));
        BundleReport report = bundler.verify(bundleDir);
        assertTrue(report.isOk(), "expected clean verify, issues: " + report.issues());
    }

    @Test
    void failedCreateLeavesNoHalfWrittenBundle() throws Exception {
        // audit 2026-10-10: a mid-create failure must not strand a
        // half-written bundle that a later verify misreports as corrupt
        Path good = tempDir.resolve("good.gguf");
        Files.write(good, new byte[]{1, 2, 3});
        final Path missing = tempDir.resolve("definitely-missing.gguf");

        final io.cortexj.model.ModelArtifact goodArtifact =
                new io.cortexj.model.ModelArtifact.Abstract(
                        "half/good.gguf", io.cortexj.model.ArtifactFormat.GGUF,
                        3L, null,
                        java.util.Collections.<String, String>emptyMap()) {
                };
        final io.cortexj.model.ModelArtifact missingArtifact =
                new io.cortexj.model.ModelArtifact.Abstract(
                        "half/missing.gguf", io.cortexj.model.ArtifactFormat.GGUF,
                        3L, null,
                        java.util.Collections.<String, String>emptyMap()) {
                };
        io.cortexj.spi.repository.ModelRepositoryProvider repository =
                new io.cortexj.spi.repository.ModelRepositoryProvider() {
                    @Override
                    public io.cortexj.spi.repository.RepositoryId id() {
                        return io.cortexj.spi.repository.RepositoryId.of("half-repo");
                    }

                    @Override
                    public boolean supports(io.cortexj.model.ModelReference reference) {
                        return true;
                    }

                    @Override
                    public io.cortexj.spi.repository.ResolvedModel resolve(
                            io.cortexj.model.ModelReference reference,
                            io.cortexj.spi.repository.ResolutionContext context) {
                        io.cortexj.model.ModelDescriptor descriptor =
                                io.cortexj.model.ModelDescriptor.builder("half/model")
                                        .revision("rev1")
                                        .task(io.cortexj.model.TaskCapability.TEXT_GENERATION)
                                        .artifact(goodArtifact)
                                        .artifact(missingArtifact)
                                        .build();
                        java.util.List<io.cortexj.spi.repository.ResolvedArtifact> artifacts =
                                new java.util.ArrayList<io.cortexj.spi.repository.ResolvedArtifact>();
                        artifacts.add(fileArtifact(goodArtifact, good.toUri()));
                        artifacts.add(fileArtifact(missingArtifact, missing.toUri()));
                        return new io.cortexj.spi.repository.ResolvedModel(descriptor, "rev1",
                                io.cortexj.spi.repository.RepositoryId.of("half-repo"), artifacts);
                    }

                    private io.cortexj.spi.repository.ResolvedArtifact fileArtifact(
                            final io.cortexj.model.ModelArtifact artifact, final java.net.URI location) {
                        return new io.cortexj.spi.repository.ResolvedArtifact() {
                            @Override
                            public io.cortexj.model.ModelArtifact artifact() {
                                return artifact;
                            }

                            @Override
                            public java.net.URI location() {
                                return location;
                            }

                            @Override
                            public java.util.Map<String, String> downloadHeaders() {
                                return java.util.Collections.emptyMap();
                            }
                        };
                    }
                };
        ModelBundler bundler = new ModelBundler(
                java.util.Collections.singletonList(repository), null);
        final Path bundleDir = tempDir.resolve("half-bundle");
        assertThrows(io.cortexj.error.ArtifactResolutionException.class,
                new org.junit.jupiter.api.function.Executable() {
                    @Override
                    public void execute() {
                        bundler.create(ModelReference.of("half/model"), bundleDir, context(false));
                    }
                });
        assertFalse(Files.exists(bundleDir),
                "a failed create must not leave a half-written bundle directory");
        try (java.nio.file.DirectoryStream<Path> stream =
                     Files.newDirectoryStream(tempDir, "half-bundle.part-*")) {
            for (Path leftover : stream) {
                assertFalse(true, "staging temp must be cleaned up, left: " + leftover);
            }
        }
    }
}
