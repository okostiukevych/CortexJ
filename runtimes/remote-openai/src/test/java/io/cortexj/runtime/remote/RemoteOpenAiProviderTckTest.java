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
import java.nio.file.Path;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteOpenAiProviderTckTest extends RuntimeProviderContract {

    private static HttpServer server;
    private static String baseUrl;
    private static final Map<String, String> lastAuthHeader = new ConcurrentHashMap<String, String>();

    @BeforeAll
    static void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(4));
        int port = server.getAddress().getPort();
        baseUrl = "http://127.0.0.1:" + port;

        server.createContext("/v1/models", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                lastAuthHeader.put("models", auth == null ? "" : auth);
                respond(exchange, 200, "{\"object\":\"list\",\"data\":[{\"id\":\"tck-model\"}]}");
            }
        });
        server.createContext("/v1/chat/completions", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                lastAuthHeader.put("chat", auth == null ? "" : auth);
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
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                lastAuthHeader.put("embed", auth == null ? "" : auth);
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
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        java.io.InputStream input = exchange.getRequestBody();
        byte[] chunk = new byte[4096];
        int read;
        while ((read = input.read(chunk)) >= 0) {
            buffer.write(chunk, 0, read);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, int status, String json) throws IOException {
        respond(exchange, status, "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    private static void respond(HttpExchange exchange, int status, String contentType, byte[] payload) throws IOException {
        drain(exchange);
        exchange.getResponseHeaders().add("Connection", "close");
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, payload.length);
        OutputStream output = exchange.getResponseBody();
        output.write(payload);
        output.close();
    }

    private static void drain(HttpExchange exchange) throws IOException {
        java.io.InputStream input = exchange.getRequestBody();
        byte[] sink = new byte[4096];
        while (input.read(sink) >= 0) {
            // discard any pending request body
        }
    }

    private final RemoteOpenAiProvider provider = new RemoteOpenAiProvider();

    @Override
    protected io.cortexj.spi.runtime.RuntimeProvider provider() {
        return provider;
    }

    // audit 2026-10-10: the remote probe needs remote.baseUrl - without the
    // override the three lifecycle/streaming contracts skipped on every
    // machine even though the stub above makes the provider fully testable
    @Override
    protected RuntimeProbeContext contractProbeContext() {
        return contextWithBaseUrl();
    }

    // audit 2026-10-10: server-side execution needs no local file - a
    // dummy path satisfies the streaming contract (load resolves server-side
    // through the stub); the failed-load contract stays skipped above
    @Override
    protected Path loadableArtifactPath() {
        return java.nio.file.Paths.get("tck-dummy/remote-artifact");
    }

    @Override
    protected boolean failedLoadContractApplies() {
        return false;
    }

    @Override
    protected RuntimeOpenRequest openRequest(Path artifactPath) {
        if (artifactPath == null) {
            return new RuntimeOpenRequest(
                    ModelDescriptor.builder("tck/model").revision("rev").task(TaskCapability.TEXT_GENERATION).build(),
                    remoteArtifact(),
                    null,
                    DeviceType.REMOTE,
                    Collections.<String, Object>emptyMap(),
                    Collections.singletonMap("remote.baseUrl", baseUrl),
                    testEnvironment(), testHardware(true),
                    null, null);
        }
        return new RuntimeOpenRequest(
                ModelDescriptor.builder("tck/model").revision("rev").task(TaskCapability.TEXT_GENERATION).build(),
                remoteArtifact(),
                artifactPath,
                DeviceType.REMOTE,
                Collections.<String, Object>emptyMap(),
                Collections.singletonMap("remote.baseUrl", baseUrl),
                testEnvironment(), testHardware(true),
                null, null);
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
    void probeForwardsApiKeyAndRedactsBaseUrlCredentials() {
        String urlWithUserInfo = baseUrl.replace("http://", "http://user:secret-password@");
        RuntimeProbeResult result = provider.probe(new RuntimeProbeContext(
                testEnvironment(), testHardware(true), false,
                mapOf("remote.baseUrl", urlWithUserInfo,
                        "remote.apiKey", "probe-secret-key")));
        assertEquals(io.cortexj.runtime.RuntimeHealth.AVAILABLE, result.health(), result.message());
        assertEquals("Bearer probe-secret-key", lastAuthHeader.get("models"),
                "probe() must send Authorization header when remote.apiKey is configured");
        assertFalse(result.message().contains("secret-password"),
                "probe() status message must redact credentials from baseUrl, got: " + result.message());

        RuntimeProbeResult offlineResult = provider.probe(new RuntimeProbeContext(
                testEnvironment(), testHardware(true), true,
                Collections.singletonMap("remote.baseUrl", baseUrl + "?api_key=secret-query-token")));
        assertFalse(offlineResult.message().contains("secret-query-token"),
                "offline probe() status message must redact query credentials from baseUrl, got: " + offlineResult.message());
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
        // audit #122: the old version of this test never set a key and only
        // asserted getMessage() != null - it could not detect a leak. A real
        // 401 path with a distinctive sentinel key must keep the key out of
        // every error surface (message, details, whole cause chain).
        final String sentinelKey = "sk-sentinel-DO-NOT-LEAK-3f9a2b7c";
        RuntimeOpenRequest openRequest = new RuntimeOpenRequest(
                ModelDescriptor.builder("tck/model").revision("rev").build(),
                remoteArtifact(), null, DeviceType.REMOTE,
                Collections.<String, Object>emptyMap(),
                mapOf("remote.baseUrl", "http://127.0.0.1:1/unreachable",
                        "remote.apiKey", sentinelKey),
                testEnvironment(), testHardware(true), null, null);
        RemoteOpenAiRuntime unreachable = (RemoteOpenAiRuntime) provider.open(openRequest);
        try {
            RuntimeModel model = unreachable.load(new RuntimeModelRequest(
                    ModelDescriptor.builder("tck/model").revision("rev").build(),
                    remoteArtifact(), null, DeviceType.REMOTE,
                    Collections.<String, Object>emptyMap()));
            try {
                ((io.cortexj.inference.TextGenerationCapability) model)
                        .generate(io.cortexj.inference.GenerationRequest.prompt("x"));
                throw new AssertionError("generate against an unreachable endpoint must fail");
            } catch (RuntimeException expected) {
                assertFalse(fullErrorSurface(expected).contains(sentinelKey),
                        "audit #122: the API key must never appear in any error surface, got: "
                                + fullErrorSurface(expected));
            } finally {
                model.close();
            }
        } finally {
            unreachable.close();
        }

        // a 401 from a hostile endpoint: the response body excerpt must be
        // surfaced, the request credential must not
        HttpServer hostile = null;
        try {
            hostile = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            final int[] portHolder = new int[1];
            hostile.createContext("/v1/chat/completions", new HttpHandler() {
                @Override
                public void handle(HttpExchange exchange) throws IOException {
                    readBody(exchange);
                    respond(exchange, 401, "{\"error\":\"invalid_api_key\"}");
                }
            });
            hostile.start();
            portHolder[0] = hostile.getAddress().getPort();
            RuntimeOpenRequest authRequest = new RuntimeOpenRequest(
                    ModelDescriptor.builder("tck/model").revision("rev").build(),
                    remoteArtifact(), null, DeviceType.REMOTE,
                    Collections.<String, Object>emptyMap(),
                    mapOf("remote.baseUrl", "http://127.0.0.1:" + portHolder[0],
                            "remote.apiKey", sentinelKey),
                    testEnvironment(), testHardware(true), null, null);
            RemoteOpenAiRuntime runtime = (RemoteOpenAiRuntime) provider.open(authRequest);
            try {
                RuntimeModel model = runtime.load(new RuntimeModelRequest(
                        ModelDescriptor.builder("tck/model").revision("rev").build(),
                        remoteArtifact(), null, DeviceType.REMOTE,
                        Collections.<String, Object>emptyMap()));
                try {
                    ((io.cortexj.inference.TextGenerationCapability) model)
                            .generate(io.cortexj.inference.GenerationRequest.prompt("x"));
                    throw new AssertionError("generate against a 401 endpoint must fail");
                } catch (RuntimeException expected) {
                    String surface = fullErrorSurface(expected);
                    assertFalse(surface.contains(sentinelKey),
                            "audit #122: the API key must never appear in any error surface, got: "
                                    + surface);
                } finally {
                    model.close();
                }
            } finally {
                runtime.close();
            }
        } catch (java.io.IOException noServer) {
            throw new RuntimeException("could not start the hostile 401 stub", noServer);
        } finally {
            if (hostile != null) {
                hostile.stop(0);
            }
        }
    }

    private static String fullErrorSurface(Throwable failure) {
        StringBuilder surface = new StringBuilder();
        Throwable current = failure;
        int hops = 0;
        while (current != null && hops < 10) {
            surface.append(String.valueOf(current)).append('\n');
            if (current instanceof io.cortexj.error.CortexJException) {
                surface.append(((io.cortexj.error.CortexJException) current).details()).append('\n');
            }
            current = current.getCause();
            hops++;
        }
        return surface.toString();
    }

    private static Map<String, String> mapOf(String first, String firstValue,
                                              String second, String secondValue) {
        Map<String, String> map = new java.util.HashMap<String, String>();
        map.put(first, firstValue);
        map.put(second, secondValue);
        return map;
    }
}
