package io.cortexj.server.test;

import io.cortexj.CortexJ;
import io.cortexj.inference.EmbeddingRequest;
import io.cortexj.inference.EmbeddingResponse;
import io.cortexj.inference.GenerationRequest;
import io.cortexj.inference.GenerationResponse;
import io.cortexj.inference.TextGenerationCapability;
import io.cortexj.model.ModelHandle;
import io.cortexj.Models;
import io.cortexj.model.ModelDescriptor;
import io.cortexj.model.ModelReference;
import io.cortexj.server.CortexJServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CortexJServerTest {

    private CortexJServer server;
    private int port;

    static final class FakeModelHandle implements ModelHandle {

        @Override
        public ModelDescriptor descriptor() {
            return ModelDescriptor.builder("server/test-model").build();
        }

        @Override
        public io.cortexj.planner.ExecutionPlan plan() {
            return null;
        }

        @Override
        public io.cortexj.model.ModelCapabilities capabilities() {
            return null;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> T capability(Class<T> capabilityType) {
            if (capabilityType == TextGenerationCapability.class) {
                return (T) new TextGenerationCapability() {
                    @Override
                    public GenerationResponse generate(GenerationRequest request) {
                        return new GenerationResponse("echo: " + request.prompt(),
                                io.cortexj.inference.FinishReason.STOP,
                                new io.cortexj.inference.TokenUsage(3L, 5L), null);
                    }

                    @Override
                    public io.cortexj.stream.TokenStream stream(GenerationRequest request) {
                        io.cortexj.stream.internal.PushTokenStream stream =
                                new io.cortexj.stream.internal.PushTokenStream();
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    Thread.sleep(50);
                                } catch (InterruptedException ignored) {
                                    return;
                                }
                                stream.emit("he", java.util.Collections.<String, String>emptyMap());
                                stream.emit("llo", java.util.Collections.<String, String>emptyMap());
                                stream.complete(io.cortexj.inference.FinishReason.STOP,
                                        new io.cortexj.inference.TokenUsage(1L, 2L));
                            }
                        }).start();
                        return stream;
                    }
                };
            }
            if (capabilityType == io.cortexj.inference.EmbeddingCapability.class) {
                return (T) new io.cortexj.inference.EmbeddingCapability() {
                    @Override
                    public EmbeddingResponse embed(EmbeddingRequest request) {
                        List<float[]> vectors = new ArrayList<float[]>();
                        for (int i = 0; i < request.texts().size(); i++) {
                            vectors.add(new float[]{0.1f, 0.2f, 0.3f});
                        }
                        return new EmbeddingResponse(vectors);
                    }
                };
            }
            throw new IllegalArgumentException("unsupported capability " + capabilityType);
        }

        @Override
        public <T> java.util.Optional<T> capabilityIfSupported(Class<T> capabilityType) {
            return java.util.Optional.empty();
        }

        @Override
        public boolean supports(io.cortexj.model.TaskCapability task) {
            return true;
        }

        @Override
        public io.cortexj.model.ModelLifecycleState state() {
            return io.cortexj.model.ModelLifecycleState.READY;
        }

        @Override
        public void waitForReady() {
        }

        @Override
        public void close() {
        }
    }

    private CortexJ fakePlatform() {
        return new CortexJ() {
            @Override
            public Models models() {
                return new Models() {
                    @Override
                    public ModelHandle load(ModelReference reference) {
                        return new FakeModelHandle();
                    }

                    @Override
                    public ModelHandle load(io.cortexj.planner.ExecutionRequest request) {
                        return new FakeModelHandle();
                    }

                    @Override
                    public java.util.concurrent.Future<ModelHandle> loadAsync(ModelReference reference) {
                        return java.util.concurrent.CompletableFuture.completedFuture(new FakeModelHandle());
                    }

                    @Override
                    public java.util.concurrent.Future<ModelHandle> loadAsync(io.cortexj.planner.ExecutionRequest request) {
                        return java.util.concurrent.CompletableFuture.completedFuture(new FakeModelHandle());
                    }
                };
            }

            @Override
            public io.cortexj.planner.Planner planner() {
                return null;
            }

            @Override
            public io.cortexj.diagnostics.Support support() {
                return null;
            }

            @Override
            public io.cortexj.hardware.EnvironmentProfile environment() {
                return null;
            }

            @Override
            public io.cortexj.hardware.HardwareProfile hardware() {
                return null;
            }

            @Override
            public List<io.cortexj.runtime.RuntimeDescriptor> runtimes() {
                return new ArrayList<io.cortexj.runtime.RuntimeDescriptor>();
            }

            @Override
            public List<io.cortexj.runtime.RuntimeHealthReport> runtimeHealth() {
                return new ArrayList<io.cortexj.runtime.RuntimeHealthReport>();
            }

            @Override
            public String platformModule() {
                return "cortexj-core";
            }

            @Override
            public void close() {
            }
        };
    }

    @BeforeEach
    void startServer() throws IOException {
        server = new CortexJServer(fakePlatform(), "server/test-model");
        port = server.start(0);
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    private com.fasterxml.jackson.databind.JsonNode request(String path, String body)
            throws IOException {
        URL url = new URL("http://127.0.0.1:" + port + path);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body.getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(200, connection.getResponseCode());
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        try (InputStream input = connection.getInputStream()) {
            return mapper.readTree(input);
        } finally {
            connection.disconnect();
        }
    }

    @Test
    void modelsEndpointListsTheLoadedModel() throws IOException {
        URL url = new URL("http://127.0.0.1:" + port + "/v1/models");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        assertEquals(200, connection.getResponseCode());
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode payload = mapper.readTree(connection.getInputStream());
        assertEquals("server/test-model",
                payload.path("data").path(0).path("id").asText());
        connection.disconnect();
    }

    @Test
    void chatCompletionsBridgeTheGenerationCapability() throws IOException {
        com.fasterxml.jackson.databind.JsonNode payload = request("/v1/chat/completions",
                "{\"model\":\"server/test-model\",\"messages\":"
                        + "[{\"role\":\"user\",\"content\":\"hi\"}]}");
        assertEquals("echo: hi", payload.path("choices").path(0)
                .path("message").path("content").asText());
        assertEquals("assistant", payload.path("choices").path(0)
                .path("message").path("role").asText());
        assertEquals("stop", payload.path("choices").path(0)
                .path("finish_reason").asText());
        assertEquals(3, payload.path("usage").path("prompt_tokens").asInt());
        assertEquals(5, payload.path("usage").path("completion_tokens").asInt());
    }

    @Test
    void streamingChatEmitsSseChunksAndDone() throws IOException {
        URL url = new URL("http://127.0.0.1:" + port + "/v1/chat/completions");
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json");
        try (OutputStream output = connection.getOutputStream()) {
            output.write(("{\"model\":\"m\",\"stream\":true,\"messages\":"
                    + "[{\"role\":\"user\",\"content\":\"hi\"}]}")
                    .getBytes(StandardCharsets.UTF_8));
        }
        assertEquals(200, connection.getResponseCode());
        java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
        List<String> tokens = new ArrayList<String>();
        boolean done = false;
        com.fasterxml.jackson.databind.ObjectMapper mapper =
                new com.fasterxml.jackson.databind.ObjectMapper();
        String line;
        while ((line = reader.readLine()) != null) {
            if (!line.startsWith("data:")) {
                continue;
            }
            String data = line.substring(5).trim();
            if (data.equals("[DONE]")) {
                done = true;
                break;
            }
            tokens.add(mapper.readTree(data).path("choices").path(0)
                    .path("delta").path("content").asText());
        }
        connection.disconnect();
        assertTrue(done, "stream must end with [DONE]");
        assertEquals(java.util.Arrays.asList("he", "llo"), tokens);
    }

    @Test
    void embeddingsBridgeTheEmbeddingCapability() throws IOException {
        com.fasterxml.jackson.databind.JsonNode payload = request("/v1/embeddings",
                "{\"model\":\"m\",\"input\":[\"a\",\"b\"]}");
        assertEquals(2, payload.path("data").size());
        assertEquals(3, payload.path("data").path(0).path("embedding").size());
        assertEquals(0.2f, payload.path("data").path(1).path("embedding").path(1)
                .asDouble(), 0.0001);
    }

    @Test
    void stalledStreamDoesNotBlockOtherEndpoints() throws Exception {
        final int stalledPort;
        final CortexJServer stalled;
        {
            CortexJ hangingPlatform = new CortexJ() {
                @Override
                public Models models() {
                    return new Models() {
                        @Override
                        public ModelHandle load(ModelReference reference) {
                            return hangingStreamHandle();
                        }

                        @Override
                        public ModelHandle load(io.cortexj.planner.ExecutionRequest request) {
                            return hangingStreamHandle();
                        }

                        @Override
                        public java.util.concurrent.Future<ModelHandle> loadAsync(ModelReference reference) {
                            return java.util.concurrent.CompletableFuture.completedFuture(hangingStreamHandle());
                        }

                        @Override
                        public java.util.concurrent.Future<ModelHandle> loadAsync(io.cortexj.planner.ExecutionRequest request) {
                            return java.util.concurrent.CompletableFuture.completedFuture(hangingStreamHandle());
                        }
                    };
                }

                @Override
                public io.cortexj.planner.Planner planner() {
                    return null;
                }

                @Override
                public io.cortexj.diagnostics.Support support() {
                    return null;
                }

                @Override
                public io.cortexj.hardware.EnvironmentProfile environment() {
                    return null;
                }

                @Override
                public io.cortexj.hardware.HardwareProfile hardware() {
                    return null;
                }

                @Override
                public List<io.cortexj.runtime.RuntimeDescriptor> runtimes() {
                    return new ArrayList<io.cortexj.runtime.RuntimeDescriptor>();
                }

                @Override
                public List<io.cortexj.runtime.RuntimeHealthReport> runtimeHealth() {
                    return new ArrayList<io.cortexj.runtime.RuntimeHealthReport>();
                }

                @Override
                public String platformModule() {
                    return "cortexj-core";
                }

                @Override
                public void close() {
                }
            };
            stalled = new CortexJServer(hangingPlatform, "server/test-model", 60_000L);
            stalledPort = stalled.start(0);
        }
        try {
            Thread requester = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                    URL url = new URL("http://127.0.0.1:" + stalledPort + "/v1/chat/completions");
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("POST");
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Content-Type", "application/json");
                    try (OutputStream output = connection.getOutputStream()) {
                            output.write(("{\"model\":\"m\",\"stream\":true,\"messages\":"
                                    + "[{\"role\":\"user\",\"content\":\"hi\"}]}")
                                    .getBytes(StandardCharsets.UTF_8));
                        }
                        connection.getInputStream();
                    } catch (IOException connectionClosedByStop) {
                        // expected once the server stops
                    }
                }
            });
            requester.setDaemon(true);
            requester.start();
            Thread.sleep(400);

            long started = System.nanoTime();
            URL modelsUrl = new URL("http://127.0.0.1:" + stalledPort + "/v1/models");
            HttpURLConnection modelsConnection = (HttpURLConnection) modelsUrl.openConnection();
            modelsConnection.setConnectTimeout(2000);
            modelsConnection.setReadTimeout(2000);
            assertEquals(200, modelsConnection.getResponseCode(),
                    "/v1/models must stay responsive while a stream is stalled");
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;
            assertTrue(elapsedMillis < 2000,
                    "/v1/models answered in " + elapsedMillis + "ms - handlers must not serialize");
            modelsConnection.disconnect();
        } finally {
            stalled.stop();
        }
    }

    @Test
    void shutdownHookSequenceStopsServerThenClosesPlatform() throws Exception {
        final java.util.concurrent.atomic.AtomicInteger platformCloses =
                new java.util.concurrent.atomic.AtomicInteger();
        CortexJ countingPlatform = new CortexJ() {
            @Override
            public Models models() {
                return new Models() {
                    @Override
                    public ModelHandle load(ModelReference reference) {
                        return new FakeModelHandle();
                    }

                    @Override
                    public ModelHandle load(io.cortexj.planner.ExecutionRequest request) {
                        return new FakeModelHandle();
                    }

                    @Override
                    public java.util.concurrent.Future<ModelHandle> loadAsync(ModelReference reference) {
                        return java.util.concurrent.CompletableFuture.completedFuture(new FakeModelHandle());
                    }

                    @Override
                    public java.util.concurrent.Future<ModelHandle> loadAsync(io.cortexj.planner.ExecutionRequest request) {
                        return java.util.concurrent.CompletableFuture.completedFuture(new FakeModelHandle());
                    }
                };
            }

            @Override
            public io.cortexj.planner.Planner planner() {
                return null;
            }

            @Override
            public io.cortexj.diagnostics.Support support() {
                return null;
            }

            @Override
            public io.cortexj.hardware.EnvironmentProfile environment() {
                return null;
            }

            @Override
            public io.cortexj.hardware.HardwareProfile hardware() {
                return null;
            }

            @Override
            public List<io.cortexj.runtime.RuntimeDescriptor> runtimes() {
                return new ArrayList<io.cortexj.runtime.RuntimeDescriptor>();
            }

            @Override
            public List<io.cortexj.runtime.RuntimeHealthReport> runtimeHealth() {
                return new ArrayList<io.cortexj.runtime.RuntimeHealthReport>();
            }

            @Override
            public String platformModule() {
                return "cortexj-core";
            }

            @Override
            public void close() {
                platformCloses.incrementAndGet();
            }
        };
        CortexJServer server = new CortexJServer(countingPlatform, "server/test-model");
        Thread hook = io.cortexj.server.CortexJServerMain.shutdownHook(server, countingPlatform);
        hook.start();
        hook.join(5_000);
        assertEquals(1, platformCloses.get(),
                "the shutdown hook must close the platform (and thereby model handles and any "
                        + "llama-server process) on SIGTERM/SIGINT - not just the HTTP layer");
        assertTrue(!server.isRunning(), "the shutdown hook must stop the HTTP server too");
    }

    private ModelHandle hangingStreamHandle() {
        return new ModelHandle() {
            @Override
            public ModelDescriptor descriptor() {
                return ModelDescriptor.builder("server/test-model").build();
            }

            @Override
            public io.cortexj.planner.ExecutionPlan plan() {
                return null;
            }

            @Override
            public io.cortexj.model.ModelCapabilities capabilities() {
                return null;
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T capability(Class<T> capabilityType) {
                if (capabilityType == TextGenerationCapability.class) {
                    return (T) new TextGenerationCapability() {
                        @Override
                        public GenerationResponse generate(GenerationRequest request) {
                            return new GenerationResponse("echo: " + request.prompt(),
                                    io.cortexj.inference.FinishReason.STOP,
                                    new io.cortexj.inference.TokenUsage(1L, 1L), null);
                        }

                        @Override
                        public io.cortexj.stream.TokenStream stream(GenerationRequest request) {
                            io.cortexj.stream.internal.PushTokenStream stream =
                                    new io.cortexj.stream.internal.PushTokenStream();
                            return stream;
                        }
                    };
                }
                throw new IllegalArgumentException("unsupported capability " + capabilityType);
            }

            @Override
            public <T> java.util.Optional<T> capabilityIfSupported(Class<T> capabilityType) {
                return java.util.Optional.empty();
            }

            @Override
            public boolean supports(io.cortexj.model.TaskCapability task) {
                return true;
            }

            @Override
            public io.cortexj.model.ModelLifecycleState state() {
                return io.cortexj.model.ModelLifecycleState.READY;
            }

            @Override
            public void waitForReady() {
            }

            @Override
            public void close() {
            }
        };
    }
}
