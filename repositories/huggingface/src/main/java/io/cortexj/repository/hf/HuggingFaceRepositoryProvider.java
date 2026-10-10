package io.cortexj.repository.hf;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.cortexj.ErrorCode;
import io.cortexj.error.ArtifactResolutionException;
import io.cortexj.error.ModelResolutionException;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.Checksum;
import io.cortexj.model.ModelArtifact;
import io.cortexj.model.ModelDescriptor;
import io.cortexj.model.ModelReference;
import io.cortexj.model.TaskCapability;
import io.cortexj.spi.repository.ModelRepositoryProvider;
import io.cortexj.spi.repository.RepositoryId;
import io.cortexj.spi.repository.ResolvedArtifact;
import io.cortexj.spi.repository.ResolvedModel;
import io.cortexj.spi.repository.ResolutionContext;
import io.cortexj.spi.repository.TokenSupplier;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HuggingFaceRepositoryProvider implements ModelRepositoryProvider {

    public static final String DEFAULT_API_BASE = "https://huggingface.co";
    public static final String HF_TOKEN_ENV = "HF_TOKEN";
    public static final String CORTEXJ_HF_TOKEN_ENV = "CORTEXJ_HF_TOKEN";

    private static final int MAX_REDIRECTS = 5;
    private static final int METADATA_CACHE_MAX_ENTRIES = 256;
    private static final int METADATA_FETCH_STRIPES = 16;
    // audit 2026-10-10: thundering-herd guard - N concurrent plan()/load()
    // calls after the TTL expiry all used to miss the cache and hit the HF
    // API simultaneously; a per-stripe lock re-checks the cache inside the
    // critical section so only one caller per slug ever fetches
    private final Object[] metadataFetchLocks = metadataFetchStripes();
    private static final long DEFAULT_METADATA_TTL_MILLIS = 60_000L;
    private static final String METADATA_TTL_PROPERTY = "cortexj.hf.metadata.ttlMillis";

    private final RepositoryId id = RepositoryId.of("huggingface");
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiBase;
    private final TokenSupplier defaultTokenSupplier;
    // short-TTL metadata cache (audit H-14): repeated plan()/load() of the
    // same reference must not pay a blocking HF API round-trip every time.
    // Bounded with eviction on access (audit #83): stale or surplus entries
    // must not linger for the lifetime of the JVM.
    private final Map<String, CachedMetadata> metadataCache = Collections.synchronizedMap(
            new LinkedHashMap<String, CachedMetadata>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, CachedMetadata> eldest) {
                    return size() > METADATA_CACHE_MAX_ENTRIES;
                }
            });

    private static Object[] metadataFetchStripes() {
        Object[] stripes = new Object[METADATA_FETCH_STRIPES];
        for (int i = 0; i < stripes.length; i++) {
            stripes[i] = new Object();
        }
        return stripes;
    }

    private Object metadataFetchLock(String slug) {
        return metadataFetchLocks[(slug.hashCode() & 0x7fffffff) % METADATA_FETCH_STRIPES];
    }

    private static final class CachedMetadata {
        final JsonNode node;
        final long fetchedAtMillis;

        CachedMetadata(JsonNode node, long fetchedAtMillis) {
            this.node = node;
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

    /** Explicit invalidation: drops all cached HF metadata (next resolve refetches). */
    public void invalidateCachedMetadata() {
        metadataCache.clear();
    }

    public HuggingFaceRepositoryProvider() {
        this(DEFAULT_API_BASE, null);
    }

    public HuggingFaceRepositoryProvider(String apiBase, TokenSupplier tokenSupplier) {
        this.apiBase = apiBase == null || apiBase.trim().isEmpty() ? DEFAULT_API_BASE : apiBase.trim();
        this.defaultTokenSupplier = tokenSupplier;
    }

    @Override
    public RepositoryId id() {
        return id;
    }

    @Override
    public boolean supports(ModelReference reference) {
        String value = reference.value();
        if (value.startsWith("hf:")) {
            return true;
        }
        if (value.startsWith("file:") || value.startsWith("/") || value.startsWith("./") || value.startsWith("~")) {
            return false;
        }
        return value.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
    }

    String modelSlug(ModelReference reference) {
        String value = reference.value();
        return value.startsWith("hf:") ? value.substring(3) : value;
    }

    @Override
    public ResolvedModel resolve(ModelReference reference, ResolutionContext context) {
        if (context.isOffline()) {
            ModelResolutionException failure = new ModelResolutionException(ErrorCode.MODEL_OFFLINE_UNRESOLVED,
                    "Cannot resolve " + reference.value() + " while offline mode is active.",
                    "Pre-download the model, use a local model bundle, or disable offline mode.");
            failure.detail("model", reference.value());
            failure.detail("repository", id.value());
            throw failure;
        }
        String slug = modelSlug(reference);
        JsonNode response = fetchModelInfo(slug, token(context));
        return parseModelInfo(slug, response);
    }

    public ResolvedModel parseModelInfo(String slug, JsonNode response) {
        String canonicalId = response.path("id").asText(slug);
        String revision = response.path("sha").asText(null);
        String pipelineTag = response.path("pipeline_tag").asText(null);
        String license = extractLicense(response);

        ModelDescriptor.Builder builder = ModelDescriptor.builder(canonicalId)
                .revision(revision)
                .license(license);
        boolean taskFound = false;
        for (JsonNode tag : response.path("tags")) {
            TaskCapability capability = TaskCapability.fromCommonTag(tag.asText());
            if (capability != null) {
                builder.task(capability);
                taskFound = true;
            }
        }
        TaskCapability fromPipeline = TaskCapability.fromCommonTag(pipelineTag);
        if (fromPipeline != null) {
            builder.task(fromPipeline);
            taskFound = true;
        }
        if (!taskFound) {
            builder.task(TaskCapability.TEXT_GENERATION);
        }

        List<ResolvedArtifact> artifacts = new ArrayList<ResolvedArtifact>();
        JsonNode siblings = response.path("siblings");
        boolean unverifiedArtifact = false;
        for (Iterator<JsonNode> it = siblings.elements(); it.hasNext(); ) {
            JsonNode sibling = it.next();
            String fileName = sibling.path("rfilename").asText(null);
            if (fileName == null) {
                continue;
            }
            ArtifactFormat format = ArtifactFormat.fromFileName(fileName);
            if (format == ArtifactFormat.UNKNOWN) {
                continue;
            }
            long size = sibling.path("size").asLong(0L);
            io.cortexj.model.Checksum checksum = null;
            JsonNode blob = sibling.path("blob");
            if (blob.isObject()) {
                if (size <= 0) {
                    size = blob.path("size").asLong(0L);
                }
                String sha256 = blob.path("sha256").asText(null);
                if (sha256 != null && !sha256.trim().isEmpty()) {
                    checksum = io.cortexj.model.Checksum.sha256(sha256);
                }
            } else if (size <= 0 && sibling.has("blob")) {
                size = blob.asLong(0L);
            }
            if (checksum == null) {
                JsonNode lfs = sibling.path("lfs");
                if (lfs.isObject()) {
                    String sha256 = lfs.path("sha256").asText(null);
                    if (sha256 != null && !sha256.trim().isEmpty()) {
                        checksum = io.cortexj.model.Checksum.sha256(sha256);
                        if (size <= 0) {
                            size = lfs.path("size").asLong(0L);
                        }
                    }
                }
            }
            if (checksum == null) {
                unverifiedArtifact = true;
            }
            String quantization = io.cortexj.model.descriptor.DescriptorEnricher.quantizationFromFileName(fileName);
            Map<String, String> metadata = new LinkedHashMap<String, String>();
            if (quantization != null) {
                metadata.put("quantization", quantization);
            }
            metadata.put("integrity", checksum != null ? "sha256-verified" : "unverified");
            String downloadUrl = apiBase + "/" + slug + "/resolve/" + (revision == null ? "main" : revision) + "/" + fileName;

            HfArtifact artifact = new HfArtifact(canonicalId + "/" + fileName, format, size, checksum, metadata);
            Map<String, String> headers = new LinkedHashMap<String, String>();
            artifacts.add(new HfResolvedArtifact(artifact, URI.create(downloadUrl), headers));
        }

        // audit #61: a community upload or an artifact without a verified LFS
        // sha256 must not silently pass as trusted - TRUSTED_ONLY blocks only
        // when the descriptor carries a trustNote
        List<String> trustReasons = new ArrayList<String>();
        if (unverifiedArtifact) {
            trustReasons.add("artifact sha256 not verified against the Hugging Face LFS blob");
        }
        if (!gatedModel(response)) {
            trustReasons.add("community upload without verified provenance");
        }
        if (!trustReasons.isEmpty()) {
            builder.trustNote(String.join("; ", trustReasons));
        }

        if (revision == null) {
            throw new ModelResolutionException(ErrorCode.MODEL_REVISION_UNRESOLVED,
                    "Hugging Face did not report an immutable revision for " + slug,
                    "Retry resolution or pin an explicit revision.");
        }
        return new ResolvedModel(builder.artifacts(toArtifactList(artifacts)).build(), revision, id, artifacts);
    }

    private JsonNode fetchModelInfo(String slug, String token) {
        long ttl = metadataTtlMillis();
        if (ttl > 0) {
            CachedMetadata cached = metadataCache.get(slug);
            if (cached != null) {
                if (System.currentTimeMillis() - cached.fetchedAtMillis < ttl) {
                    return cached.node;
                }
                // audit #83: evict the expired entry instead of leaving it resident
                metadataCache.remove(slug);
            }
        }
        // audit 2026-10-10: singleflight per stripe - concurrent misses of
        // the same slug serialize here; the winner fetches, the losers find
        // the fresh entry in the re-check below
        synchronized (metadataFetchLock(slug)) {
            if (ttl > 0) {
                CachedMetadata cached = metadataCache.get(slug);
                if (cached != null
                        && System.currentTimeMillis() - cached.fetchedAtMillis < ttl) {
                    return cached.node;
                }
            }
            JsonNode response = fetchModelInfoFromApi(slug, token);
            if (ttl > 0) {
                metadataCache.put(slug, new CachedMetadata(response, System.currentTimeMillis()));
            }
            return response;
        }
    }

    private JsonNode fetchModelInfoFromApi(String slug, String token) {
        // audit #78: the slug must be URI-encoded or a '#' silently turns
        // ?blobs=true into a fragment and the LFS sha256s are lost
        URI location;
        try {
            location = URI.create(apiBase + "/api/models/" + encodeSlug(slug) + "?blobs=true");
        } catch (IllegalArgumentException e) {
            throw new ModelResolutionException(ErrorCode.MODEL_RESOLUTION_FAILED,
                    "Invalid Hugging Face apiBase or model slug: " + apiBase + "/" + slug,
                    "Check the apiBase configuration and the model id (expected 'owner/name').", e);
        }
        // audit 2026-10-08: the API base is operator configuration, but a
        // typo (or a poisoned config) pointing at an internal host must not
        // receive the bearer token - validate before the first hop
        try {
            io.cortexj.spi.support.HttpGuards.requirePublicHttpUrl(location.toString());
        } catch (IllegalArgumentException blocked) {
            throw new ModelResolutionException(ErrorCode.MODEL_RESOLUTION_FAILED,
                    "Hugging Face apiBase blocked by the SSRF guard: " + blocked.getMessage(),
                    "Point apiBase at the public API or set "
                            + io.cortexj.spi.support.HttpGuards.ALLOW_PRIVATE_HOSTS_PROPERTY
                            + "=true for mirrors.");
        }
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Accept", "application/json");
        if (token != null && !token.isEmpty()) {
            headers.put("Authorization", "Bearer " + token);
        }
        URI current = location;
        // audit #78: auto-redirects are disabled and hops are followed manually -
        // a redirect of the API base must not leak the bearer token cross-origin
        for (int hop = 0; hop < MAX_REDIRECTS; hop++) {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) current.toURL().openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(10_000);
                connection.setReadTimeout(30_000);
                connection.setInstanceFollowRedirects(false);
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }
                int status = connection.getResponseCode();
                if (status >= 300 && status < 400) {
                    String target = connection.getHeaderField("Location");
                    if (target == null) {
                        throw new IOException("Redirect without Location header from "
                                + io.cortexj.spi.support.HttpGuards.redactUrlForLogging(
                                current.toString()));
                    }
                    URI next = current.resolve(target);
                    String nextScheme = next.getScheme() == null ? "" : next.getScheme();
                    if (!nextScheme.equalsIgnoreCase("http") && !nextScheme.equalsIgnoreCase("https")) {
                        ModelResolutionException rejected = new ModelResolutionException(ErrorCode.MODEL_RESOLUTION_FAILED,
                                "Redirect target uses a non-http(s) scheme: "
                                        + io.cortexj.spi.support.HttpGuards.redactUrlForLogging(
                                        next.toString()),
                                "Redirect hops must stay http(s); metadata fetch aborted before "
                                        + "any credential reached the target.");
                        rejected.detail("redirectTarget", io.cortexj.spi.support.HttpGuards
                                .redactUrlForLogging(next.toString()));
                        throw rejected;
                    }
                    // audit 2026-10-08: a redirect to an internal target must
                    // not be fetched with the bearer token attached
                    try {
                        io.cortexj.spi.support.HttpGuards.requirePublicHttpUrl(next.toString());
                    } catch (IllegalArgumentException blocked) {
                        ModelResolutionException ssrf = new ModelResolutionException(
                                ErrorCode.MODEL_RESOLUTION_FAILED,
                                "Redirect target blocked by the SSRF guard: " + blocked.getMessage(),
                                "Redirect hops must stay on public hosts.");
                        ssrf.detail("redirectTarget", io.cortexj.spi.support.HttpGuards
                                .redactUrlForLogging(next.toString()));
                        throw ssrf;
                    }
                    if (!sameOrigin(current, next)) {
                        headers = stripCredentials(headers);
                    }
                    connection.disconnect();
                    current = next;
                    continue;
                }
                if (status == 404) {
                    ModelResolutionException notFound = new ModelResolutionException(ErrorCode.MODEL_NOT_FOUND,
                            "Hugging Face model not found: " + slug,
                            "Check the model id (expected 'owner/name').");
                    notFound.detail("model", slug);
                    throw notFound;
                }
                if (status != 200) {
                    ModelResolutionException failed = new ModelResolutionException(ErrorCode.MODEL_RESOLUTION_FAILED,
                            "Hugging Face API returned status " + status + " for " + slug, "Retry later or check network access.");
                    failed.detail("status", String.valueOf(status));
                    throw failed;
                }
                try (InputStream input = connection.getInputStream()) {
                    return mapper.readTree(input);
                }
            } catch (IOException e) {
                if (connection != null) {
                    connection.disconnect();
                }
                throw new ModelResolutionException(ErrorCode.MODEL_RESOLUTION_FAILED,
                        "Cannot reach Hugging Face for model " + slug + ": " + e.getMessage(),
                        "Verify network access to huggingface.co.", e);
            } catch (RuntimeException e) {
                if (connection != null) {
                    connection.disconnect();
                }
                throw e;
            }
        }
        ModelResolutionException tooMany = new ModelResolutionException(ErrorCode.MODEL_RESOLUTION_FAILED,
                "Too many redirects while fetching Hugging Face metadata for " + slug,
                "Check the apiBase configuration for redirect loops.");
        tooMany.detail("redirects", String.valueOf(MAX_REDIRECTS));
        throw tooMany;
    }

    static String encodeSlug(String slug) {
        String[] segments = slug.split("/");
        StringBuilder encoded = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                encoded.append('/');
            }
            try {
                encoded.append(URLEncoder.encode(segments[i], "UTF-8").replace("+", "%20"));
            } catch (UnsupportedEncodingException e) {
                encoded.append(segments[i]);
            }
        }
        return encoded.toString();
    }

    private static boolean gatedModel(JsonNode response) {
        JsonNode gated = response.get("gated");
        if (gated == null || gated.isNull()) {
            return false;
        }
        if (gated.isBoolean()) {
            return gated.asBoolean();
        }
        String text = gated.asText("").trim();
        return !text.isEmpty() && !"false".equalsIgnoreCase(text);
    }

    private static boolean sameOrigin(URI a, URI b) {
        if (a.getScheme() == null || b.getScheme() == null
                || !a.getScheme().equalsIgnoreCase(b.getScheme())) {
            return false;
        }
        if (a.getHost() == null || b.getHost() == null
                || !a.getHost().equalsIgnoreCase(b.getHost())) {
            return false;
        }
        return effectivePort(a) == effectivePort(b);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() != -1) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static Map<String, String> stripCredentials(Map<String, String> headers) {
        Map<String, String> stripped = new LinkedHashMap<String, String>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String name = entry.getKey();
            if ("Authorization".equalsIgnoreCase(name) || "Cookie".equalsIgnoreCase(name)) {
                continue;
            }
            stripped.put(name, entry.getValue());
        }
        return stripped;
    }

    private static String token(ResolutionContext context) {
        TokenSupplier supplier = context.tokenSupplier();
        if (supplier != null) {
            String token = supplier.token();
            if (token != null && !token.isEmpty()) {
                return token;
            }
        }
        String env = System.getenv(CORTEXJ_HF_TOKEN_ENV);
        if (env != null && !env.isEmpty()) {
            return env;
        }
        String generic = System.getenv(HF_TOKEN_ENV);
        if (generic != null && !generic.isEmpty()) {
            return generic;
        }
        String property = System.getProperty("cortexj.hf.token");
        // audit 2026-10-07 (#98, parity with #125) + 2026-10-08: warn whenever
        // the sysprop is set and non-empty - a simultaneously set env var
        // does not stop jcmd/hs_err dumps from exposing the sysprop value
        if (property != null && !property.isEmpty()) {
            java.util.logging.Logger.getLogger(HuggingFaceRepositoryProvider.class.getName()).warning(
                    "cortexj.hf.token is set via a system property; system properties "
                            + "are readable by 'jcmd VM.system_properties' and hs_err dumps - "
                            + "prefer the CORTEXJ_HF_TOKEN environment variable");
        }
        return property == null || property.isEmpty() ? null : property;
    }

    private static String extractLicense(JsonNode response) {
        JsonNode licenseField = response.get("license");
        if (licenseField != null && licenseField.isTextual()) {
            return licenseField.asText();
        }
        for (JsonNode tag : response.path("tags")) {
            String text = tag.asText("");
            if (text.startsWith("license:")) {
                return text.substring("license:".length());
            }
        }
        return null;
    }

    private static List<ModelArtifact> toArtifactList(List<ResolvedArtifact> artifacts) {
        List<ModelArtifact> list = new ArrayList<ModelArtifact>();
        for (ResolvedArtifact artifact : artifacts) {
            list.add(artifact.artifact());
        }
        return list;
    }

    static final class HfArtifact extends ModelArtifact.Abstract {

        HfArtifact(String id, ArtifactFormat format, long sizeBytes, Checksum checksum, Map<String, String> metadata) {
            super(id, format, sizeBytes, checksum, metadata);
        }
    }

    static final class HfResolvedArtifact extends ResolvedArtifact.Abstract {

        HfResolvedArtifact(ModelArtifact artifact, URI location, Map<String, String> headers) {
            super(artifact, location, headers);
        }
    }
}
