package io.cortexj.runtime.llamacpp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.cortexj.inference.GenerationRequest;
import io.cortexj.spi.runtime.RuntimeModel;
import io.cortexj.stream.Token;
import io.cortexj.stream.TokenConsumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlamaServerCloseInterruptsStreamsTest {

    private HttpServer server;
    private int port;
    private final CountDownLatch stalled = new CountDownLatch(1);
    private final CountDownLatch releaseStub = new CountDownLatch(1);

    @TempDir
    Path tempDir;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        port = server.getAddress().getPort();
        server.createContext("/health", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            }
        });
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
                    byte[] payload = "{\"choices\":[{\"message\":{\"content\":\"x\"}}]}"
                            .getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, payload.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(payload);
                    }
                    return;
                }
                // emit one token, then stall: the single SSE worker blocks in
                // readLine() with a 300s read timeout - only a real socket
                // disconnect (model close()) can free it before that
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
    }

    @AfterEach
    void stopServer() {
        releaseStub.countDown();
        server.stop(0);
    }

    private RuntimeModel loadModel() throws IOException {
        LlamaCppProvider provider = new LlamaCppProvider(new LlamaServerLauncher() {
            @Override
            public LlamaServerEndpoint launch(Path artifactPath) {
                return new LlamaServerEndpoint() {
                    @Override
                    public String baseUrl() {
                        return "http://127.0.0.1:" + port;
                    }

                    @Override
                    public void stop() {
                    }
                };
            }
        });
        Path gguf = tempDir.resolve("model.gguf");
        Files.write(gguf, "gguf-bytes".getBytes(StandardCharsets.UTF_8));
        io.cortexj.spi.runtime.ModelRuntime runtime = provider.open(
                new io.cortexj.spi.runtime.RuntimeOpenRequest(
                        io.cortexj.model.ModelDescriptor.builder("t/llama").build(),
                        null, gguf, io.cortexj.runtime.DeviceType.CPU,
                        java.util.Collections.<String, Object>emptyMap(),
                        java.util.Collections.<String, String>emptyMap(),
                        null, null, null, null));
        return runtime.load(new io.cortexj.spi.runtime.RuntimeModelRequest(
                io.cortexj.model.ModelDescriptor.builder("t/llama").build(),
                null, gguf, io.cortexj.runtime.DeviceType.CPU,
                java.util.Collections.<String, Object>emptyMap()));
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
    void modelCloseUnblocksTheStalledSseWorkerImmediately() throws Exception {
        RuntimeModel model = loadModel();
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
                "the stub must be stalled with the SSE worker blocked in readLine()");

        long started = System.nanoTime();
        model.close();
        long closeMillis = (System.nanoTime() - started) / 1_000_000L;

        assertTrue(closeMillis < 5_000,
                "model close() must free the blocked SSE worker via disconnect, not wait "
                        + "for the 300s read timeout (took " + closeMillis + "ms)");
        assertEquals(1, firstError.getCount(),
                "a stream interrupted by close() must not deliver an error "
                        + "(deliberate close has cancel semantics)");
        Thread.sleep(200);
        assertTrue(noThreadAliveNamed("cortexj-llama-sse"),
                "no library SSE worker thread may survive model close()");
    }
}
