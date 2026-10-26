package io.cortexj.repository.local.test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.cortexj.error.ArtifactResolutionException;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelReference;
import io.cortexj.repository.local.bundle.BundleReport;
import io.cortexj.repository.local.bundle.ModelBundler;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BundleDownloadTempCleanupTest {

    private static final int DECLARED_SIZE = 1000;

    @TempDir
    Path tempDir;

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(2));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        // the bundler enforces the SSRF guard like the core download
        // manager - loopback test traffic opts in explicitly
        System.setProperty("cortexj.http.allowPrivateHosts", "true");
    }

    @AfterEach
    void stopServer() {
        System.clearProperty("cortexj.http.allowPrivateHosts");
        server.stop(0);
    }

    private void serveBytes(final byte[] payload) {
        server.createContext("/model.bin", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                drain(exchange);
                exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
                exchange.sendResponseHeaders(200, payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            }
        });
    }

    private void serveNotFound() {
        server.createContext("/model.bin", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                drain(exchange);
                byte[] body = "not found".getBytes();
                exchange.sendResponseHeaders(404, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        });
    }

    private static void drain(HttpExchange exchange) throws IOException {
        java.io.InputStream input = exchange.getRequestBody();
        byte[] buffer = new byte[4096];
        while (input.read(buffer) >= 0) {
            // fully drain the request body before responding
        }
        input.close();
    }

    private ResolvedModel resolvedModel() {
        io.cortexj.model.ModelArtifact artifact = new io.cortexj.model.ModelArtifact.Abstract(
                "model.bin", ArtifactFormat.GGUF, DECLARED_SIZE, null, null) {
        };
        io.cortexj.model.ModelDescriptor descriptor = io.cortexj.model.ModelDescriptor
                .builder("test/bundle-model")
                .revision("rev1")
                .task(io.cortexj.model.TaskCapability.TEXT_GENERATION)
                .artifact(artifact)
                .build();
        final URI location = URI.create(baseUrl + "/model.bin");
        ResolvedArtifact resolvedArtifact = new ResolvedArtifact() {
            @Override
            public io.cortexj.model.ModelArtifact artifact() {
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
        return new ResolvedModel(descriptor, "rev1", RepositoryId.of("test-repo"),
                Collections.singletonList(resolvedArtifact));
    }

    private ModelBundler bundler() {
        final ResolvedModel resolved = resolvedModel();
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
                return resolved;
            }
        };
        return new ModelBundler(Collections.singletonList(repository), null);
    }

    private static boolean anyTempLeft(Path bundleDirectory) throws IOException {
        // audit 2026-10-10: create is atomic now - a failed create leaves no
        // bundle directory at all (which is clean); a surviving directory
        // must contain no staging temps, and neither may its parent
        if (!Files.isDirectory(bundleDirectory)) {
            return stagingLeftovers(bundleDirectory.getParent(),
                    bundleDirectory.getFileName().toString());
        }
        try (java.util.stream.Stream<Path> files = Files.list(bundleDirectory)) {
            if (files.anyMatch(p -> p.getFileName().toString().contains(".part-"))) {
                return true;
            }
        }
        return stagingLeftovers(bundleDirectory.getParent(),
                bundleDirectory.getFileName().toString());
    }

    private static boolean stagingLeftovers(Path parent, String baseName) throws IOException {
        if (parent == null || !Files.isDirectory(parent)) {
            return false;
        }
        try (java.util.stream.Stream<Path> files = Files.list(parent)) {
            return files.anyMatch(p -> p.getFileName().toString().startsWith(baseName + ".part-"));
        }
    }

    @Test
    void truncatedDownloadLeavesNoTempFile() throws Exception {
        // the stub sends only half of the declared size and closes
        serveBytes(new byte[DECLARED_SIZE / 2]);
        Path bundleDir = tempDir.resolve("bundle");
        assertThrows(ArtifactResolutionException.class,
                () -> bundler().create(ModelReference.of("test/bundle-model"), bundleDir,
                        ResolutionContext.create(false, io.cortexj.spi.repository.TokenSupplier.none(),
                                null, null)));
        assertFalse(anyTempLeft(bundleDir),
                "a truncated bundle download must not strand a partial .part- file");
    }

    @Test
    void httpErrorLeavesNoTempFile() throws Exception {
        serveNotFound();
        Path bundleDir = tempDir.resolve("bundle");
        assertThrows(ArtifactResolutionException.class,
                () -> bundler().create(ModelReference.of("test/bundle-model"), bundleDir,
                        ResolutionContext.create(false, io.cortexj.spi.repository.TokenSupplier.none(),
                                null, null)));
        assertFalse(anyTempLeft(bundleDir),
                "a failed bundle download must not leave a temp file");
    }

    @Test
    void successfulDownloadStillUsesAtomicMoveAndLeavesNoTemp() throws Exception {
        byte[] payload = new byte[DECLARED_SIZE];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i % 251);
        }
        serveBytes(payload);
        Path bundleDir = tempDir.resolve("bundle");
        Path bundle = bundler().create(ModelReference.of("test/bundle-model"), bundleDir,
                ResolutionContext.create(false, io.cortexj.spi.repository.TokenSupplier.none(), null, null));

        assertTrue(Files.isRegularFile(bundleDir.resolve("model.bin")),
                "the downloaded artifact must land at the target via the atomic move");
        assertFalse(anyTempLeft(bundleDir), "a successful download must leave no temp");
        assertArrayEquals(payload, Files.readAllBytes(bundleDir.resolve("model.bin")));

        BundleReport report = bundler().verify(bundle);
        assertTrue(report.isOk(), "the bundle must verify cleanly, issues: " + report.issues());
    }

    @Test
    void downloadPerformsSingleGetRequest() throws Exception {
        final java.util.concurrent.atomic.AtomicInteger requestCount =
                new java.util.concurrent.atomic.AtomicInteger();
        final byte[] payload = new byte[DECLARED_SIZE];
        server.createContext("/model.bin", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                requestCount.incrementAndGet();
                drain(exchange);
                exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
                exchange.sendResponseHeaders(200, payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            }
        });
        Path bundleDir = tempDir.resolve("single-get-bundle");
        bundler().create(ModelReference.of("test/bundle-model"), bundleDir,
                ResolutionContext.create(false, io.cortexj.spi.repository.TokenSupplier.none(), null, null));
        org.junit.jupiter.api.Assertions.assertEquals(1, requestCount.get(),
                "downloadInto must not open a second GET request after checking status 200");
    }
}
