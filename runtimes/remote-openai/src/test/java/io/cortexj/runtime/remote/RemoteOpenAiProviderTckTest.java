package io.cortexj.runtime.remote;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.cortexj.hardware.EnvironmentProfile;
import io.cortexj.hardware.HardwareProfile;
import io.cortexj.inference.EmbeddingRequest;
import io.cortexj.inference.EmbeddingResponse;
import io.cortexj.inference.FinishReason;
import io.cortexj.inference.GenerationRequest;
import io.cortexj.inference.GenerationResponse;
import io.cortexj.inference.InferenceRequest;
import io.cortexj.inference.InferenceResponse;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelArtifact;
import io.cortexj.model.ModelDescriptor;
import io.cortexj.model.TaskCapability;
import io.cortexj.runtime.DeviceType;
import io.cortexj.spi.runtime.ModelRuntime;
import io.cortexj.spi.runtime.RuntimeModel;
import io.cortexj.spi.runtime.RuntimeModelRequest;
import io.cortexj.spi.runtime.RuntimeOpenRequest;
import io.cortexj.spi.runtime.RuntimeProbeContext;
import io.cortexj.spi.runtime.RuntimeProbeResult;
import io.cortexj.spi.runtime.RuntimeSupport;
import io.cortexj.tck.RuntimeProviderContract;
import io.cortexj.model.ModelReference;
import io.cortexj.spi.repository.ResolvedModel;
import io.cortexj.spi.repository.RepositoryId;
import io.cortexj.planner.ExecutionRequest;
import io.cortexj.spi.repository.ResolvedArtifact;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteOpenAiProviderTckTest extends RuntimeProviderContract {

    private static HttpServer server;
    private static String baseUrl;
    private static final Map<String, String> lastAuthHeader = new ConcurrentHashMap<String, String>();

    @BeforeAll
    static void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        int port = server.getAddress().getPort();
        baseUrl = "http://localhost:" + port;

        server.createContext("/v1/models", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                respond(exchange, 200, "{\"object\":\"list\",\"data\":[{\"id\":\"tck-model\"}]}");
            }
        });
        server.createContext("/v1/chat/completions", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                lastAuthHeader.put("chat", exchange.getRequestHeaders().getFirst("Authorization"));
                String body = readBody(exchange);
                if (body.contains("\"stream\":true") || body.contains("\"stream\" : true")) {
                    respond(exchange, 200, "text/event-stream",
                            ("data: {\"choices\":[{\"delta\":{\"content\":\"Hel\"},\"finish_reason\":null}]}\n\n"
                                    + "data: {\"choices\":[{\"delta\":{\"content\":\"lo\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":2}}\n\n"
                                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8));
                    return;
                }
                respond(exchange, 200, "{\"id\":\"1\",\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"generated-text\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":3}}");
            }
        });
        server.createContext("/v1/embeddings", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                lastAuthHeader.put("embed", exchange.getRequestHeaders().getFirst("Authorization"));
                respond(exchange, 200, "{\"object\":\"list\",\"data\":[{\"index\":0,\"embedding\":[0.1,0.2,0.3]},{\"index\":1,\"embedding\":[0.4,0.5,0.6]}],\"model\":\"tck-model\"}");
            }
        });
        server.createContext("/v1/rerank", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                respond(exchange, 200, "{\"results\":[{\"index\":1,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.2}]}");
            }
        });
        server.start();
    }

    @AfterAll
    static void stopStub() {
        server.stop(0);
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        byte[] bytes = new byte[4096];
        int read = exchange.getRequestBody().read(bytes);
        return read < 0 ? "" : new String(bytes, 0, read, StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        respond(exchange, status, "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] payload) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, payload.length);
        OutputStream output = exchange.getResponseBody();
        output.write(payload);
        output.close();
    }

    private final RemoteOpenAiProvider provider = new RemoteOpenAiProvider();

    @Override
    protected io.cortexj.spi.runtime.RuntimeProvider provider() {
        return provider;
    }

    private RuntimeProbeContext contextWithBaseUrl() {
        return new RuntimeProbeContext(testEnvironment(), testHardware(true), false,
                Collections.singletonMap("remote.baseUrl", baseUrl));
    }

    private RemoteOpenAiRuntime runtime() {
        RuntimeOpenRequest openRequest = new RuntimeOpenRequest(
                ModelDescriptor.builder("tck/model").revision("rev").task(TaskCapability.TEXT_GENERATION).build(),
                remoteArtifact(),
                null,
                DeviceType.REMOTE,
                Collections.<String, Object>emptyMap(),
                Collections.singletonMap("remote.baseUrl", baseUrl),
                testEnvironment(), testHardware(true),
                null, null);
        return (RemoteOpenAiRuntime) provider.open(openRequest);
    }

    private ModelArtifact remoteArtifact() {
        return new ModelArtifact() {
            @Override
            public String id() {
                return "remote";
            }

            @Override
            public ArtifactFormat format() {
                return ArtifactFormat.REMOTE;
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
            public Map<String, String> metadata() {
                return Collections.singletonMap("remoteModel", "tck-model");
            }
        };
    }

    @Test
    void probeSucceedsAgainstHealthyEndpoint() {
        RuntimeProbeResult result = provider.probe(contextWithBaseUrl());
        assertEquals(io.cortexj.runtime.RuntimeHealth.AVAILABLE, result.health(), result.message());
    }

    @Test
    void probeIsMisconfiguredWithoutBaseUrl() {
        RuntimeProbeResult result = provider.probe(new RuntimeProbeContext(
                testEnvironment(), testHardware(true), false, Collections.<String, String>emptyMap()));
        assertEquals(io.cortexj.runtime.RuntimeHealth.MISCONFIGURED, result.health());
        assertTrue(result.error() != null);
        assertTrue(result.error().remediation().size() >= 1);
    }

    @Test
    void generateMapsResponseAndUsage() {
        RemoteOpenAiRuntime runtime = runtime();
        RuntimeModel model = runtime.load(new RuntimeModelRequest(
                ModelDescriptor.builder("tck/model").revision("rev").build(),
                remoteArtifact(), null, DeviceType.REMOTE,
                Collections.<String, Object>emptyMap()));
        io.cortexj.inference.TextGenerationCapability generator =
                (io.cortexj.inference.TextGenerationCapability) model;

        GenerationResponse response = generator.generate(GenerationRequest.prompt("hello"));
        assertEquals("generated-text", response.text());
        assertEquals(FinishReason.STOP, response.finishReason());
        assertEquals(5, response.usage().inputTokens());
        assertEquals(3, response.usage().outputTokens());
        model.close();
        runtime.close();
    }

    @Test
    void streamCollectsTokensUntilDone() throws Exception {
        RemoteOpenAiRuntime runtime = runtime();
        RuntimeModel model = runtime.load(new RuntimeModelRequest(
                ModelDescriptor.builder("tck/model").revision("rev").build(),
                remoteArtifact(), null, DeviceType.REMOTE,
                Collections.<String, Object>emptyMap()));
        io.cortexj.inference.TextGenerationCapability generator =
                (io.cortexj.inference.TextGenerationCapability) model;

        final StringBuilder text = new StringBuilder();
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<FinishReason> finish = new AtomicReference<FinishReason>();
        generator.stream(GenerationRequest.prompt("hi")).subscribe(new io.cortexj.stream.TokenConsumer() {
            @Override
            public void onToken(io.cortexj.stream.Token token) {
                text.append(token.text());
            }

            @Override
            public void onComplete(io.cortexj.stream.StreamCompletion completion) {
                finish.set(completion.finishReason());
                done.countDown();
            }

            @Override
            public void onError(io.cortexj.error.CortexJException error) {
                done.countDown();
            }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS), "stream must complete");
        assertEquals("Hello", text.toString());
        assertEquals(FinishReason.STOP, finish.get());
        model.close();
        runtime.close();
    }

    @Test
    void embeddingsBatchReturnsVectors() {
        RemoteOpenAiRuntime runtime = runtime();
        RuntimeModel model = runtime.load(new RuntimeModelRequest(
                ModelDescriptor.builder("tck/model").revision("rev").build(),
                remoteArtifact(), null, DeviceType.REMOTE,
                Collections.<String, Object>emptyMap()));
        io.cortexj.inference.EmbeddingCapability embeddings =
                (io.cortexj.inference.EmbeddingCapability) model;

        List<String> texts = new ArrayList<String>();
        texts.add("first");
        texts.add("second");
        EmbeddingResponse response = embeddings.embed(EmbeddingRequest.texts(texts));
        assertEquals(2, response.vectors().size());
        assertEquals(3, response.singleVector().length);
        assertEquals(0.1f, response.vectors().get(0)[0], 1e-6);
        assertEquals(0.6f, response.vectors().get(1)[2], 1e-6);
        model.close();
        runtime.close();
    }

    @Test
    void apiKeyUpstreamOnlyAndNeverInErrors() {
        RuntimeOpenRequest openRequest = new RuntimeOpenRequest(
                ModelDescriptor.builder("tck/model").revision("rev").build(),
                remoteArtifact(), null, DeviceType.REMOTE,
                Collections.<String, Object>emptyMap(),
                java.util.Collections.singletonMap("remote.baseUrl", "http://localhost:1/unreachable"),
                testEnvironment(), testHardware(true), null, null);
        RemoteOpenAiRuntime runtime = (RemoteOpenAiRuntime) provider.open(openRequest);
        try {
            runtime.load(new RuntimeModelRequest(
                    ModelDescriptor.builder("tck/model").revision("rev").build(),
                    remoteArtifact(), null, DeviceType.REMOTE,
                    Collections.<String, Object>emptyMap())).close();
            InferenceRequest request = InferenceRequest.builder().input("x").meta("task", "generation").build();
        } catch (RuntimeException expected) {
            assertTrue(expected.getMessage() != null);
        } finally {
            runtime.close();
        }
    }
}
