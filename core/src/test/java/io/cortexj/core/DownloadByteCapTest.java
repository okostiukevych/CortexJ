package io.cortexj.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.cortexj.cache.CacheStorage;
import io.cortexj.core.download.DownloadManager;
import io.cortexj.error.ArtifactResolutionException;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelArtifact;
import io.cortexj.spi.repository.ResolvedArtifact;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DownloadByteCapTest {

    private static final long LIMIT = 1024L;

    private HttpServer server;
    private int port;
    private final AtomicInteger bodyRequests = new AtomicInteger();

    @TempDir
    Path tempDir;

    @BeforeEach
    void startServer() throws IOException {
        bodyRequests.set(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        port = server.getAddress().getPort();
        server.createContext("/models/model.bin", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                drain(exchange);
                bodyRequests.incrementAndGet();
                exchange.getResponseHeaders().add("Connection", "close");
                // responseLength 0 = chunked transfer: no trustworthy Content-Length
                exchange.sendResponseHeaders(200, 0);
                byte[] chunk = new byte[512];
                java.util.Arrays.fill(chunk, (byte) 'x');
                try (OutputStream out = exchange.getResponseBody()) {
                    for (int i = 0; i < 16; i++) {
                        out.write(chunk);
                        out.flush();
                    }
                }
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static void drain(HttpExchange exchange) throws IOException {
        java.io.InputStream input = exchange.getRequestBody();
        byte[] buffer = new byte[4096];
        while (input.read(buffer) >= 0) {
        }
        input.close();
    }

    private ResolvedArtifact artifact(final long declaredSize) {
        ModelArtifact model = new ModelArtifact.Abstract(
                "model.bin", ArtifactFormat.GGUF, declaredSize, null, null) {
        };
        URI location = URI.create("http://127.0.0.1:" + port + "/models/model.bin");
        return new ResolvedArtifact() {
            @Override
            public ModelArtifact artifact() {
                return model;
            }

            @Override
            public URI location() {
                return location;
            }

            @Override
            public Map<String, String> downloadHeaders() {
                return Collections.singletonMap("Connection", "close");
            }
        };
    }

    @Test
    void unboundedStreamIsCappedAtConfiguredLimit() throws Exception {
        CacheStorage cache = new CacheStorage(tempDir.resolve("cache"));
        DownloadManager downloads = new DownloadManager(new EventBus(
                Collections.<io.cortexj.observability.PlatformEventListener>emptyList()));

        ArtifactResolutionException exception = assertThrows(ArtifactResolutionException.class,
                () -> downloads.download(artifact(0L), "cap-repo", "rev1", cache, LIMIT),
                "an unbounded stream must be aborted with the typed error");

        assertEquals("CORTEXJ-ARTIFACT-2004", exception.code(),
                "the stream cap must surface as ARTIFACT_TOO_LARGE");
        assertEquals(1, bodyRequests.get(),
                "the cap failure must not be retried: one download request only");
        Path partial = cache.root().resolve("downloads-partial")
                .resolve(DownloadManager.partialFileName("cap-repo", "rev1", "model.bin"));
        assertFalse(Files.exists(partial), "the partial must be deleted when the cap is exceeded");
        assertNull(cache.get("cap-repo", "rev1", "model.bin", null),
                "nothing may be stored for an over-cap stream");
    }

    @Test
    void lyingDeclaredSizeIsCappedDuringStreaming() throws Exception {
        CacheStorage cache = new CacheStorage(tempDir.resolve("cache"));
        DownloadManager downloads = new DownloadManager(new EventBus(
                Collections.<io.cortexj.observability.PlatformEventListener>emptyList()));

        ArtifactResolutionException exception = assertThrows(ArtifactResolutionException.class,
                () -> downloads.download(artifact(10L), "cap-repo", "rev1", cache, LIMIT),
                "a server declaring a small size but streaming more must still be capped");

        assertEquals("CORTEXJ-ARTIFACT-2004", exception.code(),
                "the declared-size check passes but the stream must still be capped");
        assertEquals(1, bodyRequests.get(), "the cap failure must not be retried");
        Path partial = cache.root().resolve("downloads-partial")
                .resolve(DownloadManager.partialFileName("cap-repo", "rev1", "model.bin"));
        assertFalse(Files.exists(partial), "the partial must be deleted when the cap is exceeded");
    }
}
