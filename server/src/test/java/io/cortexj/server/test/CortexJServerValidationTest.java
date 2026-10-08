package io.cortexj.server.test;

import io.cortexj.inference.GenerationRequest;
import io.cortexj.inference.GenerationResponse;
import io.cortexj.inference.TextGenerationCapability;
import io.cortexj.server.CortexJServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit 2026-10-07 (#144): fail-closed input bounds and the bearer
 * brute-force throttle.
 */
class CortexJServerValidationTest {

    @Test
    void temperatureOutOfRangeReturns400() throws Exception {
        CortexJServer server = new CortexJServer(
                StubRuntime.platform(StubRuntime.handle(echoGenerator())), "server/test-model");
        try {
            int port = server.start(0);
            HttpURLConnection negative = post(
                    new URL("http://127.0.0.1:" + port + "/v1/chat/completions"),
                    "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"temperature\":-1}");
            assertEquals(400, negative.getResponseCode());
            assertTrue(bodyOf(negative).contains("CORTEXJ-SERVER-4001"));
            negative.disconnect();
            HttpURLConnection nan = post(
                    new URL("http://127.0.0.1:" + port + "/v1/chat/completions"),
                    "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"temperature\":99}");
            assertEquals(400, nan.getResponseCode());
            nan.disconnect();
        } finally {
            server.stop();
        }
    }

    @Test
    void invalidMaxTokensReturns400() throws Exception {
        CortexJServer server = new CortexJServer(
                StubRuntime.platform(StubRuntime.handle(echoGenerator())), "server/test-model");
        try {
            int port = server.start(0);
            HttpURLConnection zero = post(
                    new URL("http://127.0.0.1:" + port + "/v1/chat/completions"),
                    "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"max_tokens\":0}");
            assertEquals(400, zero.getResponseCode());
            assertTrue(bodyOf(zero).contains("CORTEXJ-SERVER-4002"));
            zero.disconnect();
        } finally {
            server.stop();
        }
    }

    @Test
    void unknownRoleReturns400InsteadOfSilentUser() throws Exception {
        CortexJServer server = new CortexJServer(
                StubRuntime.platform(StubRuntime.handle(echoGenerator())), "server/test-model");
        try {
            int port = server.start(0);
            HttpURLConnection connection = post(
                    new URL("http://127.0.0.1:" + port + "/v1/chat/completions"),
                    "{\"messages\":[{\"role\":\"superadmin\",\"content\":\"hi\"}]}");
            assertEquals(400, connection.getResponseCode(),
                    "an unknown role must fail closed, not smuggle into USER");
            assertTrue(bodyOf(connection).contains("CORTEXJ-SERVER-4005"));
            connection.disconnect();
        } finally {
            server.stop();
        }
    }

    @Test
    void oversizedEmbeddingBatchReturns400() throws Exception {
        CortexJServer server = new CortexJServer(
                StubRuntime.platform(StubRuntime.handle(echoGenerator())), "server/test-model");
        try {
            int port = server.start(0);
            StringBuilder inputs = new StringBuilder("{\"input\":[");
            for (int i = 0; i < 513; i++) {
                if (i > 0) {
                    inputs.append(',');
                }
                inputs.append("\"text").append(i).append('"');
            }
            inputs.append("]}");
            HttpURLConnection connection = post(
                    new URL("http://127.0.0.1:" + port + "/v1/embeddings"), inputs.toString());
            assertEquals(400, connection.getResponseCode());
            assertTrue(bodyOf(connection).contains("CORTEXJ-SERVER-4006"));
            connection.disconnect();
        } finally {
            server.stop();
        }
    }

    @Test
    void repeatedBadBearerIsThrottledWith429() throws Exception {
        CortexJServer server = new CortexJServer(
                StubRuntime.platform(StubRuntime.handle(echoGenerator())),
                "server/test-model", 0L, "correct-token");
        try {
            int port = server.start(0);
            int last = 0;
            for (int i = 0; i < 6; i++) {
                HttpURLConnection connection = postAuth(
                        new URL("http://127.0.0.1:" + port + "/v1/models"), "Bearer wrong-token");
                last = connection.getResponseCode();
                connection.disconnect();
            }
            assertEquals(429, last,
                    "the 6th consecutive bad bearer from one IP must be throttled");
        } finally {
            server.stop();
        }
    }

    @Test
    void stuckGenerationTimesOutWith504() throws Exception {
        // audit 2026-10-08 (#153): a stuck unary generate must time out to
        // the client instead of pinning a request thread forever
        System.setProperty("cortexj.server.generateTimeoutMillis", "300");
        final java.util.concurrent.CountDownLatch release =
                new java.util.concurrent.CountDownLatch(1);
        TextGenerationCapability stuck = new TextGenerationCapability() {
            @Override
            public GenerationResponse generate(GenerationRequest request) {
                try {
                    release.await(30, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return new GenerationResponse("late",
                        io.cortexj.inference.FinishReason.STOP,
                        new io.cortexj.inference.TokenUsage(1L, 1L), null);
            }

            @Override
            public io.cortexj.stream.TokenStream stream(GenerationRequest request) {
                return new io.cortexj.stream.internal.PushTokenStream();
            }
        };
        CortexJServer server = new CortexJServer(
                StubRuntime.platform(StubRuntime.handle(stuck)), "server/test-model");
        try {
            int port = server.start(0);
            HttpURLConnection connection = post(
                    new URL("http://127.0.0.1:" + port + "/v1/chat/completions"),
                    "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
            assertEquals(504, connection.getResponseCode(),
                    "a stuck generation must time out with 504");
            connection.disconnect();
        } finally {
            release.countDown();
            System.clearProperty("cortexj.server.generateTimeoutMillis");
            server.stop();
        }
    }

    private static TextGenerationCapability echoGenerator() {
        return new TextGenerationCapability() {
            @Override
            public GenerationResponse generate(GenerationRequest request) {
                return new GenerationResponse("echo: " + request.prompt(),
                        io.cortexj.inference.FinishReason.STOP,
                        new io.cortexj.inference.TokenUsage(1L, 1L), null);
            }

            @Override
            public io.cortexj.stream.TokenStream stream(GenerationRequest request) {
                return new io.cortexj.stream.internal.PushTokenStream();
            }
        };
    }

    private static HttpURLConnection post(URL url, String body) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(10_000);
        connection.setRequestProperty("Content-Type", "application/json");
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return connection;
    }

    private static HttpURLConnection postAuth(URL url, String authHeader) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(10_000);
        connection.setRequestProperty("Authorization", authHeader);
        connection.getResponseCode();
        return connection;
    }

    private static String bodyOf(HttpURLConnection connection) throws IOException {
        java.io.InputStream input = connection.getResponseCode() < 400
                ? connection.getInputStream() : connection.getErrorStream();
        java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
        byte[] chunk = new byte[1024];
        int read;
        while (input != null && (read = input.read(chunk)) >= 0) {
            buffer.write(chunk, 0, read);
        }
        return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
    }
}
