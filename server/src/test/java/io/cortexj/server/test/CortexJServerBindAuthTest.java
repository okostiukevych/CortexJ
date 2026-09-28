package io.cortexj.server.test;

import io.cortexj.CortexJ;
import io.cortexj.model.ModelDescriptor;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelReference;
import io.cortexj.server.CortexJServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CortexJServerBindAuthTest {

    static final class EchoPlatform implements CortexJ {
        @Override
        public io.cortexj.Models models() {
            return new io.cortexj.Models() {
                @Override
                public ModelHandle load(ModelReference reference) {
                    return echoHandle();
                }

                @Override
                public ModelHandle load(io.cortexj.planner.ExecutionRequest request) {
                    return echoHandle();
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
    }

    static ModelHandle echoHandle() {
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
                if (capabilityType == io.cortexj.inference.TextGenerationCapability.class) {
                    return (T) new io.cortexj.inference.TextGenerationCapability() {
                        @Override
                        public io.cortexj.inference.GenerationResponse generate(
                                io.cortexj.inference.GenerationRequest request) {
                            return new io.cortexj.inference.GenerationResponse(
                                    "echo: " + request.prompt(),
                                    io.cortexj.inference.FinishReason.STOP,
                                    new io.cortexj.inference.TokenUsage(1L, 1L), null);
                        }

                        @Override
                        public io.cortexj.stream.TokenStream stream(
                                io.cortexj.inference.GenerationRequest request) {
                            return new io.cortexj.stream.internal.PushTokenStream();
                        }
                    };
                }
                if (capabilityType == io.cortexj.inference.EmbeddingCapability.class) {
                    return (T) new io.cortexj.inference.EmbeddingCapability() {
                        @Override
                        public io.cortexj.inference.EmbeddingResponse embed(
                                io.cortexj.inference.EmbeddingRequest request) {
                            java.util.List<float[]> vectors = new java.util.ArrayList<float[]>();
                            for (int i = 0; i < request.texts().size(); i++) {
                                vectors.add(new float[]{0.5f});
                            }
                            return new io.cortexj.inference.EmbeddingResponse(vectors);
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

    private static int post(URL url, String body, String authHeader) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(5_000);
            if (authHeader != null) {
                connection.setRequestProperty("Authorization", authHeader);
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            OutputStream output = connection.getOutputStream();
            output.write(bytes);
            output.close();
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }

    private static int get(URL url, String authHeader) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(5_000);
            if (authHeader != null) {
                connection.setRequestProperty("Authorization", authHeader);
            }
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }

    @Test
    void defaultStartBindsLoopbackOnly() throws Exception {
        CortexJServer server = new CortexJServer(new EchoPlatform(), "server/test-model");
        try {
            int port = server.start(0);
            assertTrue(server.boundAddress().isLoopbackAddress(),
                    "default bind must be loopback only, got: " + server.boundAddress());
            assertEquals(200, get(new URL("http://127.0.0.1:" + port + "/v1/models"), null));
        } finally {
            server.stop();
        }
    }

    @Test
    void wildcardBindRequiresExplicitOptIn() throws Exception {
        System.setProperty("cortexj.server.bind", "0.0.0.0");
        try {
            CortexJServer server = new CortexJServer(new EchoPlatform(), "server/test-model");
            try {
                server.start(0);
                assertTrue(!server.boundAddress().isLoopbackAddress(),
                        "explicit opt-in must allow non-loopback binding");
            } finally {
                server.stop();
            }
        } finally {
            System.clearProperty("cortexj.server.bind");
        }
    }

    @Test
    void authEnabledRejectsMissingAndWrongKeysOnAllRoutes() throws Exception {
        System.setProperty("cortexj.server.auth", "secret-token");
        CortexJServer server = new CortexJServer(new EchoPlatform(), "server/test-model");
        try {
            int port = server.start(0);
            String base = "http://127.0.0.1:" + port;
            assertEquals(401, get(new URL(base + "/v1/models"), null),
                    "missing key must be rejected on /v1/models");
            assertEquals(401, get(new URL(base + "/v1/models"), "Bearer wrong"),
                    "wrong key must be rejected on /v1/models");
            assertEquals(401, post(new URL(base + "/v1/chat/completions"),
                    "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}", null),
                    "missing key must be rejected on /v1/chat/completions");
            assertEquals(401, post(new URL(base + "/v1/embeddings"), "{\"input\":[\"hi\"]}",
                    "Bearer wrong"),
                    "wrong key must be rejected on /v1/embeddings");
            assertEquals(200, get(new URL(base + "/v1/models"), "Bearer secret-token"),
                    "valid key must work");
            assertEquals(200, post(new URL(base + "/v1/chat/completions"),
                    "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}", "Bearer secret-token"),
                    "valid key must work on chat");
        } finally {
            server.stop();
            System.clearProperty("cortexj.server.auth");
        }
    }
}
