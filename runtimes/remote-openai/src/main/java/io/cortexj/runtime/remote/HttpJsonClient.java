package io.cortexj.runtime.remote;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

final class HttpJsonClient {

    private final java.net.Proxy proxy;

    HttpJsonClient() {
        this(null);
    }

    static HttpJsonClient fromConfiguration(java.util.Map<String, String> platformConfiguration) {
        String host = platformConfiguration == null ? null : platformConfiguration.get("proxy.host");
        String port = platformConfiguration == null ? null : platformConfiguration.get("proxy.port");
        if (host != null && !host.trim().isEmpty() && port != null && !port.trim().isEmpty()) {
            try {
                return new HttpJsonClient(new java.net.Proxy(java.net.Proxy.Type.HTTP,
                        new java.net.InetSocketAddress(host, Integer.parseInt(port.trim()))));
            } catch (NumberFormatException ignored) {
                // fall back to system proxy defaults on malformed port
            }
        }
        return new HttpJsonClient(null);
    }

    private HttpJsonClient(java.net.Proxy proxy) {
        this.proxy = proxy;
    }

    static final class Response {

        final int status;
        final String body;

        Response(int status, String body) {
            this.status = status;
            this.body = body;
        }

        boolean isOk() {
            return status >= 200 && status < 300;
        }
    }

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalArgumentException("Cannot serialize request: " + e.getMessage(), e);
        }
    }

    <T> T fromJson(String body, Class<T> type) {
        try {
            return mapper.readValue(body, type);
        } catch (IOException e) {
            throw new IllegalArgumentException("Malformed response from remote runtime: " + e.getMessage(), e);
        }
    }

    Response post(String baseUrl, String path, String jsonBody, String apiKey) throws IOException {
        return exchange("POST", baseUrl, path, jsonBody, apiKey, 30_000, 120_000);
    }

    Response get(String baseUrl, String path, String apiKey) throws IOException {
        return exchange("GET", baseUrl, path, null, apiKey, 5_000, 15_000);
    }

    private static final int MAX_REDIRECTS = 5;

    private Response exchange(String method, String baseUrl, String path, String jsonBody,
                              String apiKey, int connectTimeout, int readTimeout) throws IOException {
        // audit 2026-10-08: manual redirects - auto-follow would resend the
        // Bearer token to wherever a compromised endpoint points, including
        // internal hosts. Each hop is guard-validated, credentials are
        // dropped cross-origin, same as the download manager.
        io.cortexj.spi.support.HttpGuards.requirePublicHttpUrl(trimSlash(baseUrl) + path);
        URL url = new URL(trimSlash(baseUrl) + path);
        String hopApiKey = apiKey;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpURLConnection connection = proxy == null
                    ? (HttpURLConnection) url.openConnection()
                    : (HttpURLConnection) url.openConnection(proxy);
            try {
                connection.setRequestMethod(method);
                connection.setConnectTimeout(connectTimeout);
                connection.setReadTimeout(readTimeout);
                connection.setInstanceFollowRedirects(false);
                connection.setRequestProperty("Accept", "application/json");
                if (hopApiKey != null && !hopApiKey.isEmpty()) {
                    connection.setRequestProperty("Authorization", "Bearer " + hopApiKey);
                }
                if (jsonBody != null) {
                    connection.setRequestProperty("Content-Type", "application/json");
                    connection.setDoOutput(true);
                    byte[] payload = jsonBody.getBytes(StandardCharsets.UTF_8);
                    connection.setFixedLengthStreamingMode(payload.length);
                    OutputStream output = connection.getOutputStream();
                    try {
                        output.write(payload);
                    } finally {
                        output.close();
                    }
                }
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    if (hop == MAX_REDIRECTS) {
                        throw new IOException("Too many redirects from remote runtime at "
                                + io.cortexj.spi.support.HttpGuards.redactUrlForLogging(
                                url.toString()));
                    }
                    String target = connection.getHeaderField("Location");
                    if (target == null) {
                        throw new IOException("Redirect without Location header from "
                                + io.cortexj.spi.support.HttpGuards.redactUrlForLogging(
                                url.toString()));
                    }
                    URL next;
                    try {
                        next = new URL(url, target);
                    } catch (java.net.MalformedURLException malformed) {
                        throw new IOException("Unparseable redirect target from remote runtime", malformed);
                    }
                    String scheme = next.getProtocol() == null ? "" : next.getProtocol();
                    if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
                        throw new IOException("Redirect to non-http(s) target blocked: "
                                + io.cortexj.spi.support.HttpGuards.redactUrlForLogging(
                                next.toString()));
                    }
                    try {
                        io.cortexj.spi.support.HttpGuards.requirePublicHttpUrl(next.toString());
                    } catch (IllegalArgumentException blocked) {
                        throw new IOException("Redirect target blocked by the SSRF guard: "
                                + blocked.getMessage());
                    }
                    if (!sameOrigin(url, next)) {
                        hopApiKey = null;
                    }
                    url = next;
                    // POST with a body is never replayed across hops:
                    // HttpURLConnection never auto-followed it either, so the
                    // 3xx response is returned as-is (previous behavior).
                    if (jsonBody != null) {
                        InputStream stream = connection.getErrorStream();
                        return new Response(status, readAll(stream));
                    }
                    continue;
                }
                InputStream stream = status >= 200 && status < 300
                        ? connection.getInputStream()
                        : connection.getErrorStream();
                return new Response(status, readAll(stream));
            } catch (IOException requestFailure) {
                connection.disconnect();
                throw requestFailure;
            } catch (RuntimeException requestFailure) {
                connection.disconnect();
                throw requestFailure;
            }
        }
        throw new IOException("Too many redirects from remote runtime");
    }

    private static boolean sameOrigin(URL a, URL b) {
        if (a.getProtocol() == null || !a.getProtocol().equalsIgnoreCase(b.getProtocol())) {
            return false;
        }
        if (a.getHost() == null || !a.getHost().equalsIgnoreCase(b.getHost())) {
            return false;
        }
        return effectivePort(a) == effectivePort(b);
    }

    private static int effectivePort(URL url) {
        if (url.getPort() != -1) {
            return url.getPort();
        }
        return "https".equalsIgnoreCase(url.getProtocol()) ? 443 : 80;
    }

    // audit #99: a hostile or misconfigured endpoint must not stream an
    // unbounded body into the parent heap
    private static final int MAX_RESPONSE_BYTES = 64 * 1024 * 1024;

    private static String readAll(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        // audit L-6: explicit close - a mid-read exception must not leak the
        // response/error stream to the outer disconnect
        try (InputStream input = stream) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[8 * 1024];
            int read;
            long total = 0L;
            while ((read = input.read(buffer)) >= 0) {
                total += read;
                if (total > MAX_RESPONSE_BYTES) {
                    throw new IOException("remote response body exceeds " + MAX_RESPONSE_BYTES
                            + " bytes; refusing to buffer it");
                }
                bytes.write(buffer, 0, read);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    static String trimSlash(String value) {
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    static Map<String, Object> object(String key, Object value) {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put(key, value);
        return map;
    }
}
