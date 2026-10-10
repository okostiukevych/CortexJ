package io.cortexj.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.cortexj.CortexJ;
import io.cortexj.inference.GenerationRequest;
import io.cortexj.inference.GenerationResponse;
import io.cortexj.inference.InferenceRequest;
import io.cortexj.inference.InferenceResponse;
import io.cortexj.inference.TextGenerationCapability;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelArtifact;
import io.cortexj.model.ModelCapabilities;
import io.cortexj.model.ModelDescriptor;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelReference;
import io.cortexj.model.TaskCapability;
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

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real-model verification 2026-10-10: a remote resolution must materialize
 * the resolved {@code tokenizer.json} sidecar next to the cached weight,
 * otherwise runtimes that read it (ONNX/DJL) silently fall back to a hash
 * tokenizer and fail inference on real models.
 */
class TokenizerSidecarTest {

    @TempDir
    Path workDir;

    private static final byte[] WEIGHT_BYTES = payload(64 * 1024);
    private static final byte[] TOKENIZER_BYTES =
            "{\"model\":{\"type\":\"WordPiece\",\"vocab\":{\"[CLS]\":101}}}"
                    .getBytes(StandardCharsets.UTF_8);

    private static byte[] payload(int size) {
        byte[] bytes = new byte[size];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i * 31 + 7);
        }
        return bytes;
    }

    static final class Handler implements com.sun.net.httpserver.HttpHandler {
        final byte[] body;
        final int status;
        final AtomicInteger hits = new AtomicInteger();

        Handler(byte[] body, int status) {
            this.body = body;
            this.status = status;
        }

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            hits.incrementAndGet();
            exchange.getResponseHeaders().add("Connection", "close");
            if (status != 200 || body == null) {
                exchange.sendResponseHeaders(status, -1);
                exchange.close();
                return;
            }
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
            exchange.close();
        }
    }

    static final class SidecarRepository implements ModelRepositoryProvider {
        final URI weightLocation;
        final URI sidecarLocation;

        SidecarRepository(URI weightLocation, URI sidecarLocation) {
            this.weightLocation = weightLocation;
            this.sidecarLocation = sidecarLocation;
        }

        @Override
        public RepositoryId id() {
            return RepositoryId.of("sidecar-repo");
        }

        @Override
        public boolean supports(ModelReference reference) {
            return true;
        }

        @Override
        public ResolvedModel resolve(ModelReference reference, ResolutionContext context) {
            ModelArtifact weight = new ModelArtifact.Abstract(
                    "sidecar-model.gguf", ArtifactFormat.GGUF, WEIGHT_BYTES.length, null, null) {
            };
            ModelArtifact sidecar = new ModelArtifact.Abstract(
                    "sidecar/model/tokenizer.json", ArtifactFormat.TOKENIZER,
                    TOKENIZER_BYTES.length, null, null) {
            };
            List<ModelArtifact> descriptorArtifacts = new ArrayList<ModelArtifact>();
            descriptorArtifacts.add(weight);
            descriptorArtifacts.add(sidecar);
            ModelDescriptor descriptor = ModelDescriptor.builder("sidecar/model")
                    .revision("rev1")
                    .task(TaskCapability.TEXT_GENERATION)
                    .trustNote("test fixture, checksums unverified")
                    .artifacts(descriptorArtifacts)
                    .build();
            List<ResolvedArtifact> artifacts = new ArrayList<ResolvedArtifact>();
            artifacts.add(resolved(weight, weightLocation));
            artifacts.add(resolved(sidecar, sidecarLocation));
            return new ResolvedModel(descriptor, "rev1", RepositoryId.of("sidecar-repo"), artifacts);
        }

        static ResolvedArtifact resolved(final ModelArtifact artifact, final URI location) {            return new ResolvedArtifact() {
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
        }
    }

    static final class RecordingRuntimeProvider implements RuntimeProvider {
        final RuntimeId id = RuntimeId.of("sidecar-runtime");
        final List<Path> loadedPaths = new CopyOnWriteArrayList<Path>();

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
            return RuntimeProbeResult.available("sidecar runtime ready");
        }

        @Override
        public RuntimeSupport evaluate(RuntimeEvaluationRequest request) {
            if (request.artifact() != null && request.artifact().format() == ArtifactFormat.GGUF) {
                return RuntimeSupport.full(DeviceType.CPU);
            }
            return RuntimeSupport.unsupported();
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
                    loadedPaths.add(loadRequest.artifactPath());
                    return new StubModel();
                }

                @Override
                public void close() {
                }
            };
        }
    }

    static final class StubModel implements RuntimeModel, TextGenerationCapability {
        @Override
        public ModelCapabilities capabilities() {
            return ModelCapabilities.of(
                    Collections.singleton(TaskCapability.TEXT_GENERATION), true, false, 1);
        }

        @Override
        public InferenceResponse execute(InferenceRequest request) {
            return InferenceResponse.single("ok");
        }

        @Override
        public CompletionStage<InferenceResponse> executeAsync(InferenceRequest request) {
            return CompletableFuture.completedFuture(execute(request));
        }

        @Override
        public GenerationResponse generate(GenerationRequest request) {
            return new GenerationResponse("ok",
                    io.cortexj.inference.FinishReason.STOP,
                    new io.cortexj.inference.TokenUsage(1, 1), null);
        }

        @Override
        public io.cortexj.stream.TokenStream stream(GenerationRequest request) {
            return io.cortexj.stream.TokenStreams.single("ok");
        }

        @Override
        public void close() {
        }
    }

    @Test
    void httpSidecarIsStagedNextToCachedWeight() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(2));
        Handler weight = new Handler(WEIGHT_BYTES, 200);
        Handler tokenizer = new Handler(TOKENIZER_BYTES, 200);
        server.createContext("/models/sidecar-model.gguf", weight);
        server.createContext("/models/tokenizer.json", tokenizer);
        server.start();
        try {
            int port = server.getAddress().getPort();
            RecordingRuntimeProvider provider = new RecordingRuntimeProvider();
            CortexJ platform = CortexJ.builder()
                    .runtimeProvider(provider)
                    .repositoryProvider(new SidecarRepository(
                            URI.create("http://127.0.0.1:" + port + "/models/sidecar-model.gguf"),
                            URI.create("http://127.0.0.1:" + port + "/models/tokenizer.json")))
                    .workingDirectory(workDir)
                    .build();
            try {
                ModelHandle handle = platform.models().load(ModelReference.of("sidecar/model"));
                try {
                    assertEquals(1, provider.loadedPaths.size());
                    Path weightPath = provider.loadedPaths.get(0);
                    assertArrayEquals(WEIGHT_BYTES, Files.readAllBytes(weightPath));
                    Path staged = weightPath.resolveSibling("tokenizer.json");
                    assertTrue(Files.isRegularFile(staged),
                            "tokenizer.json must be staged next to the cached weight");
                    assertArrayEquals(TOKENIZER_BYTES, Files.readAllBytes(staged),
                            "staged tokenizer bytes must be intact");
                } finally {
                    handle.close();
                }
                int weightHitsAfterFirstLoad = weight.hits.get();
                int tokenizerHitsAfterFirstLoad = tokenizer.hits.get();
                assertTrue(weightHitsAfterFirstLoad >= 1,
                        "the weight must have been fetched over HTTP at least once");
                assertTrue(tokenizerHitsAfterFirstLoad >= 1,
                        "the sidecar must have been fetched over HTTP at least once");

                // second load: weight cache-hit + sibling present, no new fetches
                ModelHandle second = platform.models().load(ModelReference.of("sidecar/model"));
                try {
                    assertEquals(2, provider.loadedPaths.size());
                } finally {
                    second.close();
                }
                assertEquals(weightHitsAfterFirstLoad, weight.hits.get(),
                        "cached weight must not be re-downloaded");
                assertEquals(tokenizerHitsAfterFirstLoad, tokenizer.hits.get(),
                        "present sidecar must not be re-downloaded");
            } finally {
                platform.close();
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void missingSidecarKeepsFallbackBehaviorAndLoadSucceeds() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(2));
        Handler weight = new Handler(WEIGHT_BYTES, 200);
        Handler missing = new Handler(null, 404);
        server.createContext("/models/sidecar-model.gguf", weight);
        server.createContext("/models/tokenizer.json", missing);
        server.start();
        try {
            int port = server.getAddress().getPort();
            RecordingRuntimeProvider provider = new RecordingRuntimeProvider();
            CortexJ platform = CortexJ.builder()
                    .runtimeProvider(provider)
                    .repositoryProvider(new SidecarRepository(
                            URI.create("http://127.0.0.1:" + port + "/models/sidecar-model.gguf"),
                            URI.create("http://127.0.0.1:" + port + "/models/tokenizer.json")))
                    .workingDirectory(workDir)
                    .build();
            try {
                // a repo without tokenizer.json (GGUF-only repos, remote
                // runtimes) must not fail the load: warn and continue
                ModelHandle handle = platform.models().load(ModelReference.of("sidecar/model"));
                try {
                    assertEquals(1, provider.loadedPaths.size());
                    Path weightPath = provider.loadedPaths.get(0);
                    assertArrayEquals(WEIGHT_BYTES, Files.readAllBytes(weightPath));
                    assertFalse(Files.exists(weightPath.resolveSibling("tokenizer.json")),
                            "no sidecar must be staged when the repo has none");
                    String degraded = ((DefaultModelHandle) handle).degradedTokenizerNote();
                    assertTrue(degraded != null && degraded.contains("tokenizer.json"),
                            "the handle must carry the degraded-tokenizer cause, got: " + degraded);
                } finally {
                    handle.close();
                }
            } finally {
                platform.close();
            }
        } finally {
            server.stop(0);
        }

        // offline with a cached weight and no sidecar anywhere, and no
        // network at all (the server is stopped): the load must still
        // succeed with the previous fallback behavior
        RecordingRuntimeProvider offlineProvider = new RecordingRuntimeProvider();
        CortexJ offline = CortexJ.builder()
                .runtimeProvider(offlineProvider)
                .repositoryProvider(new SidecarRepository(
                        URI.create("http://127.0.0.1:1/models/sidecar-model.gguf"),
                        URI.create("http://127.0.0.1:1/models/tokenizer.json")))
                .workingDirectory(workDir)
                .offlineMode(true)
                .build();
        try {
            ModelHandle handle = offline.models().load(ModelReference.of("sidecar/model"));
            try {
                assertEquals(1, offlineProvider.loadedPaths.size());
            } finally {
                handle.close();
            }
        } finally {
            offline.close();
        }
    }

    @Test
    void localFilesAreNeverModifiedBySidecarStaging() throws Exception {
        Path userDir = workDir.resolve("user-models");
        Files.createDirectories(userDir);
        Path userWeight = userDir.resolve("local-model.gguf");
        Files.write(userWeight, WEIGHT_BYTES);
        final URI weightLocation = userWeight.toUri();
        final URI sidecarLocation = userDir.resolve("tokenizer.json").toUri();
        assertFalse(Files.exists(userDir.resolve("tokenizer.json")));

        RecordingRuntimeProvider provider = new RecordingRuntimeProvider();
        ModelRepositoryProvider repository = new ModelRepositoryProvider() {
            @Override
            public RepositoryId id() {
                return RepositoryId.of("local-repo");
            }

            @Override
            public boolean supports(ModelReference reference) {
                return true;
            }

            @Override
            public ResolvedModel resolve(ModelReference reference, ResolutionContext context) {
                ModelArtifact weight = new ModelArtifact.Abstract(
                        "local-model.gguf", ArtifactFormat.GGUF, WEIGHT_BYTES.length, null, null) {
                };
                ModelArtifact sidecar = new ModelArtifact.Abstract(
                        "local/tokenizer.json", ArtifactFormat.TOKENIZER, 10L, null, null) {
                };
                List<ModelArtifact> descriptorArtifacts = new ArrayList<ModelArtifact>();
                descriptorArtifacts.add(weight);
                descriptorArtifacts.add(sidecar);
                ModelDescriptor descriptor = ModelDescriptor.builder("local/model")
                        .revision("rev1")
                        .task(TaskCapability.TEXT_GENERATION)
                        .trustNote("test fixture")
                        .artifacts(descriptorArtifacts)
                        .build();
                List<ResolvedArtifact> artifacts = new ArrayList<ResolvedArtifact>();
                artifacts.add(SidecarRepository.resolved(weight, weightLocation));
                artifacts.add(SidecarRepository.resolved(sidecar, sidecarLocation));
                return new ResolvedModel(descriptor, "rev1", RepositoryId.of("local-repo"), artifacts);
            }
        };
        CortexJ platform = CortexJ.builder()
                .runtimeProvider(provider)
                .repositoryProvider(repository)
                .workingDirectory(workDir.resolve("home"))
                .build();
        try {
            ModelHandle handle = platform.models().load(ModelReference.of("local/model"));
            try {
                assertEquals(userWeight, provider.loadedPaths.get(0));
            } finally {
                handle.close();
            }
        } finally {
            platform.close();
        }
        assertFalse(Files.exists(userDir.resolve("tokenizer.json")),
                "sidecar staging must never write into user-owned directories");
    }

    @Test
    void checksumDeclaredSidecarMismatchFailsTheLoad() throws Exception {
        // audit 2026-10-10: a tampered sidecar with a declared checksum must
        // fail closed, not degrade silently into a mis-tokenizing fallback
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(2));
        Handler weight = new Handler(WEIGHT_BYTES, 200);
        Handler tokenizer = new Handler(TOKENIZER_BYTES, 200);
        server.createContext("/models/poisoned.gguf", weight);
        server.createContext("/models/tokenizer.json", tokenizer);
        server.start();
        try {
            int port = server.getAddress().getPort();
            final ModelArtifact weightArtifact = new ModelArtifact.Abstract(
                    "poisoned.gguf", ArtifactFormat.GGUF, WEIGHT_BYTES.length, null, null) {
            };
            final ModelArtifact sidecarArtifact = new ModelArtifact.Abstract(
                    "poisoned/model/tokenizer.json", ArtifactFormat.TOKENIZER,
                    TOKENIZER_BYTES.length,
                    io.cortexj.model.Checksum.sha256(
                            "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef"),
                    Collections.<String, String>emptyMap()) {
            };
            ModelDescriptor descriptor = ModelDescriptor.builder("poisoned/model")
                    .revision("rev1")
                    .task(TaskCapability.TEXT_GENERATION)
                    .trustNote("test fixture")
                    .artifact(weightArtifact)
                    .artifact(sidecarArtifact)
                    .build();
            final List<ResolvedArtifact> resolvedArtifacts = new ArrayList<ResolvedArtifact>();
            resolvedArtifacts.add(SidecarRepository.resolved(weightArtifact,
                    URI.create("http://127.0.0.1:" + port + "/models/poisoned.gguf")));
            resolvedArtifacts.add(SidecarRepository.resolved(sidecarArtifact,
                    URI.create("http://127.0.0.1:" + port + "/models/tokenizer.json")));
            final ResolvedModel resolved = new ResolvedModel(descriptor, "rev1",
                    RepositoryId.of("poisoned-repo"), resolvedArtifacts);
            ModelRepositoryProvider repository = new ModelRepositoryProvider() {
                @Override
                public RepositoryId id() {
                    return RepositoryId.of("poisoned-repo");
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
            RecordingRuntimeProvider provider = new RecordingRuntimeProvider();
            CortexJ platform = CortexJ.builder()
                    .runtimeProvider(provider)
                    .repositoryProvider(repository)
                    .workingDirectory(workDir)
                    .build();
            try {
                io.cortexj.error.ModelLoadException failure = assertThrows(
                        io.cortexj.error.ModelLoadException.class,
                        new org.junit.jupiter.api.function.Executable() {
                            @Override
                            public void execute() {
                                platform.models().load(ModelReference.of("poisoned/model"));
                            }
                        });
                boolean integritySeen = false;
                for (Throwable current = failure; current != null; current = current.getCause()) {
                    if (current instanceof io.cortexj.error.ArtifactIntegrityException) {
                        integritySeen = true;
                        break;
                    }
                    boolean suppressedSeen = false;
                    for (Throwable suppressed : current.getSuppressed()) {
                        if (suppressed instanceof io.cortexj.error.ArtifactIntegrityException) {
                            suppressedSeen = true;
                            break;
                        }
                    }
                    if (suppressedSeen) {
                        integritySeen = true;
                        break;
                    }
                }
                assertTrue(integritySeen,
                        "checksum mismatch on a declared sidecar must surface as integrity failure");
                assertTrue(provider.loadedPaths.isEmpty(),
                        "no runtime may open when the sidecar fails closed");
            } finally {
                platform.close();
            }
        } finally {
            server.stop(0);
        }
    }
}
