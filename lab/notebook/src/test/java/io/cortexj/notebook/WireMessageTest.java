package io.cortexj.notebook;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire-protocol contract: envelope construction, HMAC sign/verify
 * round-trip, reply parenting, constant-time verification.
 */
class WireMessageTest {

    private static final byte[] KEY = "test-key".getBytes(StandardCharsets.US_ASCII);

    @Test
    void signatureRoundTrips() {
        WireMessage message = WireMessage.reply(null, "kernel_info_reply", "{\"status\":\"ok\"}");
        String signature = message.signature(KEY);
        assertEquals(64, signature.length());
        assertTrue(WireMessage.verify(message, KEY, signature));
    }

    @Test
    void verificationRejectsTamperingAndWrongKey() {
        WireMessage message = WireMessage.reply(null, "execute_reply", "{\"status\":\"ok\"}");
        String signature = message.signature(KEY);
        // tampered content
        WireMessage tampered = WireMessage.reply(null, "execute_reply", "{\"status\":\"error\"}");
        assertTrue(!WireMessage.verify(tampered, KEY, signature));
        // wrong key
        assertTrue(!WireMessage.verify(message, "other".getBytes(StandardCharsets.US_ASCII), signature));
        // null signature
        assertTrue(!WireMessage.verify(message, KEY, null));
    }

    @Test
    void replyKeepsSessionAndParentsRequest() {
        WireMessage request = WireMessage.reply(null, "execute_request", "{\"code\":\"1+1\"}");
        WireMessage reply = WireMessage.reply(request, "execute_reply", "{}");
        assertEquals(request.sessionId(), reply.sessionId());
        assertEquals(request.header(), reply.parentHeader());
        assertEquals("execute_reply", reply.messageType());
        // distinct message ids
        assertNotEquals(request.messageId(), reply.messageId());
    }

    @Test
    void messageTypeAndIdsParse() {
        String header = "{\"msg_id\":\"" + UUID.randomUUID() + "\","
                + "\"session\":\"sess-1\",\"msg_type\":\"execute_request\"}";
        WireMessage message = new WireMessage(header, "{}", "{}", "{}", java.util.List.of());
        assertEquals("execute_request", message.messageType());
        assertEquals("sess-1", message.sessionId());
        assertFalse(message.messageId().isEmpty());
    }
}
