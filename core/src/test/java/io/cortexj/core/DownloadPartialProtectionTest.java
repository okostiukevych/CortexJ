package io.cortexj.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.cortexj.cache.CacheEntry;
import io.cortexj.cache.CacheStorage;
import io.cortexj.core.download.DownloadManager;
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
import java.nio.file.attribute.FileTime;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadPartialProtectionTest {

    private static final int PAYLOAD_SIZE = 100;

    private HttpServer server;
    private int port;

    @TempDir
    Path tempDir;

    @BeforeEach
    void startServer() throws IOException {
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

    private ResolvedArtifact artifact(String id, byte[] payload) {
        ModelArtifact model = new ModelArtifact.Abstract(
                id, ArtifactFormat.GGUF, payload.length, null, null) {
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

    /** Serves the payload in 10-byte chunks with 50ms pauses: a slow, in-flight download. */
    private void serveSlowly(final byte[] payload) {
        server.createContext("/models/model.bin", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                drain(exchange);
                exchange.getResponseHeaders().add("Connection", "close");
                exchange.sendResponseHeaders(200, payload.length);
                OutputStream out = exchange.getResponseBody();
                for (int offset = 0; offset < payload.length; offset += 10) {
                    int end = Math.min(offset + 10, payload.length);
                    out.write(java.util.Arrays.copyOfRange(payload, offset, end));
                    out.flush();
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                out.close();
            }
        });
    }

    private static void drain(HttpExchange exchange) throws IOException {
        java.io.InputStream input = exchange.getRequestBody();
        byte[] buffer = new byte[4096];
        while (input.read(buffer) >= 0) {
        }
        input.close();
    }

    private static byte[] payload(int size, byte fill) {
        byte[] data = new byte[size];
        java.util.Arrays.fill(data, fill);
        return data;
    }

    private Path partialFileOf(CacheStorage cache, String repositoryId, String revision, String artifactId) {
        return cache.root().resolve("downloads-partial")
                .resolve(DownloadManager.partialFileName(repositoryId, revision, artifactId));
    }

    @Test
    void evictionDuringActiveDownloadNeverDeletesTheResumeFile() throws Exception {
        byte[] big = payload(PAYLOAD_SIZE, (byte) 'd');
        serveSlowly(big);

        // eviction pressure: a small cache with a filler artifact already in it
        CacheStorage cache = new CacheStorage(tempDir.resolve("cache"), 512L);
        cache.put("hf", "rev1", "filler.gguf", null,
                new java.io.ByteArrayInputStream(payload(400, (byte) 'f')));

        DownloadManager downloads = new DownloadManager(new EventBus(
                Collections.<io.cortexj.observability.PlatformEventListener>emptyList()));
        final ResolvedArtifact artifact = artifact("big.bin", big);
        final Path[] result = new Path[1];
        final RuntimeException[] failure = new RuntimeException[1];
        Thread downloader = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    result[0] = downloads.download(artifact, "hf", "rev2", cache, 0L);
                } catch (RuntimeException e) {
                    failure[0] = e;
                }
            }
        });
        downloader.start();

        // wait until the download is in-flight with partial progress
        Path partialFile = partialFileOf(cache, "hf", "rev2", "big.bin");
        long deadline = System.currentTimeMillis() + 30_000L;
        while (!Files.isRegularFile(partialFile) || Files.size(partialFile) == 0) {
            if (System.currentTimeMillis() > deadline) {
                downloader.join(1000);
                if (failure[0] != null) {
                    throw failure[0];
                }
                throw new AssertionError("the download never produced partial progress");
            }
            Thread.sleep(10);
        }
        Thread.sleep(50);

        // make the partial the oldest entry: without the fix it is the first
        // LRU victim and gets deleted mid-download
        Files.setLastModifiedTime(partialFile,
                FileTime.fromMillis(System.currentTimeMillis() - 3_600_000L));

        cache.evictToFit(1L);

        assertTrue(Files.isRegularFile(partialFile),
                "eviction under pressure must never delete an active download's resume file");
        assertTrue(Files.size(partialFile) > 0, "the resume progress must be intact");

        downloader.join(30_000);
        if (failure[0] != null) {
            throw failure[0];
        }
        assertNotNull(result[0], "the download must complete after the eviction");
        assertEquals(PAYLOAD_SIZE, Files.size(result[0]));
        assertTrue(cache.contains("hf", "rev2", "big.bin"),
                "the downloaded artifact must be stored in the cache");
    }

    @Test
    void stalePartialsSweptByCleanAndAgeEvictionButActiveOnesSurvive() throws Exception {
        CacheStorage cache = new CacheStorage(tempDir.resolve("cache"));
        Path stale = partialFileOf(cache, "hf", "rev1", "old.bin");
        Files.createDirectories(stale.getParent());
        Files.write(stale, payload(50, (byte) 's'));
        Files.setLastModifiedTime(stale,
                FileTime.fromMillis(System.currentTimeMillis() - 3_600_000L));

        // a registered (active) partial, artificially aged: must survive eviction
        Path active = partialFileOf(cache, "hf", "rev2", "busy.bin");
        Files.write(active, payload(50, (byte) 'a'));
        Files.setLastModifiedTime(active,
                FileTime.fromMillis(System.currentTimeMillis() - 3_600_000L));
        cache.registerActivePartial(active);

        boolean staleListed = false;
        boolean activeListed = false;
        for (CacheEntry entry : cache.list()) {
            if (entry.path().equals(stale)) {
                staleListed = true;
            }
            if (entry.path().equals(active)) {
                activeListed = true;
            }
        }
        assertTrue(staleListed && activeListed,
                "cache list() must report resume partials as entries");

        long cutoff = System.currentTimeMillis() - 60_000L;
        long freed = cache.evictOlderThan(cutoff);
        assertTrue(freed > 0, "the stale partial must be swept by the age eviction");
        assertTrue(!Files.exists(stale), "a stale unregistered partial must be swept");
        assertTrue(Files.exists(active),
                "a registered active partial must survive eviction even when old");

        cache.unregisterActivePartial(active);
        cache.clear();
        assertTrue(!Files.exists(active), "cache clean sweeps stale partials");
    }
}
