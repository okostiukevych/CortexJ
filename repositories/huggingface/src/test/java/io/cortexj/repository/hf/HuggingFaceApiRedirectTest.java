package io.cortexj.repository.hf;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.cortexj.error.ModelResolutionException;
import io.cortexj.model.ModelReference;
import io.cortexj.spi.repository.ResolvedModel;
import io.cortexj.spi.repository.ResolutionContext;
import io.cortexj.spi.repository.TokenSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HuggingFaceApiRedirectTest {

    private static final String MODEL_JSON = "{\"id\":\"t/m\",\"sha\":\"rev123\","
            + "\"siblings\":[{\"rfilename\":\"model.gguf\",\"size\":1234,"
            + "\"blob\":{\"sha256\":\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\"}}]}";

    private HttpServer server;
    private HttpServer otherServer;
    private String apiBase;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.start();
        apiBase = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
        if (otherServer != null) {
            otherServer.stop(0);
        }
    }

    private ResolutionContext online() {
        return ResolutionContext.create(false, null, null, null);
    }

    private ResolutionContext onlineWithToken() {
        return ResolutionContext.create(false, TokenSupplier.of("secret-token"), null, null);
    }

    private static void serveJson(HttpExchange exchange, String json) throws IOException {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private static void serveRedirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    @Test
    void slugFragmentIsEncodedAndBlobsParamSurvives() throws Exception {
        final AtomicReference<String> requested = new AtomicReference<String>();
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                requested.set(exchange.getRequestURI().toString());
                serveJson(exchange, MODEL_JSON);
            }
        });
        HuggingFaceRepositoryProvider provider = new HuggingFaceRepositoryProvider(apiBase, null);
        ResolvedModel model = provider.resolve(ModelReference.ofHuggingFace("t/na#me"), online());

        assertEquals("rev123", model.revision());
        assertEquals("/api/models/t/na%23me?blobs=true", requested.get(),
                "the slug must be URI-encoded and the blobs parameter must survive");
    }

    @Test
    void plainSlugStillReceivesBlobsParam() throws Exception {
        final AtomicReference<String> requested = new AtomicReference<String>();
        server.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                requested.set(exchange.getRequestURI().toString());
                serveJson(exchange, MODEL_JSON);
            }
        });
        HuggingFaceRepositoryProvider provider = new HuggingFaceRepositoryProvider(apiBase, null);
        provider.resolve(ModelReference.ofHuggingFace("t/m"), online());

        assertEquals("/api/models/t/m?blobs=true", requested.get(),
                "an unencoded-safe slug must keep its separators intact");
    }

    @Test
    void sameOriginRedirectKeepsAuthorization() throws Exception {
        final AtomicReference<String> finalAuth = new AtomicReference<String>();
        server.createContext("/api/models/t/m", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                serveRedirect(exchange, "/api/models/t/m-final");
            }
        });
        server.createContext("/api/models/t/m-final", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                finalAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                serveJson(exchange, MODEL_JSON);
            }
        });
        HuggingFaceRepositoryProvider provider = new HuggingFaceRepositoryProvider(apiBase, null);
        ResolvedModel model = provider.resolve(ModelReference.ofHuggingFace("t/m"), onlineWithToken());

        assertEquals("rev123", model.revision());
        assertEquals("Bearer secret-token", finalAuth.get(),
                "a same-origin redirect hop must keep the Authorization header");
    }

    @Test
    void crossOriginRedirectStripsCredentials() throws Exception {
        otherServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        otherServer.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(2));
        otherServer.start();
        final AtomicReference<String> finalAuth = new AtomicReference<String>();
        otherServer.createContext("/api/models/t/m", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                finalAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                serveJson(exchange, MODEL_JSON);
            }
        });
        server.createContext("/api/models/t/m", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                serveRedirect(exchange,
                        "http://127.0.0.1:" + otherServer.getAddress().getPort() + "/api/models/t/m");
            }
        });
        HuggingFaceRepositoryProvider provider = new HuggingFaceRepositoryProvider(apiBase, null);
        ResolvedModel model = provider.resolve(ModelReference.ofHuggingFace("t/m"), onlineWithToken());

        assertEquals("rev123", model.revision());
        assertNull(finalAuth.get(),
                "a cross-origin redirect hop must strip the Authorization header");
    }

    @Test
    void redirectLoopFailsAfterBoundedHops() {
        server.createContext("/api/models/t/m", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                serveRedirect(exchange, "/api/models/t/m");
            }
        });
        HuggingFaceRepositoryProvider provider = new HuggingFaceRepositoryProvider(apiBase, null);
        ModelResolutionException exception = assertThrows(ModelResolutionException.class,
                () -> provider.resolve(ModelReference.ofHuggingFace("t/m"), online()));
        assertTrue(exception.getMessage().contains("Too many redirects"),
                "a redirect loop must fail after a bounded number of hops, got: " + exception.getMessage());
    }

    @Test
    void nonHttpRedirectSchemeIsRejected() {
        server.createContext("/api/models/t/m", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                serveRedirect(exchange, "ftp://127.0.0.1/api/models/t/m");
            }
        });
        HuggingFaceRepositoryProvider provider = new HuggingFaceRepositoryProvider(apiBase, null);
        ModelResolutionException exception = assertThrows(ModelResolutionException.class,
                () -> provider.resolve(ModelReference.ofHuggingFace("t/m"), onlineWithToken()));
        assertTrue(exception.getMessage().contains("non-http(s)"),
                "a non-http(s) redirect target must be rejected before any credential reaches it");
    }
}
