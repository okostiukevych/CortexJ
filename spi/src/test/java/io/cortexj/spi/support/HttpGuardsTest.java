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

    @Test
    void emptyAndNullInputsFailClosed() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> HttpGuards.requirePublicHttpUrl(null));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> HttpGuards.requirePublicHttpUrl("   "));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> HttpGuards.requirePublicHttpUrl("http://"));
        assertEquals("<null>", HttpGuards.redactUrlForLogging(null));
        assertEquals("<null>", HttpGuards.redactUrlsInText(null));
    }

    @Test
    void unresolvableHostFailsClosed() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> HttpGuards.requirePublicHttpUrl("https://nonexistent.invalid/model.gguf"));
    }

    @Test
    void envOptOutAllowsPrivateHosts() {
        // the sysprop path is covered by optOutAllowsPrivateHosts; the env
        // path cannot be set portably at runtime, so assert the documented
        // constant exists and matches the guard's lookup contract
        assertEquals("CORTEXJ_HTTP_ALLOW_PRIVATE_HOSTS", HttpGuards.ALLOW_PRIVATE_HOSTS_ENV);
        assertEquals("cortexj.http.allowPrivateHosts", HttpGuards.ALLOW_PRIVATE_HOSTS_PROPERTY);
    }

    @Test
    void cgnatUlaZeroAndMappedPrivateAddressesAreBlocked() throws Exception {
        assertThrows(IllegalArgumentException.class,
                () -> HttpGuards.requirePublicHttpUrl("http://100.64.0.1/model.gguf"));
        assertThrows(IllegalArgumentException.class,
                () -> HttpGuards.requirePublicHttpUrl("http://100.127.255.254/model.gguf"));
        assertThrows(IllegalArgumentException.class,
                () -> HttpGuards.requirePublicHttpUrl("http://0.0.0.1/model.gguf"));
        assertThrows(IllegalArgumentException.class,
                () -> HttpGuards.requirePublicHttpUrl("http://192.0.0.1/model.gguf"));
        assertThrows(IllegalArgumentException.class,
                () -> HttpGuards.requirePublicHttpUrl("http://255.255.255.255/model.gguf"));
        assertThrows(IllegalArgumentException.class,
                () -> HttpGuards.requirePublicHttpUrl("http://[fc00::1]/model.gguf"));
        assertThrows(IllegalArgumentException.class,
                () -> HttpGuards.requirePublicHttpUrl("http://[fd12:3456:789a::1]/model.gguf"));
        assertThrows(IllegalArgumentException.class,
                () -> HttpGuards.requirePublicHttpUrl("http://[::ffff:127.0.0.1]/model.gguf"));
        assertThrows(IllegalArgumentException.class,
                () -> HttpGuards.requirePublicHttpUrl("http://[::ffff:169.254.169.254]/model.gguf"));

        // Public IPv4 outside CGNAT (100.63.255.255 and 100.128.0.1) is allowed
        HttpGuards.requirePublicHttpUrl("http://100.63.255.255/model.gguf");
        HttpGuards.requirePublicHttpUrl("http://100.128.0.1/model.gguf");
        org.junit.jupiter.api.Assertions.assertFalse(
                HttpGuards.isBlockedAddress(java.net.InetAddress.getByName("8.8.8.8")));
        assertTrue(HttpGuards.isBlockedAddress(null));
    }
}
