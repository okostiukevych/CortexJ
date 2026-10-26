package io.cortexj.notebook;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Jupyter wire-protocol message (ADR-018): the six-part envelope
 * (delimiter, HMAC signature, header, parent header, metadata, content)
 * plus buffers. Signature is HMAC-SHA256 over
 * header+parent+metadata+content with the kernel key.
 */
public final class WireMessage {

    private static final byte[] DELIMITER = "<IDS|MSG>".getBytes(StandardCharsets.US_ASCII);

    /** audit 2026-10-10: DELIMITER is private; the wire layer reads it via this accessor. */
    static byte[] delimiter() {
        return DELIMITER;
    }

    private final String header;
    private final String parentHeader;
    private final String metadata;
    private final String content;
    private final List<byte[]> buffers;

    public WireMessage(String header, String parentHeader, String metadata,
                        String content, List<byte[]> buffers) {
        this.header = header;
        this.parentHeader = parentHeader == null ? "{}" : parentHeader;
        this.metadata = metadata == null ? "{}" : metadata;
        this.content = content == null ? "{}" : content;
        this.buffers = new ArrayList<>(buffers == null ? List.of() : buffers);
    }

    public String header() {
        return header;
    }

    public String parentHeader() {
        return parentHeader;
    }

    public String metadata() {
        return metadata;
    }

    public String content() {
        return content;
    }

    public List<byte[]> buffers() {
        // audit 2026-10-10 (SpotBugs EI_EXPOSE_REP): owned copy per call
        return new ArrayList<>(buffers);
    }

    public String messageType() {
        return stringValue(header, "msg_type", "unknown");
    }

    public String messageId() {
        return stringValue(header, "msg_id", "");
    }

    public String sessionId() {
        return stringValue(header, "session", "");
    }

    public String parentMessageId() {
        return stringValue(parentHeader, "msg_id", "");
    }

    /**
     * Builds a reply message: same session, the request as parent.
     */
    public static WireMessage reply(WireMessage request, String messageType, String content) {
        String header = "{\"msg_id\":\"" + UUID.randomUUID() + "\","
                + "\"username\":\"cortexj\","
                + "\"session\":" + quote(sessionValue(request))
                + ",\"date\":\"" + java.time.Instant.now() + "\","
                + "\"msg_type\":\"" + messageType + "\"}";
        return new WireMessage(header, request == null ? "{}" : request.header(),
                "{}", content, List.of());
    }

    private static String sessionValue(WireMessage request) {
        if (request == null) {
            return UUID.randomUUID().toString();
        }
        String session = request.sessionId();
        return session == null || session.isEmpty() ? UUID.randomUUID().toString() : session;
    }

    public static String quoteValue(String value) {
        return quote(value);
    }

    private static String quote(String value) {
        if (value == null) {
            return "\"\"";
        }
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }

    /**
     * Computes the HMAC-SHA256 signature over the four JSON parts.
     */
    public String signature(byte[] key) {
        if (key == null || key.length == 0) {
            return "";
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(header.getBytes(StandardCharsets.UTF_8));
            mac.update(parentHeader.getBytes(StandardCharsets.UTF_8));
            mac.update(metadata.getBytes(StandardCharsets.UTF_8));
            mac.update(content.getBytes(StandardCharsets.UTF_8));
            byte[] digest = mac.doFinal();
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 is required", e);
        }
    }

    /**
     * Verifies a signature against the four JSON parts (constant-time).
     */
    public static boolean verify(WireMessage message, byte[] key, String expectedSignature) {
        if (key == null || key.length == 0) {
            return true;
        }
        if (expectedSignature == null) {
            return false;
        }
        byte[] left = message.signature(key).getBytes(StandardCharsets.US_ASCII);
        byte[] right = expectedSignature.getBytes(StandardCharsets.US_ASCII);
        return MessageDigest.isEqual(left, right);
    }

    static String stringValue(String json, String field, String fallback) {
        if (json == null || field == null) {
            return fallback;
        }
        int at = json.indexOf("\"" + field + "\"");
        if (at < 0) {
            return fallback;
        }
        int colon = json.indexOf(':', at + field.length() + 2);
        if (colon < 0) {
            return fallback;
        }
        int open = json.indexOf('"', colon + 1);
        if (open < 0) {
            return fallback;
        }
        StringBuilder out = new StringBuilder();
        int i = open + 1;
        while (i < json.length()) {
            char ch = json.charAt(i);
            if (ch == '\\' && i + 1 < json.length()) {
                char next = json.charAt(i + 1);
                switch (next) {
                    case '"':
                        out.append('"');
                        break;
                    case '\\':
                        out.append('\\');
                        break;
                    case 'n':
                        out.append('\n');
                        break;
                    case 'r':
                        out.append('\r');
                        break;
                    case 't':
                        out.append('\t');
                        break;
                    default:
                        out.append(next);
                        break;
                }
                i += 2;
                continue;
            }
            if (ch == '"') {
                return out.toString();
            }
            out.append(ch);
            i++;
        }
        return fallback;
    }
}
