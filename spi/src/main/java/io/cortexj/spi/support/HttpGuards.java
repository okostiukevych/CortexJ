package io.cortexj.spi.support;

import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;

/**
 * Shared HTTP trust-boundary guards for repository providers and the download
 * path (audit 2026-10-07, issues #111/#142).
 *
 * <p>Model references are trusted configuration input, but server-side
 * deployments routinely resolve them on hosts reachable from a browser or a
 * chatbot - an unchecked {@code http(s)} reference is a straight SSRF
 * primitive (cloud metadata at {@code 169.254.169.254}, intranet hosts,
 * loopback services). {@link #requirePublicHttpUrl} blocks private targets by
 * default; {@link #redactUrlForLogging} keeps presigned URLs and userinfo out
 * of error details, events and logs.</p>
 */
public final class HttpGuards {

    /** System property opting out of the private-host block (intranet mirrors, tests). */
    public static final String ALLOW_PRIVATE_HOSTS_PROPERTY = "cortexj.http.allowPrivateHosts";

    /** Environment variable opting out of the private-host block. */
    public static final String ALLOW_PRIVATE_HOSTS_ENV = "CORTEXJ_HTTP_ALLOW_PRIVATE_HOSTS";

    private HttpGuards() {
    }

    /**
     * Validates that {@code url} is an {@code http(s)} URL whose host does not
     * resolve to a private target (loopback, site-local, link-local including
     * the {@code 169.254.169.254} metadata endpoint, any-local, multicast).
     *
     * <p>Numeric-host obfuscation ({@code http://2130706433/}) is covered
     * because the check runs on the resolved {@link InetAddress}es, not on the
     * raw host string.</p>
     *
     * @throws IllegalArgumentException when the URL is not http(s), has no
     *                                  host, unresolvable, or resolves to a
     *                                  private target (unless the opt-out flag
     *                                  is set)
     */
    public static void requirePublicHttpUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            throw new IllegalArgumentException("model URL must not be empty");
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (Exception invalid) {
            throw new IllegalArgumentException("model URL is not a valid URI: "
                    + redactUrlForLogging(url));
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new IllegalArgumentException("model URL must use http(s): "
                    + redactUrlForLogging(url));
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("model URL has no host: "
                    + redactUrlForLogging(url));
        }
        if (privateHostsAllowed()) {
            return;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (Exception unresolvable) {
            throw new IllegalArgumentException("model URL host does not resolve: "
                    + redactUrlForLogging(url));
        }
        for (InetAddress address : addresses) {
            if (isBlockedAddress(address)) {
                throw new IllegalArgumentException("model URL resolves to a private address ("
                        + address.getHostAddress() + ") which is blocked by default: "
                        + redactUrlForLogging(url) + ". For intranet mirrors set "
                        + ALLOW_PRIVATE_HOSTS_PROPERTY + "=true or "
                        + ALLOW_PRIVATE_HOSTS_ENV + "=1.");
            }
        }
    }

    static boolean isBlockedAddress(InetAddress address) {
        if (address == null) {
            return true;
        }
        if (address.isLoopbackAddress()
                || address.isSiteLocalAddress()
                || address.isLinkLocalAddress()
                || address.isAnyLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] raw = address.getAddress();
        if (raw == null) {
            return true;
        }
        if (raw.length == 4) {
            return isBlockedIpv4Bytes(raw[0] & 0xFF, raw[1] & 0xFF, raw[2] & 0xFF, raw[3] & 0xFF);
        }
        if (raw.length == 16) {
            // IPv6 Unique Local Addresses (fc00::/7, RFC 4193)
            if ((raw[0] & 0xFE) == 0xFC) {
                return true;
            }
            // IPv4-mapped (::ffff:w.x.y.z) and IPv4-compatible (::w.x.y.z) IPv6 addresses
            boolean prefixZeros = true;
            for (int i = 0; i < 10; i++) {
                if (raw[i] != 0) {
                    prefixZeros = false;
                    break;
                }
            }
            if (prefixZeros) {
                int b10 = raw[10] & 0xFF;
                int b11 = raw[11] & 0xFF;
                if ((b10 == 0xFF && b11 == 0xFF) || (b10 == 0x00 && b11 == 0x00)) {
                    if (isBlockedIpv4Bytes(raw[12] & 0xFF, raw[13] & 0xFF, raw[14] & 0xFF, raw[15] & 0xFF)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isBlockedIpv4Bytes(int b0, int b1, int b2, int b3) {
        // 0.0.0.0/8 ("this" network, RFC 1122)
        if (b0 == 0) {
            return true;
        }
        // 10.0.0.0/8 (private)
        if (b0 == 10) {
            return true;
        }
        // 100.64.0.0/10 (Carrier-Grade NAT / shared address space, RFC 6598)
        if (b0 == 100 && (b1 & 0xC0) == 64) {
            return true;
        }
        // 127.0.0.0/8 (loopback)
        if (b0 == 127) {
            return true;
        }
        // 169.254.0.0/16 (link-local / cloud metadata)
        if (b0 == 169 && b1 == 254) {
            return true;
        }
        // 172.16.0.0/12 (private)
        if (b0 == 172 && (b1 & 0xF0) == 16) {
            return true;
        }
        // 192.0.0.0/24 (IETF protocol assignments, RFC 6890)
        if (b0 == 192 && b1 == 0 && b2 == 0) {
            return true;
        }
        // 192.168.0.0/16 (private)
        if (b0 == 192 && b1 == 168) {
            return true;
        }
        // 255.255.255.255/32 (limited broadcast)
        return b0 == 255 && b1 == 255 && b2 == 255 && b3 == 255;
    }

    /**
     * Strips credential-bearing parts (userinfo and query, which carries HF
     * presigned tokens) from {@code url} for use in error details, events and
     * logs. Never returns null.
     */
    public static String redactUrlForLogging(String url) {
        if (url == null) {
            return "<null>";
        }
        String text = url.trim();
        int schemeEnd = text.indexOf("://");
        if (schemeEnd < 0) {
            return text;
        }
        int query = text.indexOf('?', schemeEnd + 3);
        String withoutQuery = query >= 0 ? text.substring(0, query) + "?<redacted>" : text;
        int authorityEnd = withoutQuery.indexOf('/', schemeEnd + 3);
        if (authorityEnd < 0) {
            authorityEnd = withoutQuery.length();
        }
        int at = withoutQuery.lastIndexOf('@', authorityEnd);
        if (at > schemeEnd) {
            return withoutQuery.substring(0, schemeEnd + 3) + "<userinfo>@"
                    + withoutQuery.substring(at + 1);
        }
        return withoutQuery;
    }

    private static boolean privateHostsAllowed() {
        String property = System.getProperty(ALLOW_PRIVATE_HOSTS_PROPERTY);
        if (property != null && Boolean.parseBoolean(property.trim())) {
            return true;
        }
        String env = System.getenv(ALLOW_PRIVATE_HOSTS_ENV);
        return env != null && (env.trim().equals("1") || Boolean.parseBoolean(env.trim()));
    }

    /**
     * Redacts credential-bearing URL fragments inside free-form text (exception
     * messages embedded into platform events): strips userinfo and query
     * strings from every {@code http(s)://} URL found. Never returns null.
     */
    public static String redactUrlsInText(String text) {
        if (text == null) {
            return "<null>";
        }
        // userinfo: scheme://junk@host -> scheme://<userinfo>@host
        java.util.regex.Matcher userinfo = java.util.regex.Pattern.compile(
                "(https?://)([^\\s/?#@]+)@").matcher(text);
        StringBuffer step1 = new StringBuffer();
        while (userinfo.find()) {
            userinfo.appendReplacement(step1, "$1<userinfo>@");
        }
        userinfo.appendTail(step1);
        // query: scheme://host/path?junk -> scheme://host/path?<redacted>
        java.util.regex.Matcher query = java.util.regex.Pattern.compile(
                "(https?://[^\\s?#]*)\\?[^\\s]*").matcher(step1.toString());
        StringBuffer step2 = new StringBuffer();
        while (query.find()) {
            query.appendReplacement(step2, "$1?<redacted>");
        }
        query.appendTail(step2);
        return step2.toString();
    }
}
