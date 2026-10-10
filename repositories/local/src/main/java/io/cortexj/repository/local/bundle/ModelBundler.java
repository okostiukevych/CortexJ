package io.cortexj.repository.local.bundle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cortexj.ErrorCode;
import io.cortexj.cache.CacheStorage;
import io.cortexj.cache.Checksums;
import io.cortexj.error.ArtifactResolutionException;
import io.cortexj.error.ModelResolutionException;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelReference;
import io.cortexj.model.TaskCapability;
import io.cortexj.spi.repository.ModelRepositoryProvider;
import io.cortexj.spi.repository.ResolvedArtifact;
import io.cortexj.spi.repository.ResolvedModel;
import io.cortexj.spi.repository.ResolutionContext;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class ModelBundler {

    public static final String MANIFEST_FILE = "cortexj-model.json";

    /**
     * Bundle manifest schema version (ADR-020, issue #159): major
     * gates compatibility, minor is forward-compatible. Absent version
     * means a pre-1.0 legacy bundle and is accepted as 1.x.
     */
    public static final int MANIFEST_SCHEMA_MAJOR = 1;
    public static final int MANIFEST_SCHEMA_MINOR = 0;
    private static final int CONNECT_TIMEOUT = 15_000;
    private static final int READ_TIMEOUT = 300_000;
    private static final int MAX_REDIRECTS = 5;
    // mirrors core DownloadManager's DEFAULT_MAX_ARTIFACT_BYTES (core is not
    // a dependency of this module)
    private static final long DEFAULT_MAX_ARTIFACT_BYTES = 100L * 1024L * 1024L * 1024L;
    private static final java.util.concurrent.atomic.AtomicLong TEMP_SEQUENCE =
            new java.util.concurrent.atomic.AtomicLong();

    private final List<ModelRepositoryProvider> repositories;
    private final CacheStorage cache;
    private final ObjectMapper mapper = new ObjectMapper();

    public ModelBundler(List<ModelRepositoryProvider> repositories, CacheStorage cache) {
        this.repositories = new ArrayList<ModelRepositoryProvider>(repositories);
        this.cache = cache;
    }

    public Path create(ModelReference reference, Path outputDirectory, ResolutionContext context) {
        ResolvedModel resolved = resolve(reference, context);
        // audit 2026-10-10: a failed create must not leave a half-written
        // bundle behind (a later verify would report confusing checksum
        // mismatches instead of "incomplete"). Everything is staged in a
        // sibling temp directory and published only on success.
        Path directoryName = outputDirectory.getFileName();
        final Path staging;
        final boolean staged;
        if (directoryName == null) {
            staging = outputDirectory;
            staged = false;
        } else {
            staging = outputDirectory.resolveSibling(directoryName + ".part-"
                    + Long.toHexString(System.nanoTime())
                    + "-" + TEMP_SEQUENCE.incrementAndGet());
            staged = true;
        }
        boolean published = false;
        try {
            buildBundle(resolved, context, staging);
            if (staged) {
                publishStaged(staging, outputDirectory);
            }
            published = true;
        } finally {
            if (staged && !published) {
                deleteRecursively(staging);
            }
        }
        return outputDirectory;
    }

    private void buildBundle(ResolvedModel resolved, ResolutionContext context, Path outputDirectory) {
        try {
            Files.createDirectories(outputDirectory);
        } catch (IOException e) {
            throw new ArtifactResolutionException(ErrorCode.ARTIFACT_RESOLUTION_FAILED,
                    "Cannot create bundle directory " + outputDirectory + ": " + e.getMessage(), e);
        }

        ArrayNode artifactEntries = mapper.createArrayNode();
        ObjectNode manifest = mapper.createObjectNode();
        manifest.put("manifestVersion", MANIFEST_SCHEMA_MAJOR + "." + MANIFEST_SCHEMA_MINOR);
        manifest.put("id", resolved.descriptor().canonicalId());
        manifest.put("revision", resolved.revision());
        if (resolved.descriptor().architecture() != null) {
            manifest.put("architecture", resolved.descriptor().architecture());
        }
        if (resolved.descriptor().license() != null) {
            manifest.put("license", resolved.descriptor().license());
        }
        if (resolved.descriptor().contextLength() > 0) {
            manifest.put("contextLength", resolved.descriptor().contextLength());
        }
        if (resolved.descriptor().parameterCount() > 0) {
            manifest.put("parameterCount", resolved.descriptor().parameterCount());
        }
        ArrayNode tasks = manifest.putArray("tasks");
        for (TaskCapability task : resolved.descriptor().tasks()) {
            tasks.add(task.commonTag());
        }

        java.util.Set<String> usedNames = new java.util.HashSet<String>();
        List<Path> executableTargets = new ArrayList<Path>();
        Map<String, Path> tokenizerSources = new LinkedHashMap<String, Path>();
        for (ResolvedArtifact artifact : resolved.artifacts()) {
            String fileName = uniqueBundleName(resolved, artifact, usedNames);
            Path target = outputDirectory.resolve(fileName);
            Path parent = target.getParent();
            if (parent != null) {
                try {
                    Files.createDirectories(parent);
                } catch (IOException directoriesFailed) {
                    throw new ArtifactResolutionException(ErrorCode.ARTIFACT_RESOLUTION_FAILED,
                            "Cannot create bundle subdirectory " + parent + ": "
                                    + directoriesFailed.getMessage(), directoriesFailed);
                }
                // audit 2026-10-10: a pre-planted symlink in the output tree
                // (e.g. outputDirectory/onnx -> /etc) must not receive the
                // artifact bytes - verify() is symlink-safe, create() now is too
                if (Files.isSymbolicLink(parent)) {
                    throw new ArtifactResolutionException(ErrorCode.ARTIFACT_PATH_TRAVERSAL,
                            "Bundle subdirectory is a symlink: " + parent,
                            "Bundle artifact paths must stay inside the bundle directory.");
                }
            }
            obtainInto(artifact, resolved, target, context);
            verifyDeclaredChecksum(artifact, fileName, target);
            String sha256;
            try {
                sha256 = Checksums.sha256Hex(target);
            } catch (IOException e) {
                throw new ArtifactResolutionException(ErrorCode.ARTIFACT_RESOLUTION_FAILED,
                        "Cannot hash bundled artifact " + fileName + ": " + e.getMessage(), e);
            }
            ObjectNode entry = artifactEntries.addObject();
            entry.put("file", fileName);
            entry.put("format", artifact.artifact().format().name());
            entry.put("size", sizeOf(target));
            entry.put("sha256", sha256);
            // audit #76: only a repository-declared checksum may be recorded
            // as verified; otherwise the gap must stay visible in the manifest
            entry.put("integrity", artifact.artifact().checksum() != null ? "sha256-verified" : "unverified");
            String quantization = artifact.artifact().metadata().get("quantization");
            if (quantization != null) {
                entry.put("quantization", quantization);
            }
            Path fileNamePath = target.getFileName();
            if (artifact.artifact().format() != null
                    && artifact.artifact().format().isExecutableWeights()
                    && (fileNamePath == null || !"tokenizer.json".equals(fileNamePath.toString()))) {
                executableTargets.add(target);
            }
            if (artifact.artifact().format() == ArtifactFormat.TOKENIZER) {
                if (fileNamePath != null) {
                    tokenizerSources.put(fileNamePath.toString(), target);
                }
            }
        }
        stageTokenizerSiblings(executableTargets, tokenizerSources);
        manifest.set("artifacts", artifactEntries);

        Path manifestPath = outputDirectory.resolve(MANIFEST_FILE);
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(manifestPath.toFile(), manifest);
        } catch (IOException e) {
            throw new ArtifactResolutionException(ErrorCode.ARTIFACT_RESOLUTION_FAILED,
                    "Cannot write bundle manifest: " + e.getMessage(), e);
        }
    }

    /**
     * Real-model verification 2026-10-10: hierarchical bundles separate
     * weights from tokenizers ({@code onnx/model.onnx} vs root
     * {@code tokenizer.json}), but runtimes discover the tokenizer as a
     * sibling of the weight file. Copy each bundled tokenizer next to
     * every executable weight that lacks one. The copies are derivative
     * (no manifest entries): {@code verify} ignores unlisted files and a
     * future {@code bundle create} from this bundle re-derives them.
     */
    private static void stageTokenizerSiblings(List<Path> executableTargets,
                                               Map<String, Path> tokenizerSources) {
        if (executableTargets.isEmpty() || tokenizerSources.isEmpty()) {
            return;
        }
        for (Path weight : executableTargets) {
            Path directory = weight.getParent();
            if (directory == null) {
                continue;
            }
            for (Map.Entry<String, Path> tokenizer : tokenizerSources.entrySet()) {
                Path sibling = directory.resolve(tokenizer.getKey());
                if (Files.isRegularFile(sibling)) {
                    continue;
                }
                try {
                    Files.copy(tokenizer.getValue(), sibling);
                } catch (IOException copyFailed) {
                    throw new ArtifactResolutionException(ErrorCode.ARTIFACT_RESOLUTION_FAILED,
                            "Cannot stage " + tokenizer.getKey() + " next to "
                                    + weight.getFileName() + ": " + copyFailed.getMessage(),
                            copyFailed);
                }
            }
        }
    }

    public BundleReport verify(Path bundleDirectory) {
        Path manifestPath = bundleDirectory.resolve(MANIFEST_FILE);
        List<String> issues = new ArrayList<String>();
        String modelId = null;
        String revision = null;
        int verified = 0;
        int unverified = 0;

        if (!Files.isRegularFile(manifestPath)) {
            issues.add("bundle has no " + MANIFEST_FILE);
            return new BundleReport(null, null, 0, 0, issues);
        }
        try {
            JsonNode manifest;
            try (InputStream manifestInput = Files.newInputStream(manifestPath)) {
                manifest = mapper.readTree(manifestInput);
            }
            modelId = manifest.path("id").asText(null);
            revision = manifest.path("revision").asText(null);
            if (modelId == null || modelId.isEmpty()) {
                issues.add("manifest is missing the model id");
            }
            // audit (issue #159): unknown majors fail, newer
            // minors pass, absent version is a legacy 1.x bundle
            String manifestVersion = manifest.path("manifestVersion").asText(null);
            if (manifestVersion != null && !manifestVersion.trim().isEmpty()) {
                int major = manifestMajorOf(manifestVersion);
                if (major < 0) {
                    issues.add("bundle manifest has an unparsable version '"
                            + manifestVersion + "'; expected '<major>.<minor>'");
                } else if (major != MANIFEST_SCHEMA_MAJOR) {
                    issues.add("bundle manifest schema major version " + major
                            + " is not supported by this build (reads "
                            + MANIFEST_SCHEMA_MAJOR + ".x); re-create the bundle with a "
                            + "compatible CortexJ version");
                    return new BundleReport(modelId, revision, verified, unverified, issues);
                }
            }
            for (JsonNode entry : manifest.path("artifacts")) {
                String file = entry.path("file").asText(null);
                if (file == null || file.isEmpty()) {
                    issues.add("artifact entry without a file name");
                    continue;
                }
                Path artifactPath = resolveContained(bundleDirectory, file);
                if (!Files.isRegularFile(artifactPath)) {
                    issues.add("artifact missing: " + file);
                    continue;
                }
                long declaredSize = entry.path("size").asLong(-1);
                long actualSize = sizeOf(artifactPath);
                if (declaredSize >= 0 && declaredSize != actualSize) {
                    issues.add("artifact " + file + " size mismatch: declared " + declaredSize
                            + ", actual " + actualSize);
                }
                String declaredSha = entry.path("sha256").asText(null);
                if (declaredSha != null && !declaredSha.isEmpty()) {
                    String actualSha = Checksums.sha256Hex(artifactPath);
                    if (!declaredSha.toLowerCase(Locale.ROOT).equals(actualSha)) {
                        issues.add("artifact " + file + " checksum mismatch: bundle is corrupted or tampered with");
                        continue;
                    }
                }
                if ("unverified".equalsIgnoreCase(entry.path("integrity").asText(""))) {
                    // audit #76: unverified entries are reported honestly, not counted as verified
                    unverified++;
                } else {
                    verified++;
                }
            }
        } catch (IOException e) {
            issues.add("cannot read bundle manifest: " + e.getMessage());
        }
        return new BundleReport(modelId, revision, verified, unverified, issues);
    }

    private static int manifestMajorOf(String manifestVersion) {
        String raw = manifestVersion.trim();
        int dot = raw.indexOf('.');
        String major = dot < 0 ? raw : raw.substring(0, dot);
        try {
            return Integer.parseInt(major.trim());
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }

    private ResolvedModel resolve(ModelReference reference, ResolutionContext context) {
        ModelResolutionException last = null;
        for (ModelRepositoryProvider repository : repositories) {
            if (!repository.supports(reference)) {
                continue;
            }
            try {
                return repository.resolve(reference, context);
            } catch (RuntimeException e) {
                if (e instanceof ModelResolutionException) {
                    last = (ModelResolutionException) e;
                    continue;
                }
                throw e;
            }
        }
        if (last != null) {
            throw last;
        }
        throw new ModelResolutionException(ErrorCode.MODEL_NOT_FOUND,
                "No repository provider can resolve model reference: " + reference.value(),
                "Check the reference format.")
                .detail("reference", reference.value());
    }

    /**
     * Moves a staged bundle into its final location. A missing output
     * directory is published with a single atomic rename; an existing one
     * is updated entry by entry (bundle create merges into existing dirs).
     */
    private static void publishStaged(Path staging, Path outputDirectory) {
        try {
            if (!Files.exists(outputDirectory)) {
                Path parent = outputDirectory.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try {
                    Files.move(staging, outputDirectory, StandardCopyOption.ATOMIC_MOVE);
                    return;
                } catch (IOException atomicUnsupported) {
                    // fall through to the per-entry move below
                }
            }
            final Path staged = staging;
            final Path output = outputDirectory;
            Files.walkFileTree(staging, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult preVisitDirectory(
                        Path dir, java.nio.file.attribute.BasicFileAttributes attrs)
                        throws IOException {
                    Files.createDirectories(output.resolve(staged.relativize(dir)));
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult visitFile(
                        Path file, java.nio.file.attribute.BasicFileAttributes attrs)
                        throws IOException {
                    Path target = output.resolve(staged.relativize(file));
                    try {
                        Files.move(file, target,
                                StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException atomicFailed) {
                        Files.move(file, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
            deleteRecursively(staging);
        } catch (IOException publishFailed) {
            throw new ArtifactResolutionException(ErrorCode.ARTIFACT_RESOLUTION_FAILED,
                    "Cannot publish bundle to " + outputDirectory + ": "
                            + publishFailed.getMessage(), publishFailed);
        }
    }

    private static void deleteRecursively(Path directory) {
        if (directory == null) {
            return;
        }
        try {
            Files.walkFileTree(directory, new java.nio.file.SimpleFileVisitor<Path>() {
                @Override
                public java.nio.file.FileVisitResult visitFile(
                        Path file, java.nio.file.attribute.BasicFileAttributes attrs)
                        throws IOException {
                    Files.deleteIfExists(file);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }

                @Override
                public java.nio.file.FileVisitResult postVisitDirectory(
                        Path dir, java.io.IOException failure) throws IOException {
                    Files.deleteIfExists(dir);
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ignored) {
            // best-effort staging cleanup; never mask the original failure
        }
    }

    private void obtainInto(ResolvedArtifact artifact, ResolvedModel resolved, Path target, ResolutionContext context) {
        String scheme = artifact.location() == null ? "" : artifact.location().getScheme();
        try {
            if (scheme.equals("file")) {
                Path source = java.nio.file.Paths.get(artifact.location());
                if (!Files.isRegularFile(source)) {
                    throw new ArtifactResolutionException(ErrorCode.ARTIFACT_MISSING,
                            "Source artifact does not exist: " + source,
                            "Verify the model file path.")
                            .detail("path", source.toString());
                }
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                return;
            }
            if (cache != null && resolved.hasRevision()) {
                Path cached = cache.get(resolved.repository().value(), resolved.revision(),
                        artifact.artifact().id(), artifact.artifact().checksum());
                if (cached != null) {
                    Files.copy(cached, target, StandardCopyOption.REPLACE_EXISTING);
                    return;
                }
            }
            if (context.isOffline()) {
                throw new ModelResolutionException(ErrorCode.MODEL_OFFLINE_UNRESOLVED,
                        "Artifact " + artifact.artifact().id() + " is not cached and offline mode is active.",
                        "Pre-download the model or disable offline mode.")
                        .detail("artifact", artifact.artifact().id());
            }
            downloadInto(artifact, target);
        } catch (IOException e) {
            throw new ArtifactResolutionException(ErrorCode.ARTIFACT_RESOLUTION_FAILED,
                    "Cannot bundle artifact " + artifact.artifact().id() + ": " + e.getMessage(), e);
        }
    }

    private void downloadInto(ResolvedArtifact artifact, Path target) throws IOException {
        long declaredSize = artifact.artifact().sizeBytes();
        if (declaredSize > DEFAULT_MAX_ARTIFACT_BYTES) {
            ArtifactResolutionException tooLarge = new ArtifactResolutionException(ErrorCode.ARTIFACT_TOO_LARGE,
                    "Artifact " + artifact.artifact().id() + " declared size " + declaredSize
                            + " exceeds the configured maximum of " + DEFAULT_MAX_ARTIFACT_BYTES + " bytes.",
                    "Raise the maximum artifact size or choose a smaller variant.");
            tooLarge.detail("artifact", artifact.artifact().id());
            tooLarge.detail("declaredBytes", String.valueOf(declaredSize));
            throw tooLarge;
        }
        long cap = declaredSize > 0 ? declaredSize : DEFAULT_MAX_ARTIFACT_BYTES;
        // audit #119: nanoTime alone can collide across threads (~1us
        // granularity) - the sequence suffix makes concurrent bundle temp
        // names unique, the same fix CacheStorage applied in 36fced8
        Path temp = target.resolveSibling(target.getFileName() + ".part-"
                + Long.toHexString(System.nanoTime())
                + "-" + TEMP_SEQUENCE.incrementAndGet());
        RedirectTarget redirect = resolveRedirectTarget(artifact);
        URL url = redirect.url;
        Map<String, String> effectiveHeaders = redirect.headers;
        HttpURLConnection connection = open(url);
        boolean moved = false;
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);
            // audit #128: auto-follow stays off - redirects are followed
            // manually below (resolveRedirectTarget) so every hop passes the
            // SSRF guard and credentials never leak cross-origin
            connection.setInstanceFollowRedirects(false);
            for (Map.Entry<String, String> header : effectiveHeaders.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            int status = connection.getResponseCode();
            if (status != 200) {
                throw new IOException("HTTP " + status + " while downloading " + artifact.artifact().id());
            }
            try (InputStream input = connection.getInputStream()) {
                long copied = 0L;
                try (java.io.OutputStream output = Files.newOutputStream(temp)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = input.read(buffer)) >= 0) {
                        // audit 2026-10-10: cooperate with interruption - a
                        // stuck download must not wait out the 300 s socket
                        // timeout after the user gave up
                        if (Thread.currentThread().isInterrupted()) {
                            throw new IOException("bundle download interrupted");
                        }
                        // audit #59: the byte cap must be enforced inside the
                        // copy loop - a hostile server must not stream
                        // unbounded bytes before any check fires
                        if (copied + read > cap) {
                            ArtifactResolutionException tooLarge = new ArtifactResolutionException(
                                    ErrorCode.ARTIFACT_TOO_LARGE,
                                    "Artifact " + artifact.artifact().id() + " streamed " + (copied + read)
                                            + " bytes, exceeding the cap of " + cap + " bytes.",
                                    "Raise the maximum artifact size or choose a smaller variant.");
                            tooLarge.detail("artifact", artifact.artifact().id());
                            tooLarge.detail("streamedBytes", String.valueOf(copied + read));
                            tooLarge.detail("limitBytes", String.valueOf(cap));
                            throw tooLarge;
                        }
                        output.write(buffer, 0, read);
                        copied += read;
                    }
                }
                if (artifact.artifact().sizeBytes() > 0 && copied != artifact.artifact().sizeBytes()) {
                    throw new IOException("truncated download: expected " + artifact.artifact().sizeBytes()
                            + " bytes, got " + copied);
                }
            }
            // audit 2026-10-10: atomic replace without an explicit delete -
            // delete-then-move left a missing-artifact window for a
            // concurrent create/verify of the same directory
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException atomicFailed) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
        } catch (IOException downloadFailure) {
            connection.disconnect();
            throw downloadFailure;
        } catch (RuntimeException downloadFailure) {
            connection.disconnect();
            throw downloadFailure;
        } finally {
            if (!moved) {
                // audit M-10: a failed or truncated download must not strand
                // a model-sized partial next to the bundle
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                }
            }
        }
    }

    /**
     * Real-model verification 2026-10-10: Hugging Face {@code /resolve/}
     * URLs answer 302/307 to the CDN, so a bundler that refuses redirects
     * fails {@code bundle create} for every uncached artifact. Hops are
     * followed manually (mirroring core DownloadManager): every hop passes
     * the SSRF guard and credentials are stripped cross-origin - the JDK
     * auto-follow it replaces would have carried the request headers.
     */
    /**
     * Audit 2026-10-10: honor the platform egress proxy
     * ({@code cortexj.proxy.host}/{@code CORTEXJ_PROXY_HOST} + port) so
     * proxy-enforced audit/containment covers {@code bundle create} too -
     * previously the bundler connected directly, bypassing it.
     */
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

    private RedirectTarget resolveRedirectTarget(ResolvedArtifact artifact) throws IOException {
        String raw = artifact.location() == null ? null : artifact.location().toString();
        if (raw == null || raw.isEmpty()) {
            throw new IOException("Artifact " + artifact.artifact().id() + " has no downloadable location");
        }
        try {
            io.cortexj.spi.support.HttpGuards.requirePublicHttpUrl(raw);
        } catch (IllegalArgumentException blocked) {
            throw new IOException("Artifact location blocked by the SSRF guard: " + blocked.getMessage());
        }
        java.net.URI current;
        try {
            current = new java.net.URI(raw);
        } catch (java.net.URISyntaxException invalid) {
            throw new IOException("Artifact location is not a valid URI: " + raw);
        }
        Map<String, String> hopHeaders = new LinkedHashMap<String, String>(artifact.downloadHeaders());
        for (int hop = 0; hop < MAX_REDIRECTS; hop++) {
            String scheme = current.getScheme() == null ? "" : current.getScheme();
            if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
                throw new IOException("Redirect target uses a non-http(s) scheme: "
                        + io.cortexj.spi.support.HttpGuards.redactUrlForLogging(current.toString()));
            }
            HttpURLConnection probe = open(current.toURL());
            try {
                probe.setConnectTimeout(CONNECT_TIMEOUT);
                probe.setReadTimeout(READ_TIMEOUT);
                probe.setInstanceFollowRedirects(false);
                for (Map.Entry<String, String> header : hopHeaders.entrySet()) {
                    probe.setRequestProperty(header.getKey(), header.getValue());
                }
                int status = probe.getResponseCode();
                if (status >= 300 && status < 400) {
                    String target = probe.getHeaderField("Location");
                    if (target == null) {
                        throw new IOException("Redirect without Location header from "
                                + io.cortexj.spi.support.HttpGuards.redactUrlForLogging(
                                        current.toString()));
                    }
                    java.net.URI next = current.resolve(target);
                    try {
                        io.cortexj.spi.support.HttpGuards.requirePublicHttpUrl(next.toString());
                    } catch (IllegalArgumentException blocked) {
                        throw new IOException("Redirect target blocked by the SSRF guard: "
                                + blocked.getMessage());
                    }
                    if (!sameOrigin(current, next)) {
                        hopHeaders = stripCredentials(hopHeaders);
                    }
                    current = next;
                    continue;
                }
                if (status == 200) {
                    return new RedirectTarget(current.toURL(), hopHeaders);
                }
                throw new IOException("HTTP " + status + " while resolving " + artifact.artifact().id());
            } finally {
                probe.disconnect();
            }
        }
        throw new IOException("Too many redirects while downloading " + artifact.artifact().id());
    }

    private static final class RedirectTarget {
        final URL url;
        final Map<String, String> headers;

        RedirectTarget(URL url, Map<String, String> headers) {
            this.url = url;
            this.headers = headers;
        }
    }

    private static boolean sameOrigin(java.net.URI a, java.net.URI b) {
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

    private static int effectivePort(java.net.URI uri) {
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

    private void verifyDeclaredChecksum(ResolvedArtifact artifact, String fileName, Path target) {
        io.cortexj.model.Checksum declared = artifact.artifact().checksum();
        if (declared == null) {
            return;
        }
        // audit #76: the bytes must match the checksum declared by the
        // repository BEFORE the manifest records them as verified, or a
        // tampered download is laundered into a "verified" bundle
        String actual;
        try {
            actual = Checksums.sha256Hex(target);
        } catch (IOException e) {
            throw new ArtifactResolutionException(ErrorCode.ARTIFACT_RESOLUTION_FAILED,
                    "Cannot hash bundled artifact " + fileName + ": " + e.getMessage(), e);
        }
        if (!declared.value().equals(actual)) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException ignored) {
            }
            ArtifactResolutionException mismatch = new ArtifactResolutionException(ErrorCode.ARTIFACT_CHECKSUM_MISMATCH,
                    "Bundled artifact " + fileName + " does not match the checksum declared by the repository: "
                            + "the bundle would have recorded tampered content as verified.",
                    "Verify the repository source; re-run the bundle from a trusted mirror.");
            mismatch.detail("artifact", artifact.artifact().id());
            throw mismatch;
        }
    }

    private static Path resolveContained(Path directory, String child) {
        Path resolved = directory.resolve(child).toAbsolutePath().normalize();
        Path normalizedRoot = directory.toAbsolutePath().normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            ArtifactResolutionException failure = new ArtifactResolutionException(ErrorCode.ARTIFACT_PATH_TRAVERSAL,
                    "Bundle artifact path escapes the bundle directory: " + child,
                    "Manifest artifact paths must stay inside the bundle directory.");
            failure.detail("path", child);
            throw failure;
        }
        try {
            // audit #73: normalize() does not resolve symlinks - containment
            // must hold for the real path or a bundle entry can link
            // outside the root and be hashed/read through the link
            Path realRoot = normalizedRoot.toRealPath();
            Path realResolved = resolved.toRealPath();
            if (!realResolved.startsWith(realRoot)) {
                ArtifactResolutionException escaped = new ArtifactResolutionException(ErrorCode.ARTIFACT_PATH_TRAVERSAL,
                        "Bundle artifact path resolves outside the bundle directory through a symlink: " + child,
                        "Manifest artifact paths must stay inside the bundle directory "
                                + "and must not symlink outside it.");
                escaped.detail("path", child);
                throw escaped;
            }
        } catch (IOException e) {
            // missing artifact entry: fall back to the lexical check above
        }
        return resolved;
    }

    /**
     * Real-model verification 2026-10-10: flattening every artifact id to
     * its last segment collides for repos with same-named files in
     * different directories (root {@code config.json} vs
     * {@code 1_Pooling/config.json} in sentence-transformers models) - the
     * second silently overwrote the first and {@code bundle verify} failed.
     * Bundle names now preserve the repository-relative hierarchy (root
     * files keep their historic flat names, so existing bundles still
     * resolve) with numeric disambiguation for anything that still
     * collides after sanitizing.
     */
    private static String uniqueBundleName(ResolvedModel resolved, ResolvedArtifact artifact,
                                           java.util.Set<String> usedNames) {
        String id = artifact.artifact().id() == null ? "artifact" : artifact.artifact().id();
        String canonical = resolved == null || resolved.descriptor() == null
                ? null : resolved.descriptor().canonicalId();
        String relative = id;
        if (canonical != null && !canonical.isEmpty() && id.startsWith(canonical + "/")) {
            relative = id.substring(canonical.length() + 1);
        }
        String[] segments = relative.split("/");
        StringBuilder hierarchical = new StringBuilder();
        for (String segment : segments) {
            String cleaned = sanitizeSegment(segment);
            if (cleaned.isEmpty()) {
                cleaned = "artifact";
            }
            if (hierarchical.length() > 0) {
                hierarchical.append('/');
            }
            hierarchical.append(cleaned);
        }
        String candidate = hierarchical.length() == 0 ? "artifact" : hierarchical.toString();
        // audit 2026-10-10: case-sensitive dedup let A/x and a/x coexist in
        // the manifest and overwrite each other on case-insensitive
        // filesystems (macOS/Windows) - collide on the folded name
        if (!usedNames.contains(candidate.toLowerCase(Locale.ROOT))) {
            usedNames.add(candidate.toLowerCase(Locale.ROOT));
            return candidate;
        }
        int slash = candidate.lastIndexOf('/');
        String directory = slash < 0 ? "" : candidate.substring(0, slash + 1);
        String leaf = slash < 0 ? candidate : candidate.substring(slash + 1);
        int dot = leaf.lastIndexOf('.');
        String stem = dot <= 0 ? leaf : leaf.substring(0, dot);
        String extension = dot <= 0 ? "" : leaf.substring(dot);
        for (int counter = 2; ; counter++) {
            String disambiguated = directory + stem + "-" + counter + extension;
            if (!usedNames.contains(disambiguated.toLowerCase(Locale.ROOT))) {
                usedNames.add(disambiguated.toLowerCase(Locale.ROOT));
                return disambiguated;
            }
        }
    }

    private static String sanitizeSegment(String name) {
        String cleaned = name.replaceAll("[^A-Za-z0-9._-]", "_");
        while (cleaned.startsWith(".") || cleaned.startsWith("-")) {
            cleaned = "_" + cleaned.substring(1);
        }
        return cleaned.isEmpty() ? "artifact" : cleaned;
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0L;
        }
    }
}
