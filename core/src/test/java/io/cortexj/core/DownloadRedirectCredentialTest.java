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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DownloadRedirectCredentialTest {

    private static final String BEARER = "Bearer hf_secret_token_value";

    private HttpServer primary;
    private HttpServer secondary;
    private int primaryPort;
    private int secondaryPort;
    private final List<String> secondaryAuthHeaders = new CopyOnWriteArrayList<String>();
    private final List<String> primaryAuthHeaders = new CopyOnWriteArrayList<String>();
    private final AtomicReference<String> primaryRedirectLocation = new AtomicReference<String>();

    @TempDir
    Path tempDir;

    @BeforeEach
    void startServers() throws IOException {
        secondaryAuthHeaders.clear();
        primaryAuthHeaders.clear();
        primary = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        secondary = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        primary.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(2));
        secondary.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(2));
        primaryPort = primary.getAddress().getPort();
        secondaryPort = secondary.getAddress().getPort();

        primary.createContext("/models/model.bin", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                drain(exchange);
                String query = exchange.getRequestURI().getQuery();
                if (query == null || !query.contains("real=1")) {
                    String redirect = primaryRedirectLocation.get();
                    if (redirect != null) {
                        exchange.getResponseHeaders().add("Location", redirect);
                        exchange.sendResponseHeaders(302, -1);
                        exchange.close();
                        return;
                    }
                }
                primaryAuthHeaders.add(String.valueOf(
                        exchange.getRequestHeaders().getFirst("Authorization")));
                byte[] payload = "primary-payload".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            }
        });
        secondary.createContext("/models/model.bin", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                drain(exchange);
                secondaryAuthHeaders.add(String.valueOf(
                        exchange.getRequestHeaders().getFirst("Authorization")));
                byte[] payload = "secondary-paylo".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            }
        });
        primary.start();
        secondary.start();
    }

    @AfterEach
    void stopServers() {
        primary.stop(0);
        secondary.stop(0);
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
                "model.bin", ArtifactFormat.GGUF, 15L, null, null) {
        };
        URI location = URI.create("http://127.0.0.1:" + primaryPort + "/models/model.bin");
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
                return Collections.singletonMap("Authorization", BEARER);
            }
        };
    }

    @Test
    void crossOriginRedirectDoesNotForwardAuthorization() throws Exception {
        // different port = different origin: the token must not reach the secondary
        primaryRedirectLocation.set("http://127.0.0.1:" + secondaryPort + "/models/model.bin");
        CacheStorage cache = new CacheStorage(tempDir.resolve("cache"));
        DownloadManager downloads = new DownloadManager(new EventBus(
                Collections.<io.cortexj.observability.PlatformEventListener>emptyList()));

        Path result = downloads.download(artifact(), "redirect-repo", "rev1", cache, 0L);

        assertEquals("secondary-paylo",
                new String(Files.readAllBytes(result), StandardCharsets.UTF_8),
                "the download must follow the redirect and store the secondary's bytes");
        assertTrue(secondaryAuthHeaders.contains("null"),
                "the cross-origin redirect target must NOT receive the Authorization header");
        assertFalse(secondaryAuthHeaders.contains(BEARER),
                "the bearer token must never be forwarded cross-origin");
    }

    @Test
    void sameOriginRedirectKeepsAuthorization() throws Exception {
        // same host+port = same origin: the HF CDN flow keeps working
        primaryRedirectLocation.set("http://127.0.0.1:" + primaryPort + "/models/model.bin?real=1");
        CacheStorage cache = new CacheStorage(tempDir.resolve("cache"));
        DownloadManager downloads = new DownloadManager(new EventBus(
                Collections.<io.cortexj.observability.PlatformEventListener>emptyList()));

        Path result = downloads.download(artifact(), "redirect-repo", "rev1", cache, 0L);

        assertEquals("primary-payload",
                new String(Files.readAllBytes(result), StandardCharsets.UTF_8));
        assertTrue(primaryAuthHeaders.contains(BEARER),
                "a same-origin redirect must keep the Authorization header (gated HF flows)");
    }

    @Test
    void nonHttpRedirectTargetIsRejectedWithTypeError() throws Exception {
        primaryRedirectLocation.set("file:///etc/passwd");
        CacheStorage cache = new CacheStorage(tempDir.resolve("cache"));
        DownloadManager downloads = new DownloadManager(new EventBus(
                Collections.<io.cortexj.observability.PlatformEventListener>emptyList()));

        assertThrows(ArtifactResolutionException.class,
                () -> downloads.download(artifact(), "redirect-repo", "rev1", cache, 0L),
                "a non-http(s) redirect target must be rejected with a typed error");
        assertTrue(secondaryAuthHeaders.isEmpty(),
                "no request may have reached anywhere after the rejected redirect");
    }
}
