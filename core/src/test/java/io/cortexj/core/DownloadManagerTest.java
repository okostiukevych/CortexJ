package io.cortexj.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.cortexj.cache.CacheStorage;
import io.cortexj.core.download.DownloadManager;
import io.cortexj.error.ArtifactIntegrityException;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadManagerTest {

    private HttpServer server;
    private int port;
    private final AtomicInteger hits = new AtomicInteger();

    @TempDir
    Path tempDir;

    @BeforeEach
    void startServer() throws IOException {
        hits.set(0);
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        port = server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void serve(String path, final byte[] payload) {
        server.createContext(path, new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                hits.incrementAndGet();
                exchange.getResponseHeaders().add("Content-Type", "application/octet-stream");
                exchange.sendResponseHeaders(200, payload.length);
                OutputStream output = exchange.getResponseBody();
                output.write(payload);
                output.close();
            }
        });
    }

    private byte[] payload(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private String sha256Hex(byte[] bytes) {
        return io.cortexj.cache.Checksums.sha256Hex(bytes);
    }

    private static final class SimpleArtifact implements ModelArtifact {

        private final String id;
        private final long size;
        private final io.cortexj.model.Checksum checksum;

        SimpleArtifact(String id, long size, io.cortexj.model.Checksum checksum) {
            this.id = id;
            this.size = size;
            this.checksum = checksum;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public ArtifactFormat format() {
            return ArtifactFormat.GGUF;
        }

        @Override
        public long sizeBytes() {
            return size;
        }

        @Override
        public io.cortexj.model.Checksum checksum() {
            return checksum;
        }

        @Override
        public java.util.Map<String, String> metadata() {
            return Collections.emptyMap();
        }
    }

    private ResolvedArtifact resolvedArtifact(String id, long size, io.cortexj.model.Checksum checksum, URI location) {
        final SimpleArtifact artifact = new SimpleArtifact(id, size, checksum);
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
            public java.util.Map<String, String> downloadHeaders() {
                return Collections.emptyMap();
            }
        };
    }

    @Test
    void downloadsAndStoresInCache() throws IOException {
        byte[] bytes = payload("gguf-payload-123");
        serve("/good/model.gguf", bytes);
        CacheStorage cache = new CacheStorage(tempDir);
        DownloadManager manager = new DownloadManager(new EventBus(Collections.emptyList()));

        Path path = manager.download(
                resolvedArtifact("model.gguf", bytes.length,
                        io.cortexj.model.Checksum.sha256(sha256Hex(bytes)),
                        URI.create("http://localhost:" + port + "/good/model.gguf")),
                "test-repo", "rev1", cache, 0L);

        assertTrue(Files.exists(path));
        assertEquals("gguf-payload-123", new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
        assertTrue(cache.contains("test-repo", "rev1", "model.gguf"));
    }

    @Test
    void checksumMismatchIsRejected() throws IOException {
        byte[] bytes = payload("tampered");
        serve("/bad/model.gguf", bytes);
        CacheStorage cache = new CacheStorage(tempDir);
        DownloadManager manager = new DownloadManager(new EventBus(Collections.emptyList()));

        io.cortexj.model.Checksum wrong = io.cortexj.model.Checksum.sha256(
                sha256Hex(payload("expected-different")));
        assertThrows(ArtifactIntegrityException.class, () -> manager.download(
                resolvedArtifact("model.gguf", bytes.length, wrong,
                        URI.create("http://localhost:" + port + "/bad/model.gguf")),
                "test-repo", "rev1", cache, 0L));
        assertTrue(tempDir.toAbsolutePath().toString().length() > 0);
    }

    @Test
    void redirectsAreFollowed() throws IOException {
        byte[] bytes = payload("redirected-payload");
        serve("/target/model.gguf", bytes);
        server.createContext("/redirect", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                exchange.getResponseHeaders().add("Location", "/target/model.gguf");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            }
        });
        CacheStorage cache = new CacheStorage(tempDir);
        DownloadManager manager = new DownloadManager(new EventBus(Collections.emptyList()));

        Path path = manager.download(
                resolvedArtifact("model.gguf", bytes.length,
                        io.cortexj.model.Checksum.sha256(sha256Hex(bytes)),
                        URI.create("http://localhost:" + port + "/redirect")),
                "test-repo", "rev1", cache, 0L);
        assertEquals("redirected-payload", new String(Files.readAllBytes(path), StandardCharsets.UTF_8));
    }

    @Test
    void declaredSizeAboveLimitRejectedBeforeNetwork() {
        CacheStorage cache = new CacheStorage(tempDir);
        DownloadManager manager = new DownloadManager(new EventBus(Collections.emptyList()));
        io.cortexj.error.ArtifactResolutionException exception = assertThrows(
                io.cortexj.error.ArtifactResolutionException.class,
                () -> manager.download(
                        resolvedArtifact("huge.gguf", 10_000_000_000L, null,
                                URI.create("http://localhost:" + port + "/good/model.gguf")),
                        "test-repo", "rev1", cache, 1_000L));
        assertEquals("CORTEXJ-ARTIFACT-2004", exception.code());
        assertEquals(0, hits.get());
    }
}
