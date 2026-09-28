package io.cortexj.runtime.llamacpp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.cortexj.inference.GenerationRequest;
import io.cortexj.spi.runtime.RuntimeModel;
import io.cortexj.stream.StreamSubscription;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlamaServerStreamCancelTest {

    private HttpServer server;
    private int port;
    private final AtomicInteger activeServerGenerations = new AtomicInteger();
    private final CountDownLatch serverSawDisconnect = new CountDownLatch(1);

    @TempDir
    Path tempDir;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(8));
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
                StringBuilder body = new StringBuilder();
                java.io.InputStream input = exchange.getRequestBody();
                byte[] buffer = new byte[4096];
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
                // infinite SSE: keeps emitting tokens until the client disconnects
                activeServerGenerations.incrementAndGet();
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, 0);
                try (OutputStream out = exchange.getResponseBody()) {
                    int token = 0;
                    while (true) {
                        out.write(("data: {\"choices\":[{\"delta\":{\"content\":\"t" + token
                                + "\"}}]}\n\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        token++;
                        Thread.sleep(10);
                    }
                } catch (IOException | InterruptedException disconnected) {
                    // the cancelled client's connection ends here
                    serverSawDisconnect.countDown();
                } finally {
                    activeServerGenerations.decrementAndGet();
                }
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
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
        // single-thread SSE executor: exactly the deployment where an
        // abandoned stream used to block every following stream forever
        return runtime.load(new io.cortexj.spi.runtime.RuntimeModelRequest(
                io.cortexj.model.ModelDescriptor.builder("t/llama").build(),
                null, gguf, io.cortexj.runtime.DeviceType.CPU,
                java.util.Collections.<String, Object>emptyMap()));
    }

    @Test
    void cancelDisconnectsTheServerGenerationAndFreesTheSingleSseThread() throws Exception {
        RuntimeModel model = loadModel();
        try {
            io.cortexj.inference.TextGenerationCapability generator =
                    (io.cortexj.inference.TextGenerationCapability) model;

            final CountDownLatch firstToken = new CountDownLatch(1);
            final CountDownLatch firstError = new CountDownLatch(1);
            StreamSubscription abandoned = generator.stream(GenerationRequest.prompt("hi"))
                    .subscribe(new TokenConsumer() {
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
                    "the abandoned stream must produce at least one token first");

            abandoned.cancel();

            assertTrue(serverSawDisconnect.await(5, TimeUnit.SECONDS),
                    "cancel() must disconnect the SSE connection so llama-server "
                            + "stops generating the abandoned completion");
            assertEquals(1, firstError.getCount(),
                    "a deliberately cancelled stream must not deliver an error "
                            + "(latch still at 1 means onError never fired)");

            // the single SSE thread is free: a second stream must start streaming
            // immediately instead of queueing behind the abandoned one forever
            final CountDownLatch secondToken = new CountDownLatch(1);
            generator.stream(GenerationRequest.prompt("hi")).subscribe(new TokenConsumer() {
                @Override
                public void onToken(Token token) {
                    secondToken.countDown();
                }

                @Override
                public void onComplete(io.cortexj.stream.StreamCompletion completion) {
                }

                @Override
                public void onError(io.cortexj.error.CortexJException error) {
                }
            });
            assertTrue(secondToken.await(10, TimeUnit.SECONDS),
                    "the second stream must run on the freed SSE thread - before the fix "
                            + "it queued behind the abandoned stream forever");
        } finally {
            model.close();
        }
    }
}
