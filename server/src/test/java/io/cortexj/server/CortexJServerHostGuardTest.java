package io.cortexj.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * audit #90: the no-auth loopback Host guard must accept only literal
 * loopback names. Forms like "2130706433" (integer IP) or "0x7f.1" (hex)
 * are resolved to 127.0.0.1 by InetAddress.getByName WITHOUT any DNS
 * lookup - exactly what a rebinding attack exploits - so they must be
 * rejected, proving the guard performs no resolution at all.
 */
class CortexJServerHostGuardTest {

    @Test
    void literalLoopbackNamesAreAccepted() {
        assertTrue(CortexJServer.isLoopbackHost("localhost"));
        assertTrue(CortexJServer.isLoopbackHost("LOCALHOST"));
        assertTrue(CortexJServer.isLoopbackHost("localhost:8080"));
        assertTrue(CortexJServer.isLoopbackHost("127.0.0.1"));
        assertTrue(CortexJServer.isLoopbackHost("127.0.0.1:8080"));
        assertTrue(CortexJServer.isLoopbackHost("127.54.99.1"));
        assertTrue(CortexJServer.isLoopbackHost("::1"));
        assertTrue(CortexJServer.isLoopbackHost("[::1]:8080"));
        assertTrue(CortexJServer.isLoopbackHost("0:0:0:0:0:0:0:1"));
    }

    @Test
    void foreignNamesAreRejectedWithoutResolution() {
        assertFalse(CortexJServer.isLoopbackHost("rebind.attacker.example"));
        assertFalse(CortexJServer.isLoopbackHost("localhost.evil.example"));
        assertFalse(CortexJServer.isLoopbackHost("127.0.0.1.evil.example"));
    }

    @Test
    void resolutionEquivalentIpFormsAreRejected() {
        assertFalse(CortexJServer.isLoopbackHost("2130706433"),
                "integer form of 127.0.0.1 resolves via InetAddress - the guard must not resolve");
        assertFalse(CortexJServer.isLoopbackHost("2130706433:8080"));
        assertFalse(CortexJServer.isLoopbackHost("0x7f.1"),
                "hex form of 127.0.0.1 resolves via InetAddress - the guard must not resolve");
        assertFalse(CortexJServer.isLoopbackHost("0177.0.0.1"),
                "octal form of 127.0.0.1 resolves via InetAddress - the guard must not resolve");
    }

    @Test
    void malformedHostsAreRejected() {
        assertFalse(CortexJServer.isLoopbackHost(null));
        assertFalse(CortexJServer.isLoopbackHost(""));
        assertFalse(CortexJServer.isLoopbackHost("   "));
        assertFalse(CortexJServer.isLoopbackHost("[::1"));
        assertFalse(CortexJServer.isLoopbackHost(":8080"));
        assertFalse(CortexJServer.isLoopbackHost("127."));
        assertFalse(CortexJServer.isLoopbackHost("127.0.0.256"));
        assertFalse(CortexJServer.isLoopbackHost("127.0.0"));
    }
}
