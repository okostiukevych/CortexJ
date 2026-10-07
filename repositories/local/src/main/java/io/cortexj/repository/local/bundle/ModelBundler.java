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
    private static final int CONNECT_TIMEOUT = 15_000;
    private static final int READ_TIMEOUT = 300_000;
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
        try {
            Files.createDirectories(outputDirectory);
        } catch (IOException e) {
            throw new ArtifactResolutionException(ErrorCode.ARTIFACT_RESOLUTION_FAILED,
                    "Cannot create bundle directory " + outputDirectory + ": " + e.getMessage(), e);
        }

        ArrayNode artifactEntries = mapper.createArrayNode();
        ObjectNode manifest = mapper.createObjectNode();
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

        for (ResolvedArtifact artifact : resolved.artifacts()) {
            String fileName = sanitizeFileName(artifact.artifact().id());
            Path target = outputDirectory.resolve(fileName);
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
        }
        manifest.set("artifacts", artifactEntries);

        Path manifestPath = outputDirectory.resolve(MANIFEST_FILE);
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(manifestPath.toFile(), manifest);
        } catch (IOException e) {
            throw new ArtifactResolutionException(ErrorCode.ARTIFACT_RESOLUTION_FAILED,
                    "Cannot write bundle manifest: " + e.getMessage(), e);
        }
        return outputDirectory;
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
        URL url = new URL(artifact.location().toString());
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        boolean moved = false;
        try {
            connection.setConnectTimeout(CONNECT_TIMEOUT);
            connection.setReadTimeout(READ_TIMEOUT);
            for (Map.Entry<String, String> header : artifact.downloadHeaders().entrySet()) {
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
            if (Files.exists(target)) {
                Files.delete(target);
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
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

    private static String sanitizeFileName(String id) {
        String name = id;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
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
