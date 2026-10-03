package io.cortexj.server.test;

import io.cortexj.CortexJ;
import io.cortexj.inference.GenerationRequest;
import io.cortexj.inference.GenerationResponse;
import io.cortexj.inference.TextGenerationCapability;
import io.cortexj.model.ModelDescriptor;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelReference;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class StubRuntime {

    static final class ThrowingGenerator implements TextGenerationCapability {

        private final RuntimeException failure;

        ThrowingGenerator(RuntimeException failure) {
            this.failure = failure;
        }

        @Override
        public GenerationResponse generate(GenerationRequest request) {
            throw failure;
        }

        @Override
        public io.cortexj.stream.TokenStream stream(GenerationRequest request) {
            throw failure;
        }
    }

    private StubRuntime() {
    }

    static ModelHandle handle(final TextGenerationCapability generator) {
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
                    return (T) generator;
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

    static CortexJ platform(final ModelHandle handle) {
        return new CortexJ() {
            @Override
            public io.cortexj.Models models() {
                return new io.cortexj.Models() {
                    @Override
                    public ModelHandle load(ModelReference reference) {
                        return handle;
                    }

                    @Override
                    public ModelHandle load(io.cortexj.planner.ExecutionRequest request) {
                        return handle;
                    }

                    @Override
                    public java.util.concurrent.Future<ModelHandle> loadAsync(ModelReference reference) {
                        return java.util.concurrent.CompletableFuture.completedFuture(handle);
                    }

                    @Override
                    public java.util.concurrent.Future<ModelHandle> loadAsync(io.cortexj.planner.ExecutionRequest request) {
                        return java.util.concurrent.CompletableFuture.completedFuture(handle);
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

    static int postJson(URL url, String body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(60_000);
            connection.setRequestProperty("Content-Type", "application/json");
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

    static String postJsonErrorBody(URL url, String body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(60_000);
            connection.setRequestProperty("Content-Type", "application/json");
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bytes.length);
            OutputStream output = connection.getOutputStream();
            output.write(bytes);
            output.close();
            InputStream input = connection.getResponseCode() < 400
                    ? connection.getInputStream() : connection.getErrorStream();
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[1024];
            int read;
            while (input != null && (read = input.read(chunk)) >= 0) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }

    static int get(URL url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(5_000);
            return connection.getResponseCode();
        } finally {
            connection.disconnect();
        }
    }

    static String errorBody(URL url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        try {
            connection.setConnectTimeout(5_000);
            connection.setReadTimeout(5_000);
            InputStream input = connection.getResponseCode() < 400
                    ? connection.getInputStream() : connection.getErrorStream();
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[1024];
            int read;
            while (input != null && (read = input.read(chunk)) >= 0) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            connection.disconnect();
        }
    }
}
