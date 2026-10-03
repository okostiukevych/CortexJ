package io.cortexj.server.test;

import io.cortexj.CortexJ;
import io.cortexj.error.CortexJException;
import io.cortexj.inference.GenerationRequest;
import io.cortexj.inference.GenerationResponse;
import io.cortexj.inference.TextGenerationCapability;
import io.cortexj.model.ModelHandle;
import io.cortexj.server.CortexJServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CortexJServerHardeningTest {

    @Test
    void methodNotAllowedReturns405() throws Exception {
        CortexJServer server = new CortexJServer(
                StubRuntime.platform(StubRuntime.handle(echoGenerator())), "server/test-model");
        try {
            int port = server.start(0);
            HttpURLConnection chat = (HttpURLConnection)
                    new URL("http://127.0.0.1:" + port + "/v1/chat/completions").openConnection();
            chat.setConnectTimeout(5_000);
            chat.setReadTimeout(5_000);
            assertEquals(405, chat.getResponseCode(),
                    "GET on a POST-only endpoint must return 405");
            assertEquals("POST", chat.getHeaderField("Allow"));
            chat.disconnect();
            HttpURLConnection embeddings = (HttpURLConnection)
                    new URL("http://127.0.0.1:" + port + "/v1/embeddings").openConnection();
            embeddings.setConnectTimeout(5_000);
            embeddings.setReadTimeout(5_000);
            assertEquals(405, embeddings.getResponseCode(),
                    "GET on a POST-only endpoint must return 405");
            embeddings.disconnect();
        } finally {
            server.stop();
        }
    }

    @Test
    void unknownPathReturns404() throws Exception {
        CortexJServer server = new CortexJServer(
                StubRuntime.platform(StubRuntime.handle(echoGenerator())), "server/test-model");
        try {
            int port = server.start(0);
            String body = StubRuntime.errorBody(
                    new URL("http://127.0.0.1:" + port + "/v1/does-not-exist"));
            assertTrue(body.contains("CORTEXJ-SERVER-4040"),
                    "an unknown path must answer with the typed 404 code, got: " + body);
        } finally {
            server.stop();
        }
    }

    @Test
    void malformedJsonReturns400() throws Exception {
        CortexJServer server = new CortexJServer(
                StubRuntime.platform(StubRuntime.handle(echoGenerator())), "server/test-model");
        try {
            int port = server.start(0);
            HttpURLConnection connection = post(
                    new URL("http://127.0.0.1:" + port + "/v1/chat/completions"),
                    "{oops", "application/json");
            assertEquals(400, connection.getResponseCode(),
                    "a malformed JSON body must return 400");
            connection.disconnect();
        } finally {
            server.stop();
        }
    }

    @Test
    void nonJsonContentTypeReturns415() throws Exception {
        CortexJServer server = new CortexJServer(
                StubRuntime.platform(StubRuntime.handle(echoGenerator())), "server/test-model");
        try {
            int port = server.start(0);
            HttpURLConnection connection = post(
                    new URL("http://127.0.0.1:" + port + "/v1/chat/completions"),
                    "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}", "text/plain");
            assertEquals(415, connection.getResponseCode(),
                    "a non-JSON content type must return 415");
            connection.disconnect();
        } finally {
            server.stop();
        }
    }

    @Test
    void nonLoopbackHostHeaderIsRejectedWhenUnauthenticated() throws Exception {
        CortexJServer server = new CortexJServer(
                StubRuntime.platform(StubRuntime.handle(echoGenerator())), "server/test-model");
        try {
            int port = server.start(0);
            String rejected = sendRaw(port,
                    "GET /v1/models HTTP/1.1\r\nHost: 10.0.0.1\r\nConnection: close\r\n\r\n");
            assertTrue(rejected.startsWith("HTTP/1.1 403"),
                    "a non-loopback Host header must be rejected when unauthenticated, got: "
                            + rejected.split("\r\n")[0]);
            assertTrue(rejected.contains("CORTEXJ-SERVER-4030"),
                    "the host rejection must carry the typed code, got: " + rejected);
            String accepted = sendRaw(port,
                    "GET /v1/models HTTP/1.1\r\nHost: 127.0.0.1:" + port
                            + "\r\nConnection: close\r\n\r\n");
            assertTrue(accepted.startsWith("HTTP/1.1 200"),
                    "a loopback Host header must stay accepted, got: "
                            + accepted.split("\r\n")[0]);
        } finally {
            server.stop();
        }
    }

    @Test
    void internalErrorResponsesAreGeneric() throws Exception {
        CortexJServer server = new CortexJServer(StubRuntime.platform(StubRuntime.handle(
                new StubRuntime.ThrowingGenerator(new RuntimeException(
                        "corrupt cache at /Users/x/.cortexj/cache/model.gguf; "
                                + "upstream http://10.1.2.3:9999/v1 failed")))),
                "server/test-model");
        try {
            int port = server.start(0);
            String body = StubRuntime.postJsonErrorBody(new URL(
                    "http://127.0.0.1:" + port + "/v1/chat/completions"),
                    "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
            assertTrue(body.contains("CORTEXJ-SERVER-5000"),
                    "the generic internal error code must be present, got: " + body);
            assertTrue(body.contains("internal error, see server logs"),
                    "the generic message must be present, got: " + body);
            assertTrue(!body.contains("/Users/x"),
                    "cache paths must not leak into error responses, got: " + body);
            assertTrue(!body.contains("http://10.1.2.3"),
                    "upstream URLs must not leak into error responses, got: " + body);
        } finally {
            server.stop();
        }
    }

    @Test
    void upstreamErrorResponsesAreGeneric() throws Exception {
        CortexJServer server = new CortexJServer(StubRuntime.platform(StubRuntime.handle(
                new StubRuntime.ThrowingGenerator(new CortexJException(
                        "CORTEXJ-CACHE-1000",
                        "cache path /Users/x/.cortexj/cache/model.gguf corrupted")))),
                "server/test-model");
        try {
            int port = server.start(0);
            String body = StubRuntime.postJsonErrorBody(new URL(
                    "http://127.0.0.1:" + port + "/v1/chat/completions"),
                    "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
            assertTrue(body.contains("CORTEXJ-CACHE-1000"),
                    "the upstream error code must be preserved, got: " + body);
            assertTrue(body.contains("internal error, see server logs"),
                    "the generic message must replace the upstream detail, got: " + body);
            assertTrue(!body.contains("/Users/x"),
                    "cache paths must not leak into error responses, got: " + body);
        } finally {
            server.stop();
        }
    }

    @Test
    void saturatedPoolResponds503() throws Exception {
        final int workers = Math.max(8, 2 * Runtime.getRuntime().availableProcessors());
        final CountDownLatch allInFlight = new CountDownLatch(workers);
        final CountDownLatch release = new CountDownLatch(1);
        ModelHandle handle = StubRuntime.handle(new TextGenerationCapability() {
            @Override
            public GenerationResponse generate(GenerationRequest request) {
                allInFlight.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return new GenerationResponse("done", io.cortexj.inference.FinishReason.STOP,
                        io.cortexj.inference.TokenUsage.unknown(), null);
            }

            @Override
            public io.cortexj.stream.TokenStream stream(GenerationRequest request) {
                return new io.cortexj.stream.internal.PushTokenStream();
            }
        });
        CortexJServer server = new CortexJServer(StubRuntime.platform(handle), "server/test-model");
        try {
            int port = server.start(0);
            final URL url = new URL("http://127.0.0.1:" + port + "/v1/chat/completions");
            List<Thread> blocked = new ArrayList<Thread>();
            for (int i = 0; i < workers; i++) {
                Thread requester = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            StubRuntime.postJson(url,
                                    "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
                        } catch (IOException ignored) {
                            // server stopping
                        }
                    }
                });
                requester.setDaemon(true);
                blocked.add(requester);
                requester.start();
            }
            assertTrue(allInFlight.await(10, TimeUnit.SECONDS),
                    "all " + workers + " requests must be in flight");
            HttpURLConnection probe = (HttpURLConnection)
                    new URL("http://127.0.0.1:" + port + "/v1/models").openConnection();
            probe.setConnectTimeout(5_000);
            probe.setReadTimeout(5_000);
            assertEquals(503, probe.getResponseCode(),
                    "a request beyond the bounded pool capacity must be refused with 503");
            assertTrue(probe.getHeaderField("Retry-After") != null,
                    "the 503 response must carry a Retry-After header");
            probe.disconnect();
        } finally {
            release.countDown();
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

    private static HttpURLConnection post(URL url, String body, String contentType)
            throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(5_000);
        connection.setReadTimeout(60_000);
        connection.setRequestProperty("Content-Type", contentType);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return connection;
    }

    private static String sendRaw(int port, String request) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            OutputStream output = socket.getOutputStream();
            output.write(request.getBytes(StandardCharsets.UTF_8));
            output.flush();
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            InputStream input = socket.getInputStream();
            byte[] chunk = new byte[1024];
            int read;
            while ((read = input.read(chunk)) >= 0) {
                buffer.write(chunk, 0, read);
            }
            return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
