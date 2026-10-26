package io.cortexj.runtime.isolated;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cortexj.worker.WorkerCodec;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Channel-level protocol round trip against a real child JVM: spawn ->
 * handshake -> probe -> open -> load -> execute -> close -> shutdown, with
 * every response echoing the request id it answers.
 */
class WorkerChannelRoundTripTest {

    @Test
    void fullProtocolRoundTripEchoesRequestIds() throws Exception {
        WorkerChannel channel = WorkerChannel.start("stub");
        try {
            // probe
            long probeId = channel.nextId();
            ObjectNode probePayload = WorkerCodec.object();
            JsonNode probe = channel.call(probeId, "probe", probePayload);
            assertEquals(probeId, probe.path("id").asLong(), "probe response must echo the request id");
            assertTrue(probe.path("ok").asBoolean());
            assertTrue(probe.path("available").asBoolean());
            assertEquals("stub", probe.path("descriptor").path("runtimeId").asText());

            // open
            Path artifact = Files.createTempFile("isolated-channel-model", ".onnx");
            Files.write(artifact, "stub".getBytes(StandardCharsets.UTF_8));
            long openId = channel.nextId();
            ObjectNode openPayload = WorkerCodec.object();
            openPayload.put("artifactPath", artifact.toAbsolutePath().toString());
            openPayload.put("device", "CPU");
            JsonNode open = channel.call(openId, "open", openPayload);
            assertEquals(openId, open.path("id").asLong());
            assertTrue(open.path("runtimeOpened").asBoolean());

            // load
            long loadId = channel.nextId();
            ObjectNode loadPayload = WorkerCodec.object();
            loadPayload.put("artifactPath", artifact.toAbsolutePath().toString());
            loadPayload.put("device", "CPU");
            JsonNode load = channel.call(loadId, "load", loadPayload);
            assertEquals(loadId, load.path("id").asLong());
            String token = load.path("modelToken").asText();
            assertTrue(!token.isEmpty());

            // execute
            long executeId = channel.nextId();
            ObjectNode executePayload = WorkerCodec.object();
            executePayload.put("modelToken", token);
            executePayload.set("request", WorkerCodec.inferenceRequest(
                    io.cortexj.inference.InferenceRequest.builder().input("ping").build()));
            JsonNode executed = channel.call(executeId, "execute", executePayload);
            assertEquals(executeId, executed.path("id").asLong());
            assertEquals("stub:ping", executed.path("response").path("outputs").path(0).asText());

            // typed error for an unknown token, id echoed
            long bogusId = channel.nextId();
            ObjectNode bogusPayload = WorkerCodec.object();
            bogusPayload.put("modelToken", "no-such-token");
            bogusPayload.set("request", WorkerCodec.inferenceRequest(
                    io.cortexj.inference.InferenceRequest.builder().input("x").build()));
            try {
                channel.call(bogusId, "execute", bogusPayload);
                throw new AssertionError("an unknown model token must fail typed");
            } catch (io.cortexj.error.CortexJException typed) {
                assertTrue(typed.code().startsWith("CORTEXJ-LOAD"));
            }

            // close session
            long closeId = channel.nextId();
            JsonNode closed = channel.call(closeId, "close", WorkerCodec.object());
            assertEquals(closeId, closed.path("id").asLong());
            assertTrue(closed.path("runtimeClosed").asBoolean());
        } finally {
            channel.shutdown();
        }
        assertTrue(channel.isDead());
        assertFalse(channel.processForTesting().isAlive());
    }

    @Test
    void readCappedLineReturnsMarkerWithoutWaitingForNewlineAndProviderRefcountsSessions() throws Exception {
        // 1. Unterminated stream exceeding cap returns CAPPED_LINE_MARKER immediately without blocking for \n
        java.io.BufferedReader infiniteNoNewline = new java.io.BufferedReader(new java.io.Reader() {
            @Override
            public int read(char[] cbuf, int off, int len) {
                for (int i = 0; i < len; i++) {
                    cbuf[off + i] = 'x';
                }
                return len;
            }

            @Override
            public void close() {
            }
        });
        String capped = WorkerChannel.readCappedLine(infiniteNoNewline, 64);
        assertTrue(capped == WorkerChannel.CAPPED_LINE_MARKER,
                "readCappedLine must return CAPPED_LINE_MARKER without blocking on missing newline");

        // 2. Provider allows two concurrent open() sessions and closes child session only when last closes
        Path artifact = Files.createTempFile("isolated-refcount-model", ".onnx");
        Files.write(artifact, "stub".getBytes(StandardCharsets.UTF_8));
        IsolatedRuntimeProvider provider = new IsolatedRuntimeProvider("stub");
        try {
            io.cortexj.spi.runtime.ModelRuntime session1 =
                    provider.open(IsolatedProviderRoundTripTest.openRequest());
            io.cortexj.spi.runtime.ModelRuntime session2 =
                    provider.open(IsolatedProviderRoundTripTest.openRequest());
            io.cortexj.spi.runtime.RuntimeModel model1 =
                    session1.load(IsolatedProviderRoundTripTest.loadRequest(artifact));
            model1.close();
            session1.close();

            // session2 must still be able to load and execute after session1 closed
            io.cortexj.spi.runtime.RuntimeModel model2 =
                    session2.load(IsolatedProviderRoundTripTest.loadRequest(artifact));
            assertEquals("stub:ok", model2.execute(
                    io.cortexj.inference.InferenceRequest.builder().input("ok").build()).singleOutput());
            model2.close();
            session2.close();
        } finally {
            provider.close();
            Files.deleteIfExists(artifact);
        }
    }
}
