package io.cortexj.spi.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HttpGuardsTest {

    @Test
    void loopbackIsBlockedByDefault() {
        IllegalArgumentException blocked = assertThrows(IllegalArgumentException.class,
                () -> HttpGuards.requirePublicHttpUrl("https://127.0.0.1/model.gguf"));
        assertTrue(blocked.getMessage().contains("private"),
                "loopback must be refused, got: " + blocked.getMessage());
    }

    @Test
    void metadataEndpointIsBlockedByDefault() {
        assertThrows(IllegalArgumentException.class,
                () -> HttpGuards.requirePublicHttpUrl("http://169.254.169.254/latest/meta-data/"));
    }

    @Test
    void decimalIpObfuscationOfLoopbackIsBlocked() {
        // 2130706433 == 127.0.0.1 - the check runs on resolved addresses
        assertThrows(IllegalArgumentException.class,
                () -> HttpGuards.requirePublicHttpUrl("http://2130706433/model.gguf"));
    }

    @Test
    void nonHttpSchemeIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> HttpGuards.requirePublicHttpUrl("file:///etc/passwd"));
    }

    @Test
    void optOutAllowsPrivateHosts() {
        System.setProperty(HttpGuards.ALLOW_PRIVATE_HOSTS_PROPERTY, "true");
        try {
            HttpGuards.requirePublicHttpUrl("https://127.0.0.1/model.gguf");
        } finally {
            System.clearProperty(HttpGuards.ALLOW_PRIVATE_HOSTS_PROPERTY);
        }
    }

    @Test
    void redactStripsUserinfoAndQuery() {
        assertEquals("https://<userinfo>@host/model.gguf?<redacted>",
                HttpGuards.redactUrlForLogging("https://user:pass@host/model.gguf?token=abc&exp=1"));
        assertEquals("https://host/model.gguf?<redacted>",
                HttpGuards.redactUrlForLogging("https://host/model.gguf?X-Amz-Signature=abc"));
        assertEquals("https://host/model.gguf",
                HttpGuards.redactUrlForLogging("https://host/model.gguf"));
    }

    @Test
    void redactUrlsInTextKeepsStructure() {
        String redacted = HttpGuards.redactUrlsInText(
                "HTTP 404 from https://user:pw@host/a.gguf?tok=1 and https://h/b?x=2 ok");
        assertTrue(!redacted.contains("user:pw") && !redacted.contains("tok=1")
                        && !redacted.contains("x=2"),
                "credentials must not survive, got: " + redacted);
        assertTrue(redacted.contains("https://"), "URL structure must survive: " + redacted);
    }
}
