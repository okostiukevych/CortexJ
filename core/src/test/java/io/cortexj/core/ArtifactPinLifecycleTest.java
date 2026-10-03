package io.cortexj.core;

import io.cortexj.CortexJ;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelArtifact;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelReference;
import io.cortexj.spi.repository.ModelRepositoryProvider;
import io.cortexj.spi.repository.RepositoryId;
import io.cortexj.spi.repository.ResolvedArtifact;
import io.cortexj.spi.repository.ResolvedModel;
import io.cortexj.spi.repository.ResolutionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArtifactPinLifecycleTest {

    private static final int PAYLOAD_SIZE = 4096;

    private com.sun.net.httpserver.HttpServer server;
    private int port;

    @TempDir
    Path tempDir;

    @BeforeEach
    void startServer() throws IOException {
        server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(2));
        port = server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void servePayload(final byte[] payload) {
        server.createContext("/models/model.bin", new com.sun.net.httpserver.HttpHandler() {
            @Override
            public void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
                exchange.sendResponseHeaders(200, payload.length);
                OutputStream out = exchange.getResponseBody();
                out.write(payload);
                out.close();
            }
        });
    }

    private static ModelRepositoryProvider httpRepository(final AtomicReference<String> location) {
        return new ModelRepositoryProvider() {
            @Override
            public RepositoryId id() {
                return RepositoryId.of("pin-repo");
            }

            @Override
            public boolean supports(ModelReference reference) {
                return true;
            }

            @Override
            public ResolvedModel resolve(ModelReference reference, ResolutionContext context) {
                ModelArtifact artifact = new ModelArtifact.Abstract("model.bin", ArtifactFormat.GGUF,
                        PAYLOAD_SIZE, null, null) {
                };
                ResolvedArtifact resolvedArtifact = new ResolvedArtifact() {
                    @Override
                    public ModelArtifact artifact() {
                        return artifact;
                    }

                    @Override
                    public URI location() {
                        return URI.create(location.get());
                    }

                    @Override
                    public Map<String, String> downloadHeaders() {
                        return Collections.emptyMap();
                    }
                };
                return new ResolvedModel(
                        io.cortexj.model.ModelDescriptor.builder("pin/test-model")
                                .revision("rev1")
                                .task(io.cortexj.model.TaskCapability.TEXT_GENERATION)
                                .artifact(artifact)
                                .build(),
                        "rev1", RepositoryId.of("pin-repo"),
                        Collections.singletonList(resolvedArtifact));
            }
        };
    }

    private long pinnedMarkerCount(Path work) throws IOException {
        Path cacheRoot = work.resolve("cache");
        if (!Files.isDirectory(cacheRoot)) {
            return 0L;
        }
        final AtomicInteger markers = new AtomicInteger();
        Files.walk(cacheRoot).forEach(file -> {
            if (file.getFileName() != null
                    && file.getFileName().toString().endsWith(".pinned")) {
                markers.incrementAndGet();
            }
        });
        return markers.get();
    }

    @Test
    void loadedModelPinsItsArtifactAndCloseUnpins() throws Exception {
        servePayload(new byte[PAYLOAD_SIZE]);
        final AtomicReference<String> location = new AtomicReference<String>(
                "http://127.0.0.1:" + port + "/models/model.bin");
        DefaultCortexJ platform = (DefaultCortexJ) CortexJ.builder()
                .repositoryProvider(httpRepository(location))
                .runtimeProvider(new EndToEndLoadTest.FakeRuntimeProvider())
                .workingDirectory(tempDir.resolve("work"))
                .build();
        try {
            ModelHandle handle = platform.models().load(ModelReference.of("pin/test-model"));
            assertEquals(1L, pinnedMarkerCount(tempDir.resolve("work")),
                    "a cache-backed artifact must be pinned for the model lifetime");
            handle.close();
            assertEquals(0L, pinnedMarkerCount(tempDir.resolve("work")),
                    "closing the model must unpin its artifact");
        } finally {
            platform.close();
        }
    }

    @Test
    void sharedArtifactStaysPinnedUntilTheLastHandleCloses() throws Exception {
        servePayload(new byte[PAYLOAD_SIZE]);
        final AtomicReference<String> location = new AtomicReference<String>(
                "http://127.0.0.1:" + port + "/models/model.bin");
        DefaultCortexJ platform = (DefaultCortexJ) CortexJ.builder()
                .repositoryProvider(httpRepository(location))
                .runtimeProvider(new EndToEndLoadTest.FakeRuntimeProvider())
                .workingDirectory(tempDir.resolve("shared"))
                .build();
        try {
            ModelHandle first = platform.models().load(ModelReference.of("pin/test-model"));
            ModelHandle second = platform.models().load(ModelReference.of("pin/test-model"));
            assertEquals(1L, pinnedMarkerCount(tempDir.resolve("shared")),
                    "two handles on one artifact => one pin marker");
            first.close();
            assertEquals(1L, pinnedMarkerCount(tempDir.resolve("shared")),
                    "closing one handle must keep the pin while the second still uses the artifact");
            second.close();
            assertEquals(0L, pinnedMarkerCount(tempDir.resolve("shared")),
                    "closing the last handle must unpin");
        } finally {
            platform.close();
        }
    }

    @Test
    void platformCloseUnpinsAllPinnedArtifacts() throws Exception {
        servePayload(new byte[PAYLOAD_SIZE]);
        final AtomicReference<String> location = new AtomicReference<String>(
                "http://127.0.0.1:" + port + "/models/model.bin");
        DefaultCortexJ platform = (DefaultCortexJ) CortexJ.builder()
                .repositoryProvider(httpRepository(location))
                .runtimeProvider(new EndToEndLoadTest.FakeRuntimeProvider())
                .workingDirectory(tempDir.resolve("close-all"))
                .build();
        platform.models().load(ModelReference.of("pin/test-model"));
        assertEquals(1L, pinnedMarkerCount(tempDir.resolve("close-all")));
        platform.close();
        assertEquals(0L, pinnedMarkerCount(tempDir.resolve("close-all")),
                "platform.close() must unpin through closeAll -> handle.close()");
    }

    @Test
    void localFileArtifactsAreNeverPinned() throws Exception {
        final AtomicReference<String> location = new AtomicReference<String>(
                "file:" + tempDir.resolve("local/model.bin").toAbsolutePath());
        Files.createDirectories(tempDir.resolve("local"));
        Files.write(tempDir.resolve("local/model.bin"), new byte[PAYLOAD_SIZE]);
        DefaultCortexJ platform = (DefaultCortexJ) CortexJ.builder()
                .repositoryProvider(httpRepository(location))
                .runtimeProvider(new EndToEndLoadTest.FakeRuntimeProvider())
                .workingDirectory(tempDir.resolve("local-work"))
                .build();
        try {
            platform.models().load(ModelReference.of("pin/test-model"));
            assertEquals(0L, pinnedMarkerCount(tempDir.resolve("local-work")),
                    "a local-file artifact has no cache entry - nothing to pin");
        } finally {
            platform.close();
        }
    }
}
