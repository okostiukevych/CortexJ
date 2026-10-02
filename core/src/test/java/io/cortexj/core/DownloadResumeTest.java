package io.cortexj.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.cortexj.cache.CacheStorage;
import io.cortexj.core.download.DownloadManager;
import io.cortexj.error.ArtifactResolutionException;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.Checksum;
import io.cortexj.model.ModelArtifact;
import io.cortexj.spi.repository.ResolvedArtifact;
import io.cortexj.core.EventBus;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadResumeTest {

    private HttpServer server;
    private int port;
    private final AtomicInteger rangeRequests = new AtomicInteger();
    private final List<String> rangeHeaders = new CopyOnWriteArrayList<String>();
    private byte[] fullPayload;

    @TempDir
    Path tempDir;

    @BeforeEach
    void startServer() throws IOException {
        rangeRequests.set(0);
        rangeHeaders.clear();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        port = server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + port + "/models/model.bin";
    }

    private ResolvedArtifact artifact(byte[] payload, Checksum checksum) {
        ModelArtifact model = new ModelArtifact.Abstract(
                "model.bin", ArtifactFormat.GGUF, payload.length, checksum, null) {
        };
        URI location = URI.create(url());
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

    private static String sha256(byte[] payload) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(payload);
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void interruptedDownloadResumesWithRangeHeader() throws Exception {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            builder.append("0123456789abcdefghijklmnopqrstuvwxyz");
        }
        fullPayload = builder.toString().getBytes(StandardCharsets.UTF_8);
        byte[] firstChunk = java.util.Arrays.copyOfRange(fullPayload, 0, 300);

        server.createContext("/models/model.bin", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                exchange.getResponseHeaders().add("Connection", "close");
                String range = exchange.getRequestHeaders().getFirst("Range");
                if (range != null) {
                    rangeHeaders.add(range);
                    rangeRequests.incrementAndGet();
                    long offset = Long.parseLong(range.replaceAll("[^0-9]", ""));
                    byte[] slice = java.util.Arrays.copyOfRange(fullPayload, (int) offset,
                            fullPayload.length);
                    exchange.getResponseHeaders().add("Content-Range", "bytes=" + offset + "-"
                            + (fullPayload.length - 1) + "/" + fullPayload.length);
                    exchange.sendResponseHeaders(206, slice.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(slice);
                    }
                    return;
                }
                // first request: serve only a prefix, flush it, then close (truncated body)
                exchange.sendResponseHeaders(200, fullPayload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(firstChunk);
                    out.flush();
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                }
            }
        });

        CacheStorage cache = new CacheStorage(tempDir.resolve("cache"));
        DownloadManager downloads = new DownloadManager(new EventBus(
                java.util.Collections.<io.cortexj.observability.PlatformEventListener>emptyList()));
        Checksum checksum = Checksum.sha256(sha256(fullPayload));
        ResolvedArtifact artifact = artifact(fullPayload, checksum);

        Path result = downloads.download(artifact, "resume-repo", "rev1", cache, 0L);
        byte[] stored = Files.readAllBytes(result);
        assertArrayEquals(fullPayload, stored, "resumed download must reconstruct the full payload");
        assertEquals(1, rangeRequests.get(), "exactly one Range resume request expected");
        assertEquals("bytes=300-", rangeHeaders.get(0));
    }

    @Test
    void cancelledDownloadStopsAndKeepsPartialProgress() throws Exception {
        fullPayload = new byte[10_000];
        for (int i = 0; i < fullPayload.length; i++) {
            fullPayload[i] = (byte) i;
        }
        server.createContext("/models/model.bin", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                exchange.getResponseHeaders().add("Connection", "close");
                exchange.sendResponseHeaders(200, fullPayload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    for (int i = 0; i < fullPayload.length; i += 512) {
                        out.write(fullPayload, i, Math.min(512, fullPayload.length - i));
                        out.flush();
                        try {
                            Thread.sleep(5);
                        } catch (InterruptedException ignored) {
                            return;
                        }
                    }
                }
            }
        });

        CacheStorage cache = new CacheStorage(tempDir.resolve("cache"));
        DownloadManager downloads = new DownloadManager(new EventBus(
                java.util.Collections.<io.cortexj.observability.PlatformEventListener>emptyList()));
        final AtomicBoolean cancel = new AtomicBoolean(false);
        Thread canceller = new Thread(() -> {
            try {
                Thread.sleep(80);
            } catch (InterruptedException ignored) {
                return;
            }
            cancel.set(true);
        });
        canceller.start();
        try {
            downloads.download(artifact(fullPayload, null), "cancel-repo", "rev1", cache, 0L, cancel);
        } catch (ArtifactResolutionException expected) {
            assertTrue(expected.getMessage().contains("cancelled"),
                    "cancellation must produce a dedicated error, got: " + expected.getMessage());
        } finally {
            canceller.join(2_000);
        }
        assertFalse(cache.contains("cancel-repo", "rev1", "model.bin"),
                "cancelled download must not enter the cache");
        Path partial = cache.root().resolve("downloads-partial")
                .resolve(DownloadManager.partialFileName("cancel-repo", "rev1", "model.bin"));
        assertTrue(Files.exists(partial), "partial progress must be kept for resume");
    }

    @Test
    void explicitProxyIsUsedForConnections() throws Exception {
        fullPayload = "tiny-payload".getBytes(StandardCharsets.UTF_8);
        AtomicInteger targetHits = new AtomicInteger();
        AtomicInteger proxyHits = new AtomicInteger();

        server.createContext("/models/model.bin", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                exchange.getResponseHeaders().add("Connection", "close");
                targetHits.incrementAndGet();
                exchange.sendResponseHeaders(200, fullPayload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(fullPayload);
                }
            }
        });

        HttpServer proxy = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        proxy.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        proxy.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                exchange.getResponseHeaders().add("Connection", "close");
                proxyHits.incrementAndGet();
                java.io.InputStream body = exchange.getRequestBody();
                while (body.read() >= 0) {
                    // drain
                }
                exchange.sendResponseHeaders(200, fullPayload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(fullPayload);
                }
            }
        });
        proxy.start();
        try {
            CacheStorage cache = new CacheStorage(tempDir.resolve("cache"));
            DownloadManager downloads = new DownloadManager(new EventBus(
                    java.util.Collections.<io.cortexj.observability.PlatformEventListener>emptyList()),
                    "127.0.0.1", String.valueOf(proxy.getAddress().getPort()));

            Path result = downloads.download(artifact(fullPayload, null), "proxy-repo", "rev1", cache, 0L);
            assertArrayEquals(fullPayload, Files.readAllBytes(result));
            assertEquals(2, proxyHits.get(),
                    "redirect probe + download must both go through the explicit proxy");
            assertEquals(0, targetHits.get(), "the target must not be contacted directly");
        } finally {
            proxy.stop(0);
        }
    }

    @Test
    void bad206ContentRangeStartRestartsFromScratch() throws Exception {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            builder.append("0123456789abcdefghijklmnopqrstuvwxyz");
        }
        fullPayload = builder.toString().getBytes(StandardCharsets.UTF_8);
        byte[] firstChunk = java.util.Arrays.copyOfRange(fullPayload, 0, 100);
        final AtomicInteger bodyRequests = new AtomicInteger();

        server.createContext("/models/model.bin", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                exchange.getResponseHeaders().add("Connection", "close");
                String range = exchange.getRequestHeaders().getFirst("Range");
                if (range != null) {
                    // lying proxy: answers 206 from byte 0 but sends the full body
                    rangeHeaders.add(range);
                    rangeRequests.incrementAndGet();
                    exchange.getResponseHeaders().add("Content-Range", "bytes=0-"
                            + (fullPayload.length - 1) + "/" + fullPayload.length);
                    exchange.sendResponseHeaders(206, fullPayload.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(fullPayload);
                    }
                    return;
                }
                int freshRequest = bodyRequests.incrementAndGet();
                if (freshRequest <= 2) {
                    // the first two fresh requests (redirect probe + download):
                    // serve only a prefix, then close (truncated body)
                    exchange.sendResponseHeaders(200, fullPayload.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(firstChunk);
                        out.flush();
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException ignored) {
                            return;
                        }
                    }
                    return;
                }
                exchange.sendResponseHeaders(200, fullPayload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(fullPayload);
                }
            }
        });

        CacheStorage cache = new CacheStorage(tempDir.resolve("cache"));
        DownloadManager downloads = new DownloadManager(new EventBus(
                java.util.Collections.<io.cortexj.observability.PlatformEventListener>emptyList()),
                null, null, 1L);
        Checksum checksum = Checksum.sha256(sha256(fullPayload));
        ResolvedArtifact artifact = artifact(fullPayload, checksum);

        Path result = downloads.download(artifact, "resume-repo", "rev1", cache, 0L);
        byte[] stored = Files.readAllBytes(result);
        assertArrayEquals(fullPayload, stored,
                "after the Content-Range mismatch the download must restart from scratch");
        assertEquals(1, rangeRequests.get(), "exactly one lying 206 resume request expected");
        assertEquals("bytes=100-", rangeHeaders.get(0));
        assertEquals(5, bodyRequests.get(),
                "two truncated fresh requests (probe + download) + one complete fresh request,"
                        + " plus the later redirect probes");
    }

    @Test
    void collidingResumeKeysUseDistinctPartialFiles() throws Exception {
        byte[] payloadA = new byte[1000];
        byte[] payloadB = new byte[1000];
        for (int i = 0; i < payloadA.length; i++) {
            payloadA[i] = (byte) ('a' + (i % 26));
            payloadB[i] = (byte) ('A' + (i % 26));
        }
        final AtomicReference<byte[]> payloadRef = new AtomicReference<byte[]>(payloadA);
        final AtomicBoolean truncateFirst = new AtomicBoolean(true);

        server.createContext("/models/model.bin", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                exchange.getResponseHeaders().add("Connection", "close");
                byte[] data = payloadRef.get();
                String range = exchange.getRequestHeaders().getFirst("Range");
                if (range != null && !truncateFirst.get()) {
                    long offset = Long.parseLong(range.replaceAll("[^0-9]", ""));
                    byte[] slice = java.util.Arrays.copyOfRange(data, (int) offset, data.length);
                    exchange.getResponseHeaders().add("Content-Range", "bytes=" + offset + "-"
                            + (data.length - 1) + "/" + data.length);
                    exchange.sendResponseHeaders(206, slice.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(slice);
                    }
                    return;
                }
                exchange.sendResponseHeaders(200, data.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    if (truncateFirst.get()) {
                        out.write(java.util.Arrays.copyOfRange(data, 0, 500));
                        out.flush();
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException ignored) {
                            return;
                        }
                    } else {
                        out.write(data);
                    }
                }
            }
        });

        CacheStorage cache = new CacheStorage(tempDir.resolve("cache"));
        DownloadManager downloads = new DownloadManager(new EventBus(
                java.util.Collections.<io.cortexj.observability.PlatformEventListener>emptyList()),
                null, null, 1L);
        ResolvedArtifact artifactA = artifact(payloadA, null);
        ResolvedArtifact artifactB = artifact(payloadB, null);

        assertFalse(DownloadManager.partialFileName("team/models", "main", "model.bin")
                        .equals(DownloadManager.partialFileName("team_models", "main", "model.bin")),
                "distinct raw triples must produce distinct partial files");

        try {
            downloads.download(artifactA, "team/models", "main", cache, 0L);
            throw new AssertionError("the truncated download must fail");
        } catch (ArtifactResolutionException expected) {
        }
        Path partialA = cache.root().resolve("downloads-partial")
                .resolve(DownloadManager.partialFileName("team/models", "main", "model.bin"));
        assertTrue(Files.exists(partialA), "the first artifact's partial must be kept");
        assertEquals(500L, Files.size(partialA), "the first artifact's partial must hold its progress");

        truncateFirst.set(false);
        payloadRef.set(payloadB);
        Path result = downloads.download(artifactB, "team_models", "main", cache, 0L);
        assertArrayEquals(payloadB, Files.readAllBytes(result),
                "the second artifact must download its own bytes, not the spliced partial");
        assertTrue(Files.exists(partialA),
                "the first artifact's partial must survive the second download");
        assertEquals(500L, Files.size(partialA),
                "the first artifact's partial must not be spliced or deleted by the collision");
    }
}
