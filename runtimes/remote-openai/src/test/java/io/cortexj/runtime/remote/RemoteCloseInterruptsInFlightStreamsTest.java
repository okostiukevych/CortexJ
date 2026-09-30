package io.cortexj.runtime.remote;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.cortexj.inference.GenerationRequest;
import io.cortexj.spi.runtime.ModelRuntime;
import io.cortexj.spi.runtime.RuntimeModel;
import io.cortexj.spi.runtime.RuntimeModelRequest;
import io.cortexj.stream.Token;
import io.cortexj.stream.TokenConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Tag;

@Tag("concurrency")
class RemoteCloseInterruptsInFlightStreamsTest {

    private HttpServer server;
    private String baseUrl;
    private final CountDownLatch stalled = new CountDownLatch(1);
    private final CountDownLatch releaseStub = new CountDownLatch(1);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/v1/chat/completions", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                java.io.InputStream input = exchange.getRequestBody();
                byte[] buffer = new byte[4096];
                StringBuilder body = new StringBuilder();
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    body.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                }
                input.close();
                if (!body.toString().contains("\"stream\":true")) {
                    respondJson(exchange, "{\"choices\":[{\"message\":{\"content\":\"x\"}}]}");
                    return;
                }
                // emit one token, then stall: the client worker blocks in
                // readLine() with a 300s read timeout - only a real socket
                // disconnect (close()) can free it before that
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                OutputStream out = exchange.getResponseBody();
                out.write("data: {\"choices\":[{\"delta\":{\"content\":\"first\"}}]}\n\n"
                        .getBytes(StandardCharsets.UTF_8));
                out.flush();
                stalled.countDown();
                try {
                    releaseStub.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                out.close();
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        releaseStub.countDown();
        server.stop(0);
    }

    private static void respondJson(HttpExchange exchange, String json) throws IOException {
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }

    private ModelRuntime loadRuntime() {
        RemoteOpenAiProvider provider = new RemoteOpenAiProvider();
        return provider.open(new io.cortexj.spi.runtime.RuntimeOpenRequest(
                io.cortexj.model.ModelDescriptor.builder("tck/model")
                        .task(io.cortexj.model.TaskCapability.TEXT_GENERATION).build(),
                new io.cortexj.model.ModelArtifact() {
                    @Override
                    public String id() {
                        return "remote";
                    }

                    @Override
                    public io.cortexj.model.ArtifactFormat format() {
                        return io.cortexj.model.ArtifactFormat.REMOTE;
                    }

                    @Override
                    public long sizeBytes() {
                        return 0;
                    }

                    @Override
                    public io.cortexj.model.Checksum checksum() {
                        return null;
                    }

                    @Override
                    public java.util.Map<String, String> metadata() {
                        return Collections.singletonMap("remoteModel", "test-model");
                    }
                },
                null,
                io.cortexj.runtime.DeviceType.REMOTE,
                Collections.<String, Object>emptyMap(),
                Collections.singletonMap("remote.baseUrl", baseUrl),
                null, null, null, null));
    }

    private static boolean noThreadAliveNamed(String prefix) {
        for (java.util.Map.Entry<Thread, java.lang.StackTraceElement[]> entry
                : Thread.getAllStackTraces().entrySet()) {
            if (entry.getKey().isAlive() && entry.getKey().getName().startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    @Test
    void runtimeCloseUnblocksInFlightSseWorkersImmediately() throws Exception {
        ModelRuntime runtime = loadRuntime();
        RuntimeModel model = runtime.load(new RuntimeModelRequest(
                io.cortexj.model.ModelDescriptor.builder("tck/model").build(),
                null, null, io.cortexj.runtime.DeviceType.REMOTE,
                Collections.<String, Object>emptyMap()));
        io.cortexj.inference.TextGenerationCapability generator =
                (io.cortexj.inference.TextGenerationCapability) model;
        final CountDownLatch firstToken = new CountDownLatch(1);
        final CountDownLatch firstError = new CountDownLatch(1);
        generator.stream(GenerationRequest.prompt("hi")).subscribe(new TokenConsumer() {
            @Override
            public void onToken(Token token) {
                firstToken.countDown();
            }

            @Override
            public void onComplete(io.cortexj.stream.StreamCompletion completion) {
            }

            @Override
            public void onError(io.cortexj.error.CortexJException error) {
                firstError.countDown();
            }
        });
        assertTrue(firstToken.await(10, TimeUnit.SECONDS),
                "the stalled stream must emit its single token");
        assertTrue(stalled.await(10, TimeUnit.SECONDS),
                "the stub must be stalled with the worker blocked in readLine()");

        long started = System.nanoTime();
        runtime.close();
        long closeMillis = (System.nanoTime() - started) / 1_000_000L;

        assertTrue(closeMillis < 5_000,
                "close() must free the blocked SSE worker via disconnect, not wait for "
                        + "the 300s read timeout (took " + closeMillis + "ms)");
        assertEquals(1, firstError.getCount(),
                "a stream interrupted by close() must not deliver an error "
                        + "(deliberate close has cancel semantics)");
        Thread.sleep(200);
        assertTrue(noThreadAliveNamed("cortexj-remote-io"),
                "no library SSE worker thread may survive runtime close()");
        model.close();
    }
}
