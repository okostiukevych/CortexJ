package io.cortexj.repository.http.test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelReference;
import io.cortexj.repository.http.HttpRepositoryProvider;
import io.cortexj.spi.repository.ResolvedModel;
import io.cortexj.spi.repository.ResolutionContext;
import io.cortexj.tck.RepositoryProviderContract;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

class HttpRepositoryProviderTckTest extends RepositoryProviderContract {

    private HttpServer server;
    private int port;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.createContext("/models/test-model.gguf", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                exchange.getResponseHeaders().add("Connection", "close");
                byte[] payload = "gguf-payload".getBytes(StandardCharsets.UTF_8);
                if ("HEAD".equals(exchange.getRequestMethod())) {
                    exchange.getResponseHeaders().add("ETag", "\"rev-42\"");
                    exchange.sendResponseHeaders(200, -1);
                    exchange.close();
                    return;
                }
                exchange.sendResponseHeaders(200, payload.length);
                OutputStream output = exchange.getResponseBody();
                output.write(payload);
                output.close();
            }
        });
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + port + "/models/test-model.gguf";
    }

    @Override
    protected HttpRepositoryProvider provider() {
        return new HttpRepositoryProvider();
    }

    @Override
    protected ModelReference knownReference() {
        return ModelReference.of(url());
    }

    @Override
    protected ModelReference unknownReference() {
        return ModelReference.of("this-is-not-a-url");
    }

    @Override
    @org.junit.jupiter.api.Test
    public void supportsIsPrecise() {
        super.supportsIsPrecise();
    }

    @org.junit.jupiter.api.Test
    void resolvesEtagRevisionAndFormat() {
        ResolvedModel resolved = provider().resolve(knownReference(), onlineContext());
        org.junit.jupiter.api.Assertions.assertTrue(resolved.revision().startsWith("etag-rev-42"),
                "ETag must become the immutable revision, got: " + resolved.revision());
        org.junit.jupiter.api.Assertions.assertEquals("test-model.gguf",
                resolved.artifacts().get(0).artifact().id());
        org.junit.jupiter.api.Assertions.assertEquals(ArtifactFormat.GGUF,
                resolved.artifacts().get(0).artifact().format());
        org.junit.jupiter.api.Assertions.assertTrue(resolved.descriptor().trustNote() != null,
                "plain HTTP models must carry a trust note");
    }

    @org.junit.jupiter.api.Test
    void missingFileProducesTypedError() {
        ModelReference missing = ModelReference.of(
                "http://127.0.0.1:" + port + "/models/nope.gguf");
        try {
            provider().resolve(missing, onlineContext());
            org.junit.jupiter.api.Assertions.fail("404 must fail resolution");
        } catch (io.cortexj.error.ModelResolutionException expected) {
            org.junit.jupiter.api.Assertions.assertEquals("CORTEXJ-MODEL-1002",
                    expected.code());
        }
    }

    @org.junit.jupiter.api.Test
    void loopbackIsBlockedWithoutTheIntranetOptOut() {
        // audit 2026-10-07 (#111): the SSRF guard is default-on
        System.clearProperty("cortexj.http.allowPrivateHosts");
        try {
            provider().resolve(knownReference(), onlineContext());
            org.junit.jupiter.api.Assertions.fail("loopback resolution must be blocked by default");
        } catch (io.cortexj.error.ModelResolutionException expected) {
            org.junit.jupiter.api.Assertions.assertEquals("CORTEXJ-MODEL-1001",
                    expected.code());
        } finally {
            System.setProperty("cortexj.http.allowPrivateHosts", "true");
        }
    }

    @org.junit.jupiter.api.Test
    void rejectsUrlWithoutFilenameOrWithEncodedTraversal() {
        org.junit.jupiter.api.Assertions.assertThrows(
                io.cortexj.error.ModelResolutionException.class,
                new org.junit.jupiter.api.function.Executable() {
                    @Override
                    public void execute() {
                        provider().resolve(ModelReference.of("http://127.0.0.1:" + port + "/models/"),
                                onlineContext());
                    }
                });
        org.junit.jupiter.api.Assertions.assertThrows(
                io.cortexj.error.ModelResolutionException.class,
                new org.junit.jupiter.api.function.Executable() {
                    @Override
                    public void execute() {
                        provider().resolve(ModelReference.of("http://127.0.0.1:" + port + "/models/%2e%2e"),
                                onlineContext());
                    }
                });
        org.junit.jupiter.api.Assertions.assertThrows(
                io.cortexj.error.ModelResolutionException.class,
                new org.junit.jupiter.api.function.Executable() {
                    @Override
                    public void execute() {
                        provider().resolve(ModelReference.of("http://127.0.0.1:" + port + "/models/a%2fb.gguf"),
                                onlineContext());
                    }
                });
    }
}
