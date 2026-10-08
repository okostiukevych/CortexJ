package io.cortexj.server.test;

import io.cortexj.inference.GenerationRequest;
import io.cortexj.inference.GenerationResponse;
import io.cortexj.inference.TextGenerationCapability;
import io.cortexj.server.CortexJServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit 2026-10-08: load proof for the streaming/unary capacity split -
 * streams must not exhaust unary capacity and saturation must 503.
 */
@Tag("perf")
class CortexJServerLoadTest {

    @Test
    void streamsDoNotStarveUnary() throws Exception {
        final int streamWorkers = Math.max(4, Runtime.getRuntime().availableProcessors());
        final CountDownLatch streamsRunning = new CountDownLatch(streamWorkers);
        TextGenerationCapability slowStreamer = new TextGenerationCapability() {
            @Override
            public GenerationResponse generate(GenerationRequest request) {
                return new GenerationResponse("echo",
                        io.cortexj.inference.FinishReason.STOP,
                        new io.cortexj.inference.TokenUsage(1L, 1L), null);
            }

            @Override
            public io.cortexj.stream.TokenStream stream(GenerationRequest request) {
                io.cortexj.stream.internal.PushTokenStream stream =
                        new io.cortexj.stream.internal.PushTokenStream();
                // one token then stall: the exchange stays open
                stream.emit("t", null);
                streamsRunning.countDown();
                return stream;
            }
        };
        CortexJServer server = new CortexJServer(
                StubRuntime.platform(StubRuntime.handle(slowStreamer)), "server/test-model");
        try {
            int port = server.start(0);
            final URL chat = new URL("http://127.0.0.1:" + port + "/v1/chat/completions");
            List<Thread> holders = new ArrayList<Thread>();
            for (int i = 0; i < streamWorkers; i++) {
                Thread holder = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        HttpURLConnection connection = null;
                        try {
                            connection = (HttpURLConnection) chat.openConnection();
                            connection.setRequestMethod("POST");
                            connection.setDoOutput(true);
                            connection.setConnectTimeout(5_000);
                            connection.setReadTimeout(20_000);
                            connection.setRequestProperty("Content-Type", "application/json");
                            byte[] body = ("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],"
                                    + "\"stream\":true}").getBytes("UTF-8");
                            // fixed length like StubRuntime.postJson: the
                            // request must be fully deliverable without
                            // waiting for a response read
                            connection.setFixedLengthStreamingMode(body.length);
                            java.io.OutputStream output = connection.getOutputStream();
                            output.write(body);
                            // end-of-body must reach the server or handleChat
                            // never runs; then hold the SSE exchange open
                            output.flush();
                            output.close();
                            Thread.sleep(3_000L);
                        } catch (Exception ignored) {
                        } finally {
                            if (connection != null) {
                                connection.disconnect();
                            }
                        }
                    }
                });
                holder.setDaemon(true);
                holders.add(holder);
                holder.start();
            }
            assertTrue(streamsRunning.await(15, TimeUnit.SECONDS),
                    "all stream workers must be occupied");
            // unary must still answer while every stream slot is held
            HttpURLConnection models = (HttpURLConnection)
                    new URL("http://127.0.0.1:" + port + "/v1/models").openConnection();
            models.setConnectTimeout(5_000);
            models.setReadTimeout(10_000);
            assertEquals(200, models.getResponseCode(),
                    "unary capacity must survive full stream occupancy");
            models.disconnect();
            for (Thread holder : holders) {
                holder.join(10_000L);
            }
        } finally {
            server.stop();
        }
    }

    @Test
    void unarySaturationRefusesWith503() throws Exception {
        final int workers = Math.max(8, 2 * Runtime.getRuntime().availableProcessors());
        final CountDownLatch release = new CountDownLatch(1);
        TextGenerationCapability stuck = new TextGenerationCapability() {
            @Override
            public GenerationResponse generate(GenerationRequest request) {
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                return new GenerationResponse("done",
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
            final URL chat = new URL("http://127.0.0.1:" + port + "/v1/chat/completions");
            List<Thread> blocked = new ArrayList<Thread>();
            for (int i = 0; i < workers; i++) {
                Thread requester = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            StubRuntime.postJson(chat,
                                    "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
                        } catch (Exception ignored) {
                        }
                    }
                });
                requester.setDaemon(true);
                blocked.add(requester);
                requester.start();
            }
            // generations wait on their own bounded pool; unary admission
            // fills as the requesters arrive, so give them a moment, then
            // poll: once every admission permit is held the probe 503s
            Thread.sleep(2_000L);
            int code = 0;
            long deadline = System.currentTimeMillis() + 15_000L;
            do {
                HttpURLConnection probe = (HttpURLConnection)
                        new URL("http://127.0.0.1:" + port + "/v1/models").openConnection();
                probe.setConnectTimeout(5_000);
                probe.setReadTimeout(5_000);
                code = probe.getResponseCode();
                probe.disconnect();
                if (code != 503) {
                    Thread.sleep(100L);
                }
            } while (code != 503 && System.currentTimeMillis() < deadline);
            assertEquals(503, code, "saturated unary capacity must refuse with 503");
        } finally {
            release.countDown();
            server.stop();
        }
    }
}
