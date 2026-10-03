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
}
