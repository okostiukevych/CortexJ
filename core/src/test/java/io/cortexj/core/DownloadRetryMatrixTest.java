package io.cortexj.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.cortexj.cache.CacheStorage;
import io.cortexj.cache.Checksums;
import io.cortexj.core.download.DownloadManager;
import io.cortexj.error.ArtifactResolutionException;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.Checksum;
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
import java.util.Map;import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadRetryMatrixTest {

    private HttpServer server;
    private int port;
    private final AtomicInteger requests = new AtomicInteger();
    private final CopyOnWriteArrayList<Integer> statuses = new CopyOnWriteArrayList<Integer>();
    private final AtomicReference<byte[]> payload = new AtomicReference<byte[]>();

    @TempDir
    Path tempDir;

    @BeforeEach
    void startServer() throws IOException {
        requests.set(0);
        statuses.clear();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        port = server.getAddress().getPort();
        server.createContext("/models/model.bin", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                drain(exchange);
                int attempt = requests.incrementAndGet();
                byte[] range = exchange.getRequestHeaders().getFirst("Range") != null
                        ? new byte[0] : null;
                int status = statusFor(attempt);
                statuses.add(status);
                if (status != 200 && status != 206) {
                    byte[] body = ("error " + status).getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(status, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                    return;
                }
                byte[] data = payload.get();
                long declared = declaredSize();
                boolean resumed = range != null;
                exchange.sendResponseHeaders(resumed ? 206 : 200, resumed
                        ? data.length - Long.parseLong(
                        exchange.getRequestHeaders().getFirst("Range").replaceAll("[^0-9]", ""))
                        : data.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    if (resumed) {
                        long offset = Long.parseLong(
                                exchange.getRequestHeaders().getFirst("Range").replaceAll("[^0-9]", ""));
                        out.write(java.util.Arrays.copyOfRange(data, (int) offset, data.length));
                    } else {
                        out.write(data);
                    }
                }
            }
        });
        server.start();
    }

    private int statusFor(int attempt) {
        Integer[] scripted = scriptedStatuses.get();
        if (attempt <= scripted.length) {
            return scripted[attempt - 1];
        }
        return 200;
    }

    private final AtomicReference<Integer[]> scriptedStatuses = new AtomicReference<Integer[]>(
            new Integer[]{});

    private long declaredSize() {
        byte[] data = payload.get();
        return data == null ? 0 : data.length;
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

    private ResolvedArtifact artifact() {
        ModelArtifact model = new ModelArtifact.Abstract(
                "model.bin", ArtifactFormat.GGUF, payload.get().length, null, null) {
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

    private static String sha256Hex(byte[] data) {
        return Checksums.sha256Hex(data);
    }

    @Test
    void serverErrorsRetriedUpToThreeAttemptsThenTypedFailure() throws Exception {
        payload.set("retries-are-exhausted".getBytes(StandardCharsets.UTF_8));
        scriptedStatuses.set(new Integer[]{500, 502, 503, 500});
        CacheStorage cache = new CacheStorage(tempDir.resolve("cache"));
        DownloadManager downloads = new DownloadManager(new EventBus(
                Collections.<io.cortexj.observability.PlatformEventListener>emptyList()));

        assertThrows(ArtifactResolutionException.class,
                () -> downloads.download(artifact(), "retry-repo", "rev1", cache, 0L),
                "persistent 5xx must end in the typed failure");

        assertEquals(3, requests.get(),
                "the retry loop must attempt exactly MAX_ATTEMPTS times");
        assertEquals(3, statuses.size());
        assertTrue(cache.get("retry-repo", "rev1", "model.bin", null) == null,
                "nothing may be stored after the exhausted retries");
    }

    @Test
    void transientErrorRecoversWithinTheRetryBudget() throws Exception {
        byte[] data = "recovers-after-one-error".getBytes(StandardCharsets.UTF_8);
        payload.set(data);
        scriptedStatuses.set(new Integer[]{503});
        CacheStorage cache = new CacheStorage(tempDir.resolve("cache"));
        DownloadManager downloads = new DownloadManager(new EventBus(
                Collections.<io.cortexj.observability.PlatformEventListener>emptyList()));

        Path result = downloads.download(artifact(), "retry-repo", "rev1", cache, 0L);

        assertArrayEquals(data, Files.readAllBytes(result),
                "the download must succeed after a transient 5xx");
        assertEquals(3, requests.get(),
                "one failed probe + one successful probe + one successful download");
        assertTrue(cache.contains("retry-repo", "rev1", "model.bin"));
    }

    @Test
    void rateLimited429RetriedAndStored() throws Exception {
        byte[] data = "recovers-after-rate-limit".getBytes(StandardCharsets.UTF_8);
        payload.set(data);
        scriptedStatuses.set(new Integer[]{429});
        CacheStorage cache = new CacheStorage(tempDir.resolve("cache"));
        DownloadManager downloads = new DownloadManager(new EventBus(
                Collections.<io.cortexj.observability.PlatformEventListener>emptyList()));

        Path result = downloads.download(artifact(), "retry-repo", "rev1", cache, 0L);

        assertArrayEquals(data, Files.readAllBytes(result),
                "the download must succeed after a 429");
        assertEquals(3, requests.get(),
                "one failed probe + one successful probe + one successful download");
        assertTrue(cache.contains("retry-repo", "rev1", "model.bin"));
    }
}
