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
            if (address.isLoopbackAddress()
                    || address.isSiteLocalAddress()
                    || address.isLinkLocalAddress()
                    || address.isAnyLocalAddress()
                    || address.isMulticastAddress()) {
                throw new IllegalArgumentException("model URL resolves to a private address ("
                        + address.getHostAddress() + ") which is blocked by default: "
                        + redactUrlForLogging(url) + ". For intranet mirrors set "
                        + ALLOW_PRIVATE_HOSTS_PROPERTY + "=true or "
                        + ALLOW_PRIVATE_HOSTS_ENV + "=1.");
            }
        }
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
