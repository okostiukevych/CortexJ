package io.cortexj.runtime.llamacpp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.cortexj.inference.GenerationRequest;
import io.cortexj.inference.GenerationResponse;
import io.cortexj.inference.TextGenerationCapability;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelDescriptor;
import io.cortexj.spi.runtime.RuntimeModel;
import io.cortexj.spi.runtime.RuntimeModelRequest;
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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlamaServerBridgeTest {

    private HttpServer server;
    private int port;
    private final AtomicBoolean stopped = new AtomicBoolean(false);

    @TempDir
    Path tempDir;

    @BeforeEach
    void startServer() throws IOException {
        stopped.set(false);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        port = server.getAddress().getPort();
        server.createContext("/health", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                exchange.getResponseHeaders().add("Connection", "close");
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            }
        });
        server.createContext("/v1/chat/completions", new ChatHandler());
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static final class ChatHandler implements HttpHandler {

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            exchange.getResponseHeaders().add("Connection", "close");
            StringBuilder body = new StringBuilder();
            java.io.InputStream input = exchange.getRequestBody();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                body.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            }
            input.close();
            boolean stream = body.toString().contains("\"stream\":true")
                    || body.toString().contains("\"stream\" : true");
            if (!stream) {
                String response = "{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"managed hello\"}}],"
                        + "\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":7}}";
                byte[] payload = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
                return;
            }
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                String[] chunks = {
                        "data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"}}]}\n\n",
                        "data: {\"choices\":[{\"delta\":{\"content\":\"lo\"}}]}\n\n",
                        "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],"
                                + "\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":2}}\n\n",
                        "data: [DONE]\n\n",
                };
                for (String chunk : chunks) {
                    out.write(chunk.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                }
            }
        }
    }

    private LlamaCppProvider providerWithStubEndpoint() {
        return new LlamaCppProvider(new LlamaServerLauncher() {
            @Override
            public LlamaServerEndpoint launch(Path artifactPath) {
                return new LlamaServerEndpoint() {
                    @Override
                    public String baseUrl() {
                        return "http://127.0.0.1:" + port;
                    }

                    @Override
                    public void stop() {
                        stopped.set(true);
                    }
                };
            }
        });
    }

    private RuntimeModel loadModel(LlamaCppProvider provider) throws IOException {
        Path gguf = tempDir.resolve("model.gguf");
        Files.write(gguf, "gguf-bytes".getBytes(StandardCharsets.UTF_8));
        io.cortexj.spi.runtime.ModelRuntime runtime = provider.open(
                new io.cortexj.spi.runtime.RuntimeOpenRequest(
                        ModelDescriptor.builder("t/llama").build(),
                        null, gguf, io.cortexj.runtime.DeviceType.CPU,
                        java.util.Collections.<String, Object>emptyMap(),
                        java.util.Collections.<String, String>emptyMap(),
                        null, null, null, null));
        return runtime.load(new RuntimeModelRequest(
                ModelDescriptor.builder("t/llama").build(),
                null, gguf, io.cortexj.runtime.DeviceType.CPU,
                java.util.Collections.<String, Object>emptyMap()));
    }

    @Test
    void probeReportsServerBridgeWhenBinaryPresent() throws Exception {
        Path fakeServer = tempDir.resolve("llama-server");
        Files.write(fakeServer, "stub".getBytes(StandardCharsets.UTF_8));
        fakeServer.toFile().setExecutable(true);
        System.setProperty(LlamaCppProvider.SERVER_PROPERTY, fakeServer.toAbsolutePath().toString());
        try {
            io.cortexj.spi.runtime.RuntimeProbeResult result =
                    new LlamaCppProvider().probe(new io.cortexj.spi.runtime.RuntimeProbeContext(
                            null, null, false, java.util.Collections.<String, String>emptyMap()));
            assertTrue(result.isUsable(), "llama-server on PATH must make the runtime usable");
            assertTrue(result.message().contains("llama-server"));
        } finally {
            System.clearProperty(LlamaCppProvider.SERVER_PROPERTY);
        }
    }

    @Test
    void bridgeModelGeneratesThroughManagedProcess() throws Exception {
        LlamaCppProvider provider = providerWithStubEndpoint();
        RuntimeModel model = loadModel(provider);
        try {
            TextGenerationCapability generator = (TextGenerationCapability) model;
            GenerationResponse response = generator.generate(GenerationRequest.prompt("hi"));
            assertEquals("managed hello", response.text());
            assertTrue(response.usage().isKnown());
            assertEquals(7L, response.usage().outputTokens());
        } finally {
            model.close();
            assertTrue(stopped.get(), "closing the model must stop the managed llama-server");
        }
    }

    @Test
    void bridgeModelStreamsTokensViaSse() throws Exception {
        LlamaCppProvider provider = providerWithStubEndpoint();
        RuntimeModel model = loadModel(provider);
        try {
            TextGenerationCapability generator = (TextGenerationCapability) model;
            final List<String> tokens = new CopyOnWriteArrayList<String>();
            final CountDownLatch done = new CountDownLatch(1);
            final AtomicReference<io.cortexj.stream.StreamCompletion> completion =
                    new AtomicReference<io.cortexj.stream.StreamCompletion>();
            generator.stream(GenerationRequest.prompt("hi")).subscribe(new TokenConsumer() {
                @Override
                public void onToken(io.cortexj.stream.Token token) {
                    tokens.add(token.text());
                }

                @Override
                public void onComplete(io.cortexj.stream.StreamCompletion streamCompletion) {
                    completion.set(streamCompletion);
                    done.countDown();
                }

                @Override
                public void onError(io.cortexj.error.CortexJException error) {
                    done.countDown();
                }
            });
            assertTrue(done.await(15, TimeUnit.SECONDS), "SSE stream must complete");
            assertEquals(java.util.Arrays.asList("Hel", "lo"), tokens);
            assertTrue(completion.get() != null
                    && completion.get().usage() != null
                    && completion.get().usage().isKnown(), "usage must be parsed from the stream");
        } finally {
            model.close();
        }
    }

    @Test
    void missingArtifactFailsWithActionableError() throws IOException {
        LlamaCppProvider provider = providerWithStubEndpoint();
        io.cortexj.spi.runtime.ModelRuntime runtime = provider.open(
                new io.cortexj.spi.runtime.RuntimeOpenRequest(
                        ModelDescriptor.builder("t/llama").build(),
                        null, tempDir.resolve("missing.gguf"), io.cortexj.runtime.DeviceType.CPU,
                        java.util.Collections.<String, Object>emptyMap(),
                        java.util.Collections.<String, String>emptyMap(),
                        null, null, null, null));
        try {
            runtime.load(new RuntimeModelRequest(
                    ModelDescriptor.builder("t/llama").build(),
                    null, tempDir.resolve("missing.gguf"), io.cortexj.runtime.DeviceType.CPU,
                    java.util.Collections.<String, Object>emptyMap()));
        } catch (io.cortexj.error.ModelLoadException expected) {
            assertEquals("CORTEXJ-LOAD-8001", expected.code());
        }
    }
}
