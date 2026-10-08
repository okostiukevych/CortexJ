package io.cortexj.runtime.remote;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit 2026-10-08: the remote client follows redirects manually so the
 * Bearer token never crosses origins and private targets are blocked.
 */
class HttpJsonClientRedirectTest {

    private HttpServer primary;
    private HttpServer secondary;
    private int primaryPort;
    private int secondaryPort;
    private final List<String> secondaryAuthHeaders = new CopyOnWriteArrayList<String>();
    private volatile String redirectTarget;

    @BeforeEach
    void startServers() throws IOException {
        primary = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        secondary = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        primary.createContext("/v1/models", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                if (redirectTarget != null) {
                    exchange.getResponseHeaders().add("Location", redirectTarget);
                    exchange.sendResponseHeaders(302, -1);
                    exchange.close();
                    return;
                }
                byte[] payload = "{\"data\":[]}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, payload.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(payload);
                }
            }
        });
        secondary.createContext("/v1/models", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                secondaryAuthHeaders.add(String.valueOf(
                        exchange.getRequestHeaders().getFirst("Authorization")));
                byte[] payload = "{\"data\":[]}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, payload.length);
                try (OutputStream output = exchange.getResponseBody()) {
                    output.write(payload);
                }
            }
        });
        primary.start();
        secondary.start();
        primaryPort = primary.getAddress().getPort();
        secondaryPort = secondary.getAddress().getPort();
    }

    @AfterEach
    void stopServers() {
        primary.stop(0);
        secondary.stop(0);
    }

    @Test
    void crossOriginRedirectStripsBearer() throws Exception {
        redirectTarget = "http://127.0.0.1:" + secondaryPort + "/v1/models";
        secondaryAuthHeaders.clear();
        HttpJsonClient client = new HttpJsonClient();
        HttpJsonClient.Response response = client.get(
                "http://127.0.0.1:" + primaryPort, "/v1/models", "secret-key");
        assertTrue(response.isOk(), "redirect must be followed, got: " + response.status);
        assertEquals(1, secondaryAuthHeaders.size());
        assertEquals("null", secondaryAuthHeaders.get(0),
                "the Bearer token must not cross origins, got: " + secondaryAuthHeaders.get(0));
    }

    @Test
    void loopbackBaseUrlBlockedByDefault() {
        // the guard runs on the initial URL too: without the opt-out even the
        // first hop (loopback fixture) is refused. Hop-by-hop validation
        // itself delegates to HttpGuards, covered by HttpGuardsTest.
        System.clearProperty("cortexj.http.allowPrivateHosts");
        try {
            redirectTarget = "http://127.0.0.1:" + secondaryPort + "/v1/models";
            HttpJsonClient client = new HttpJsonClient();
            RuntimeException blocked = assertThrows(RuntimeException.class, () ->
                    client.get("http://127.0.0.1:" + primaryPort, "/v1/models", "secret-key"));
            assertTrue(blocked.getMessage().contains("private"),
                    "loopback base URL must be blocked by default, got: " + blocked.getMessage());
        } finally {
            System.setProperty("cortexj.http.allowPrivateHosts", "true");
        }
    }
}
