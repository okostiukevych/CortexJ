package io.cortexj.runtime.remote;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.cortexj.inference.GenerationRequest;
import io.cortexj.spi.runtime.ModelRuntime;
import io.cortexj.spi.runtime.RuntimeModel;
import io.cortexj.spi.runtime.RuntimeModelRequest;
import io.cortexj.stream.StreamSubscription;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Tag;

@Tag("concurrency")
class RemoteStreamCancelTest {

    private HttpServer server;
    private String baseUrl;
    private final CountDownLatch serverSawDisconnect = new CountDownLatch(1);

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.getAddress().getPort();
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
                    respondJson(exchange, "{\"choices\":[{\"message\":{\"content\":\"x\"}}]}");
                    return;
                }
                // infinite SSE: keeps emitting tokens until the client disconnects
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
                    serverSawDisconnect.countDown();
                }
            }
        });
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
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

    private RuntimeModel loadModel() {
        RemoteOpenAiProvider provider = new RemoteOpenAiProvider();
        ModelRuntime runtime = provider.open(new io.cortexj.spi.runtime.RuntimeOpenRequest(
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
                        return java.util.Collections.singletonMap("remoteModel", "test-model");
                    }
                },
                null,
                io.cortexj.runtime.DeviceType.REMOTE,
                java.util.Collections.<String, Object>emptyMap(),
                java.util.Collections.singletonMap("remote.baseUrl", baseUrl),
                null, null, null, null));
        return runtime.load(new RuntimeModelRequest(
                io.cortexj.model.ModelDescriptor.builder("tck/model").build(),
                null, null, io.cortexj.runtime.DeviceType.REMOTE,
                java.util.Collections.<String, Object>emptyMap()));
    }

    @Test
    void cancelledRemoteStreamDisconnectsAndDeliversNoError() throws Exception {
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
                    "the abandoned remote stream must produce at least one token first");

            abandoned.cancel();

            assertTrue(serverSawDisconnect.await(5, TimeUnit.SECONDS),
                    "cancel() must disconnect the remote SSE connection - the worker "
                            + "must stop reading and the remote endpoint stops generating");
            assertEquals(1, firstError.getCount(),
                    "a deliberately cancelled stream must not deliver an error "
                            + "(latch still at 1 means onError never fired)");

            // a second stream must work immediately on the freed pool thread
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
                    "the second stream must stream after the first was cancelled");
        } finally {
            model.close();
        }
    }
}
