package io.cortexj.repository.hf;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.cortexj.model.ModelReference;
import io.cortexj.spi.repository.ResolvedModel;
import io.cortexj.spi.repository.ResolutionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HfMetadataCacheTest {

    private HttpServer server;
    private String apiBase;
    private final AtomicInteger apiHits = new AtomicInteger();

    @BeforeEach
    void startServer() throws IOException {
        apiHits.set(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.createContext("/api/models/test-owner/test-model", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                apiHits.incrementAndGet();
                byte[] payload = ("{\"id\":\"test-owner/test-model\",\"sha\":\"rev123\","
                        + "\"siblings\":[{\"rfilename\":\"model.gguf\",\"size\":1234,"
                        + "\"blob\":{\"sha256\":\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\"}}]}"
                ).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            }
        });
        server.start();
        apiBase = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private ResolutionContext online() {
        return ResolutionContext.create(false, null, null, null);
    }

    @Test
    void repeatedResolvesWithinTtlPerformZeroExtraHttpCalls() throws Exception {
        HuggingFaceRepositoryProvider provider = new HuggingFaceRepositoryProvider(apiBase, null);
        ModelReference reference = ModelReference.of("hf:test-owner/test-model");

        ResolvedModel first = provider.resolve(reference, online());
        assertEquals("rev123", first.revision());
        assertEquals(1, apiHits.get(), "the first resolve must fetch metadata once");

        provider.resolve(reference, online());
        provider.resolve(reference, online());
        provider.resolve(reference, online());
        assertEquals(1, apiHits.get(),
                "repeated resolves of the same reference within the TTL must perform zero extra HTTP calls");

        provider.invalidateCachedMetadata();
        provider.resolve(reference, online());
        assertEquals(2, apiHits.get(),
                "explicit invalidation must force a fresh fetch");
    }

    @Test
    void cacheEntryExpiresPerTtl() throws Exception {
        System.setProperty("cortexj.hf.metadata.ttlMillis", "150");
        try {
            HuggingFaceRepositoryProvider provider = new HuggingFaceRepositoryProvider(apiBase, null);
            ModelReference reference = ModelReference.of("hf:test-owner/test-model");
            provider.resolve(reference, online());
            assertEquals(1, apiHits.get());

            Thread.sleep(250);
            provider.resolve(reference, online());
            assertEquals(2, apiHits.get(),
                    "an expired cache entry must be refetched");

            // disabled cache (ttl=0) must always fetch
            System.setProperty("cortexj.hf.metadata.ttlMillis", "0");
            provider.resolve(reference, online());
            assertEquals(3, apiHits.get(),
                    "ttl=0 must disable the metadata cache entirely");
        } finally {
            System.clearProperty("cortexj.hf.metadata.ttlMillis");
        }
    }

    @Test
    void cachedMetadataKeepsChecksumIntact() throws Exception {
        HuggingFaceRepositoryProvider provider = new HuggingFaceRepositoryProvider(apiBase, null);
        ResolvedModel model = provider.resolve(ModelReference.of("hf:test-owner/test-model"), online());
        ResolvedModel cached = provider.resolve(ModelReference.of("hf:test-owner/test-model"), online());
        assertTrue(model.artifacts().get(0).artifact().checksum() != null,
                "the sha256 from the blobs response must survive the cache round-trip (#16)");
        assertEquals(model.artifacts().get(0).artifact().checksum().value(),
                cached.artifacts().get(0).artifact().checksum().value());
    }
}
