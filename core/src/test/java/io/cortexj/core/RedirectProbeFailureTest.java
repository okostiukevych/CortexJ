package io.cortexj.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.cortexj.cache.CacheStorage;
import io.cortexj.core.download.DownloadManager;
import io.cortexj.error.ArtifactResolutionException;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelArtifact;
import io.cortexj.model.ModelReference;
import io.cortexj.spi.repository.ResolvedArtifact;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RedirectProbeFailureTest {

    private HttpServer server;
    private int port;

    @TempDir
    Path tempDir;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        port = server.getAddress().getPort();
        server.createContext("/models/model.bin", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                // accept the connection, then reset it without responding:
                // the client probe's getResponseCode() throws AFTER the socket
                // was opened - exactly the fault-injection the fix needs
                exchange.close();
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private ResolvedArtifact artifact() {
        ModelArtifact model = new ModelArtifact.Abstract(
                "model.bin", ArtifactFormat.GGUF, 15L, null, null) {
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
                return Collections.emptyMap();
            }
        };
    }

    private static Long openFdCount() {
        java.lang.management.OperatingSystemMXBean os =
                java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        if (os instanceof com.sun.management.UnixOperatingSystemMXBean) {
            return Long.valueOf(
                    ((com.sun.management.UnixOperatingSystemMXBean) os).getOpenFileDescriptorCount());
        }
        return null;
    }

    @Test
    void repeatedProbeFailuresLeaveNoLingeringConnections() throws Exception {
        Long before = openFdCount();
        assumeTrue(before != null, "open fd counting is unavailable on this platform");

        CacheStorage cache = new CacheStorage(tempDir.resolve("cache"));
        DownloadManager downloads = new DownloadManager(new EventBus(
                Collections.<io.cortexj.observability.PlatformEventListener>emptyList()));

        for (int attempt = 0; attempt < 50; attempt++) {
            try {
                downloads.download(artifact(), "probe-repo", "rev1", cache, 0L);
            } catch (ArtifactResolutionException expected) {
                // every attempt must fail with the typed error
            }
        }

        Long after = openFdCount();
        assertTrue(after != null);
        assertTrue(after.longValue() <= before.longValue() + 5,
                "repeated probe failures must not accumulate lingering connections: "
                        + "before=" + before + " after=" + after);
    }

    @Test
    void everyAttemptFailsWithTypeError() throws Exception {
        CacheStorage cache = new CacheStorage(tempDir.resolve("cache"));
        DownloadManager downloads = new DownloadManager(new EventBus(
                Collections.<io.cortexj.observability.PlatformEventListener>emptyList()));
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                downloads.download(artifact(), "probe-repo", "rev1", cache, 0L);
                org.junit.jupiter.api.Assertions.fail("expected ArtifactResolutionException");
            } catch (ArtifactResolutionException expected) {
                assertTrue(expected.code().startsWith("CORTEXJ-ARTIFACT"));
            }
        }
    }
}
