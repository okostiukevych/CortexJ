package io.cortexj.repository.http;

import io.cortexj.ErrorCode;
import io.cortexj.error.ModelResolutionException;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelDescriptor;
import io.cortexj.model.ModelReference;
import io.cortexj.model.TaskCapability;
import io.cortexj.model.ModelArtifact;
import io.cortexj.spi.repository.ModelRepositoryProvider;
import io.cortexj.spi.repository.RepositoryId;
import io.cortexj.spi.repository.ResolvedArtifact;
import io.cortexj.spi.repository.ResolvedModel;
import io.cortexj.spi.repository.ResolutionContext;
import io.cortexj.spi.support.SimpleResolvedArtifact;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class HttpRepositoryProvider implements ModelRepositoryProvider {

    private static final int CONNECT_TIMEOUT = 15_000;
    private static final int READ_TIMEOUT = 30_000;
    private static final long DEFAULT_METADATA_TTL_MILLIS = 60_000L;
    private static final String METADATA_TTL_PROPERTY = "cortexj.http.metadata.ttlMillis";
    private static final int HEAD_CACHE_MAX_ENTRIES = 256;

    private final RepositoryId id = RepositoryId.of("http");
    // short-TTL HEAD cache (audit H-14): repeated resolve() of the same URL
    // must not pay a blocking HEAD round-trip every time. Bounded with
    // eviction on access (audit #83): TTL governs freshness, not residency -
    // stale or surplus entries must not linger for the lifetime of the JVM
    private final java.util.Map<String, CachedHead> headCache = java.util.Collections.synchronizedMap(
            new LinkedHashMap<String, CachedHead>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedHead> eldest) {
                    return size() > HEAD_CACHE_MAX_ENTRIES;
                }
            });

    private static final class CachedHead {
        final HeadInfo info;
        final long fetchedAtMillis;

        CachedHead(HeadInfo info, long fetchedAtMillis) {
            this.info = info;
            this.fetchedAtMillis = fetchedAtMillis;
        }
    }

    private static long metadataTtlMillis() {
        String property = System.getProperty(METADATA_TTL_PROPERTY);
        if (property != null && !property.trim().isEmpty()) {
            try {
                return Long.parseLong(property.trim());
            } catch (NumberFormatException ignored) {
                // fall back to the default
            }
        }
        return DEFAULT_METADATA_TTL_MILLIS;
    }

    /** Explicit invalidation: drops all cached HEAD metadata (next resolve refetches). */
    public void invalidateCachedMetadata() {
        headCache.clear();
    }

    @Override
    public RepositoryId id() {
        return id;
    }

    @Override
    public boolean supports(ModelReference reference) {
        String value = reference.value();
        return value != null
                && (value.startsWith("http://") || value.startsWith("https://"));
    }

    @Override
    public ResolvedModel resolve(ModelReference reference, ResolutionContext context) {
        String url = reference.value();
        if (context.isOffline()) {
            throw offline(url);
        }
        String fileName = fileNameOf(url);
        HeadInfo head = resolveHead(url);
        ArtifactFormat format = ArtifactFormat.fromFileName(fileName);
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        // audit 2026-10-08: descriptor metadata surfaces in explanations -
        // keep the origin without the bearer query
        metadata.put("sourceUrl", io.cortexj.spi.support.HttpGuards.redactUrlForLogging(url));
        String quantization = io.cortexj.model.descriptor.DescriptorEnricher.quantizationFromFileName(fileName);
        if (quantization != null) {
            metadata.put("quantization", quantization);
        }
        metadata.put("integrity", "unverified");
        ModelArtifact artifact = new ModelArtifact.Abstract(
                fileName, format, head.contentLength, null, metadata) {
        };
        ModelDescriptor descriptor = ModelDescriptor.builder(reference.value())
                .revision(head.revision)
                .artifact(artifact)
                .task(TaskCapability.TEXT_GENERATION)
                .trustNote("served over plain HTTP, publisher unverified")
                .build();
        List<ResolvedArtifact> artifacts = Collections.<ResolvedArtifact>singletonList(
                new SimpleResolvedArtifact(artifact, java.net.URI.create(url),
                        Collections.<String, String>emptyMap()));
        return new ResolvedModel(descriptor, head.revision, id, artifacts);
    }

    private HeadInfo resolveHead(String url) {
        long ttl = metadataTtlMillis();
        if (ttl > 0) {
            CachedHead cached = headCache.get(url);
            if (cached != null) {
                if (System.currentTimeMillis() - cached.fetchedAtMillis < ttl) {
                    return cached.info;
                }
                // audit #83: evict the expired entry instead of leaving it resident
                headCache.remove(url);
            }
        }
        HeadInfo fresh = head(url);
        if (ttl > 0) {
            headCache.put(url, new CachedHead(fresh, System.currentTimeMillis()));
        }
        return fresh;
    }

    private static ModelResolutionException offline(String url) {
        // audit 2026-10-08: reference may carry userinfo/presigned query
        ModelResolutionException failure = new ModelResolutionException(ErrorCode.MODEL_OFFLINE_UNRESOLVED,
                "HTTP model resolution is disabled in offline mode: "
                        + io.cortexj.spi.support.HttpGuards.redactUrlForLogging(url),
                "Pre-download the model with 'cortexj pull' or use a local repository bundle.");
        failure.detail("reference", io.cortexj.spi.support.HttpGuards.redactUrlForLogging(url));
        return failure;
    }

    static java.net.Proxy egressProxy() {
        String host = System.getProperty("cortexj.proxy.host");
        if (host == null || host.trim().isEmpty()) {
            host = System.getenv("CORTEXJ_PROXY_HOST");
        }
        String port = System.getProperty("cortexj.proxy.port");
        if (port == null || port.trim().isEmpty()) {
            port = System.getenv("CORTEXJ_PROXY_PORT");
        }
        if (host == null || host.trim().isEmpty() || port == null || port.trim().isEmpty()) {
            return null;
        }
        try {
            return new java.net.Proxy(java.net.Proxy.Type.HTTP,
                    new java.net.InetSocketAddress(host.trim(), Integer.parseInt(port.trim())));
        } catch (NumberFormatException invalid) {
            return null;
        }
    }

    private static HttpURLConnection open(URL url) throws IOException {
        java.net.Proxy proxy = egressProxy();
        return proxy == null
                ? (HttpURLConnection) url.openConnection()
                : (HttpURLConnection) url.openConnection(proxy);
    }

    private static HeadInfo head(String url) {
        try {
            // audit 2026-10-07 (#111): validate the SSRF posture before the
            // connection - openConnection() would otherwise follow redirects
            // to link-local/metadata endpoints automatically
            try {
                io.cortexj.spi.support.HttpGuards.requirePublicHttpUrl(url);
            } catch (IllegalArgumentException blocked) {
                ModelResolutionException failure = new ModelResolutionException(ErrorCode.MODEL_RESOLUTION_FAILED,
                        "HTTP model URL is blocked by the SSRF guard: " + blocked.getMessage(),
                        "Point the reference at a public artifact URL, or set "
                                + io.cortexj.spi.support.HttpGuards.ALLOW_PRIVATE_HOSTS_PROPERTY
                                + "=true for intranet mirrors.");
                failure.detail("reference", io.cortexj.spi.support.HttpGuards.redactUrlForLogging(url));
                throw failure;
            }
            HttpURLConnection connection = open(new URL(url));
            try {
                connection.setRequestMethod("HEAD");
                // audit 2026-10-07 (#111): do not follow redirects blindly -
                // resolve() re-validates explicit redirect targets instead
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(CONNECT_TIMEOUT);
                connection.setReadTimeout(READ_TIMEOUT);
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String target = connection.getHeaderField("Location");
                    ModelResolutionException redirect = new ModelResolutionException(ErrorCode.MODEL_RESOLUTION_FAILED,
                            "HTTP endpoint redirected for "
                                    + io.cortexj.spi.support.HttpGuards.redactUrlForLogging(url)
                                    + " - redirects are not followed automatically by the resolver",
                            "Use the final artifact URL directly, or download the model through the "
                                    + "download manager which validates redirect targets.");
                    redirect.detail("status", String.valueOf(status));
                    redirect.detail("reference", io.cortexj.spi.support.HttpGuards.redactUrlForLogging(url));
                    if (target != null) {
                        redirect.detail("location", io.cortexj.spi.support.HttpGuards.redactUrlForLogging(target));
                    }
                    throw redirect;
                }
                if (status != 200) {
                    ModelResolutionException notFound = new ModelResolutionException(ErrorCode.MODEL_NOT_FOUND,
                            "HTTP endpoint answered with status " + status + " for "
                                    + io.cortexj.spi.support.HttpGuards.redactUrlForLogging(url),
                            "Check the model URL.");
                    notFound.detail("status", String.valueOf(status));
                    notFound.detail("reference", io.cortexj.spi.support.HttpGuards.redactUrlForLogging(url));
                    throw notFound;
                }
                HeadInfo info = new HeadInfo();
                info.contentLength = connection.getContentLengthLong();
                String etag = connection.getHeaderField("ETag");
                if (etag != null) {
                    etag = etag.replace('"', ' ').trim();
                }
                String lastModified = connection.getHeaderField("Last-Modified");
                if (etag != null && !etag.isEmpty()) {
                    info.revision = "etag-" + etag;
                } else if (lastModified != null) {
                    info.revision = "mtime-" + Integer.toHexString(lastModified.hashCode());
                } else {
                    info.revision = "unversioned-" + Integer.toHexString(url.hashCode());
                }
                return info;
            } catch (IOException probeFailure) {
                connection.disconnect();
                throw probeFailure;
            } catch (RuntimeException probeFailure) {
                connection.disconnect();
                throw probeFailure;
            }
        } catch (IOException e) {
            ModelResolutionException failure = new ModelResolutionException(ErrorCode.MODEL_RESOLUTION_FAILED,
                    "HTTP model resolution failed for "
                            + io.cortexj.spi.support.HttpGuards.redactUrlForLogging(url) + ": " + e.getMessage(),
                    "Check the URL, network connectivity and TLS configuration.");
            failure.detail("reference", io.cortexj.spi.support.HttpGuards.redactUrlForLogging(url));
            throw failure;
        }
    }

    private static String fileNameOf(String url) {
        java.net.URI uri;
        try {
            uri = java.net.URI.create(url);
        } catch (IllegalArgumentException invalidUri) {
            ModelResolutionException failure = new ModelResolutionException(ErrorCode.MODEL_RESOLUTION_FAILED,
                    "Invalid HTTP model URL: " + io.cortexj.spi.support.HttpGuards.redactUrlForLogging(url),
                    "Provide a valid HTTP(S) artifact URL with a file name.");
            failure.detail("reference", io.cortexj.spi.support.HttpGuards.redactUrlForLogging(url));
            throw failure;
        }
        String rawPath = uri.getRawPath();
        if (rawPath == null || rawPath.isEmpty() || rawPath.endsWith("/")) {
            ModelResolutionException failure = new ModelResolutionException(ErrorCode.MODEL_RESOLUTION_FAILED,
                    "HTTP model URL has no artifact file name in path: "
                            + io.cortexj.spi.support.HttpGuards.redactUrlForLogging(url),
                    "Point the URL directly at a model artifact file (for example '.../model.gguf').");
            failure.detail("reference", io.cortexj.spi.support.HttpGuards.redactUrlForLogging(url));
            throw failure;
        }
        int slash = rawPath.lastIndexOf('/');
        String rawName = slash >= 0 ? rawPath.substring(slash + 1) : rawPath;
        String name;
        try {
            name = java.net.URLDecoder.decode(rawName.replace("+", "%2B"), "UTF-8");
        } catch (java.io.UnsupportedEncodingException | IllegalArgumentException invalidEncoding) {
            ModelResolutionException failure = new ModelResolutionException(ErrorCode.MODEL_RESOLUTION_FAILED,
                    "HTTP model URL contains malformed percent-encoding in file name: "
                            + io.cortexj.spi.support.HttpGuards.redactUrlForLogging(url),
                    "Check the URL path encoding.");
            failure.detail("reference", io.cortexj.spi.support.HttpGuards.redactUrlForLogging(url));
            throw failure;
        }
        if (name.trim().isEmpty()
                || ".".equals(name)
                || "..".equals(name)
                || name.contains("..")
                || name.indexOf('/') >= 0
                || name.indexOf('\\') >= 0
                || hasControlChars(name)) {
            ModelResolutionException failure = new ModelResolutionException(ErrorCode.MODEL_RESOLUTION_FAILED,
                    "HTTP model URL contains an unsafe artifact file name '" + name + "'",
                    "Point the URL directly at a valid model artifact file.");
            failure.detail("reference", io.cortexj.spi.support.HttpGuards.redactUrlForLogging(url));
            throw failure;
        }
        return name;
    }

    private static boolean hasControlChars(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                return true;
            }
        }
        return false;
    }

    private static final class HeadInfo {
        long contentLength;
        String revision;
    }
}
