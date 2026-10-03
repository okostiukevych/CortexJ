package io.cortexj.server.test;

import io.cortexj.CortexJ;
import io.cortexj.inference.FinishReason;
import io.cortexj.inference.GenerationRequest;
import io.cortexj.inference.GenerationResponse;
import io.cortexj.inference.TextGenerationCapability;
import io.cortexj.server.CortexJServer;
import io.cortexj.stream.internal.PushTokenStream;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CortexJServerStreamLifecycleTest {

    @Test
    void disconnectDuringStreamCancelsUpstream() throws Exception {
        final CountDownLatch cancelInvoked = new CountDownLatch(1);
        final PushTokenStream stream = new PushTokenStream();
        stream.onCancel(new Runnable() {
            @Override
            public void run() {
                cancelInvoked.countDown();
            }
        });
        stream.emit("first", Collections.<String, String>emptyMap());
        CortexJServer server = new CortexJServer(
                StubRuntime.platform(StubRuntime.handle(streamingStub(stream))), "server/test-model");
        try {
            int port = server.start(0);
            String body = "{\"model\":\"m\",\"stream\":true,\"messages\":"
                    + "[{\"role\":\"user\",\"content\":\"hi\"}]}";
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(10_000);
                writeRawRequest(socket, port, body);
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                boolean firstTokenSeen = false;
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data:") && line.contains("first")) {
                        firstTokenSeen = true;
                        break;
                    }
                }
                assertTrue(firstTokenSeen,
                        "the first token must reach the client before the disconnect");
                socket.close();
            }
            final char[] payload = new char[8192];
            java.util.Arrays.fill(payload, 'x');
            final String bigToken = new String(payload);
            Thread emitter = new Thread(new Runnable() {
                @Override
                public void run() {
                    for (int i = 0; i < 250 && cancelInvoked.getCount() > 0; i++) {
                        stream.emit(bigToken, Collections.<String, String>emptyMap());
                        try {
                            Thread.sleep(20);
                        } catch (InterruptedException interrupted) {
                            return;
                        }
                    }
                }
            });
            emitter.setDaemon(true);
            emitter.start();
            assertTrue(cancelInvoked.await(15, TimeUnit.SECONDS),
                    "a client disconnect must cancel the upstream generation");
        } finally {
            server.stop();
        }
    }

    @Test
    void awaitTimeoutCancelsUpstream() throws Exception {
        System.setProperty("cortexj.server.streamAwaitMillis", "400");
        final CountDownLatch cancelInvoked = new CountDownLatch(1);
        final PushTokenStream stream = new PushTokenStream();
        stream.onCancel(new Runnable() {
            @Override
            public void run() {
                cancelInvoked.countDown();
            }
        });
        CortexJServer server = new CortexJServer(
                StubRuntime.platform(StubRuntime.handle(streamingStub(stream))), "server/test-model");
        try {
            int port = server.start(0);
            String body = "{\"model\":\"m\",\"stream\":true,\"messages\":"
                    + "[{\"role\":\"user\",\"content\":\"hi\"}]}";
            String response;
            try (Socket socket = new Socket("127.0.0.1", port)) {
                socket.setSoTimeout(60_000);
                writeRawRequest(socket, port, body);
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                List<String> frames = new ArrayList<String>();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.startsWith("data:")) {
                        frames.add(line.substring(5).trim());
                    }
                }
                response = String.join("\n", frames);
            }
            assertTrue(cancelInvoked.await(5, TimeUnit.SECONDS),
                    "the await timeout must cancel the upstream generation");
            assertTrue(!response.isEmpty(),
                    "a timeout error frame must be emitted before the exchange is closed");
            assertTrue(response.contains("CORTEXJ-SERVER-5040"),
                    "the timeout error frame must report the cancellation, got: " + response);
        } finally {
            server.stop();
            System.clearProperty("cortexj.server.streamAwaitMillis");
        }
    }

    private static void writeRawRequest(Socket socket, int port, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        OutputStream output = socket.getOutputStream();
        output.write(("POST /v1/chat/completions HTTP/1.1\r\nHost: 127.0.0.1:" + port
                + "\r\nContent-Type: application/json\r\nContent-Length: " + bytes.length
                + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        output.write(bytes);
        output.flush();
    }

    private static TextGenerationCapability streamingStub(final PushTokenStream stream) {
        return new TextGenerationCapability() {
            @Override
            public GenerationResponse generate(GenerationRequest request) {
                return new GenerationResponse("unused", FinishReason.STOP,
                        io.cortexj.inference.TokenUsage.unknown(), null);
            }

            @Override
            public PushTokenStream stream(GenerationRequest request) {
                return stream;
            }
        };
    }
}
