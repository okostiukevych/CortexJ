package io.cortexj.repository.local;

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

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class LocalRepositoryProvider implements ModelRepositoryProvider {

    public static final String MANIFEST_FILE = "cortexj-model.json";

    private final RepositoryId id = RepositoryId.of("local");
    private final ObjectMapper mapper = new ObjectMapper();
    private static final long CHECKSUM_INLINE_LIMIT_BYTES = 256L * 1024L * 1024L;
    private static final int CHECKSUM_CACHE_MAX_ENTRIES = 64;

    // audit #83: re-hashing up to 256MB inline on every resolve is not free -
    // memoize the digest keyed by (path, size, lastModified) in a small
    // access-order map
    private static final Map<String, String> CHECKSUM_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<String, String>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > CHECKSUM_CACHE_MAX_ENTRIES;
                }
            });

    @Override
    public RepositoryId id() {
        return id;
    }

    @Override
    public boolean supports(ModelReference reference) {
        String value = reference.value();
        if (value.startsWith("file:")
                || value.startsWith("/")
                || value.startsWith("./")
                || value.startsWith("~")) {
            return true;
        }
        // Windows drive-letter paths (C:\... or C:/...) and UNC paths (\\server\share)
        return value.length() >= 2 && Character.isLetter(value.charAt(0)) && value.charAt(1) == ':'
                || value.startsWith("\\\\");
    }

    @Override
    public ResolvedModel resolve(ModelReference reference, ResolutionContext context) {
        Path path = toPath(reference);
        if (!Files.exists(path)) {
            ModelResolutionException failure = new ModelResolutionException(ErrorCode.MODEL_NOT_FOUND,
                    "Local model not found: " + path,
                    "Check the file path in the model reference.");
            failure.detail("reference", reference.value());
            throw failure;
        }
        if (Files.isDirectory(path)) {
            return resolveDirectory(reference, path);
        }
        if (Files.isRegularFile(path)) {
            return resolveSingleFile(reference, path);
        }
        throw new ModelResolutionException(ErrorCode.MODEL_RESOLUTION_FAILED,
                "Local model path is neither a file nor a directory: " + path, "Verify the model path.");
    }

    private Path toPath(ModelReference reference) {
        String value = reference.value();
        if (value.startsWith("file:")) {
            try {
                URI uri = URI.create(value);
                if ("file".equalsIgnoreCase(uri.getScheme()) && !uri.isOpaque()) {
                    // file:///C:/... and file:/tmp/... carry the path in the URI itself;
                    // stripping the scheme would leave ///C:/... (invalid on Windows)
                    return Paths.get(uri).toAbsolutePath().normalize();
                }
            } catch (IllegalArgumentException legacyForm) {
                // fall through: old "file:C:\..." and "file:relative" forms
            }
            value = value.substring("file:".length());
        }
        if (value.startsWith("~")) {
            String home = System.getProperty("user.home", ".");
            value = home + value.substring(1);
        }
        return Paths.get(value).toAbsolutePath().normalize();
    }

    private ResolvedModel resolveDirectory(ModelReference reference, Path directory) {
        Path manifestPath = directory.resolve(MANIFEST_FILE);
        if (!Files.isRegularFile(manifestPath)) {
            ModelResolutionException failure = new ModelResolutionException(ErrorCode.MODEL_RESOLUTION_FAILED,
                    "Local model directory has no " + MANIFEST_FILE + ": " + directory,
                    "Create a manifest describing the model artifacts.");
            failure.detail("directory", directory.toString());
            throw failure;
        }
        try {
            JsonNode manifest;
            try (java.io.InputStream manifestInput = Files.newInputStream(manifestPath)) {
                manifest = mapper.readTree(manifestInput);
            }
            java.nio.file.Path directoryName = directory.getFileName();
            String canonicalId = manifest.path("id")
                    .asText(directoryName == null ? "local" : directoryName.toString());
            String revision = manifest.path("revision").asText("local-" + Files.getLastModifiedTime(manifestPath).toMillis());
            ModelDescriptor.Builder builder = ModelDescriptor.builder(canonicalId)
                    .revision(revision)
                    .architecture(manifest.path("architecture").asText(null))
                    .license(manifest.path("license").asText(null));
            if (manifest.path("contextLength").isInt()) {
                builder.contextLength(manifest.path("contextLength").asInt());
            }
            if (manifest.path("parameterCount").isLong()) {
                builder.parameterCount(manifest.path("parameterCount").asLong());
            }
            for (JsonNode task : manifest.path("tasks")) {
                TaskCapability capability = TaskCapability.fromCommonTag(task.asText());
                if (capability != null) {
                    builder.task(capability);
                }
            }

            List<ResolvedArtifact> artifacts = new ArrayList<ResolvedArtifact>();
            for (JsonNode artifactNode : manifest.path("artifacts")) {
                String file = artifactNode.path("file").asText(null);
                if (file == null || file.isEmpty()) {
                    continue;
                }
                Path artifactPath = resolveContained(directory, file);
                // audit #128: an unknown format string in the manifest (user
                // input) must fail typed, not with a bare IllegalArgumentException
                ArtifactFormat format;
                try {
                    format = artifactNode.has("format")
                            ? ArtifactFormat.valueOf(artifactNode.path("format").asText("UNKNOWN").toUpperCase(Locale.ROOT))
                            : ArtifactFormat.fromFileName(file);
                } catch (IllegalArgumentException unknownFormat) {
                    throw new ModelResolutionException(ErrorCode.MODEL_RESOLUTION_FAILED,
                            "artifact " + file + " declares an unknown format '"
                                    + artifactNode.path("format").asText("") + "'",
                            "Use a known ArtifactFormat name in cortexj-model.json "
                                    + "(GGUF, SAFETENSORS, ONNX, ...) or omit the field.");
                }
                long size = Files.isRegularFile(artifactPath) ? sizeOf(artifactPath) : artifactNode.path("size").asLong(0L);
                String sha256 = artifactNode.path("sha256").asText(null);
                if (sha256 == null && Files.isRegularFile(artifactPath) && size < CHECKSUM_INLINE_LIMIT_BYTES) {
                    sha256 = cachedSha256Hex(artifactPath);
                }
                Map<String, String> metadata = new LinkedHashMap<String, String>();
                if (artifactNode.has("quantization")) {
                    metadata.put("quantization", artifactNode.path("quantization").asText());
                }
                artifacts.add(new LocalResolvedArtifact(
                        new LocalArtifact(canonicalId + "/" + file, format, size,
                                sha256 == null ? null : Checksum.sha256(sha256), metadata),
                        artifactPath.toUri()));
            }
            return new ResolvedModel(builder.artifacts(toArtifactList(artifacts)).build(), revision, id, artifacts);
        } catch (IOException e) {
            throw new ModelResolutionException(ErrorCode.MODEL_RESOLUTION_FAILED,
                    "Cannot read local manifest " + manifestPath + ": " + e.getMessage(), e);
        }
    }

    private ResolvedModel resolveSingleFile(ModelReference reference, Path file) {
        java.nio.file.Path fileName = file.getFileName();
        String fileNameString = fileName == null ? "model" : fileName.toString();
        ArtifactFormat format = ArtifactFormat.fromFileName(fileNameString);
        long size = sizeOf(file);
        String sha256 = size < CHECKSUM_INLINE_LIMIT_BYTES ? cachedSha256Hex(file) : null;
        String canonicalId = fileNameString;
        String revision = "local-" + size + "-" + (sha256 == null ? Long.toHexString(file.toAbsolutePath().hashCode()) : sha256.substring(0, 12));

        ModelDescriptor.Builder builder = ModelDescriptor.builder(canonicalId)
                .revision(revision)
                .task(taskForFormat(format));
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        metadata.put("quantization", io.cortexj.model.descriptor.DescriptorEnricher.quantizationFromFileName(canonicalId));
        LocalArtifact artifact = new LocalArtifact(canonicalId, format, size,
                sha256 == null ? null : Checksum.sha256(sha256), metadata);
        List<ResolvedArtifact> artifacts = new ArrayList<ResolvedArtifact>(1);
        artifacts.add(new LocalResolvedArtifact(artifact, file.toUri()));
        return new ResolvedModel(builder.artifact(artifact).build(), revision, id, artifacts);
    }

    static TaskCapability taskForFormat(ArtifactFormat format) {
        switch (format) {
            case GGUF:
            case SAFETENSORS:
                return TaskCapability.TEXT_GENERATION;
            case ONNX:
                return TaskCapability.EMBEDDING;
            default:
                return TaskCapability.TEXT_GENERATION;
        }
    }

    private static List<ModelArtifact> toArtifactList(List<ResolvedArtifact> artifacts) {
        List<ModelArtifact> list = new ArrayList<ModelArtifact>();
        for (ResolvedArtifact artifact : artifacts) {
            list.add(artifact.artifact());
        }
        return list;
    }

    private static Path resolveContained(Path directory, String child) {
        Path resolved = directory.resolve(child).toAbsolutePath().normalize();
        Path normalizedRoot = directory.toAbsolutePath().normalize();
        if (!resolved.startsWith(normalizedRoot)) {
            ArtifactResolutionException failure = new ArtifactResolutionException(ErrorCode.ARTIFACT_PATH_TRAVERSAL,
                    "Artifact path escapes the model directory: " + child,
                    "Manifest artifact paths must stay inside the model directory.");
            failure.detail("path", child);
            throw failure;
        }
        try {
            // audit #73: normalize() does not resolve symlinks - containment
            // must hold for the real path or a manifest entry can link
            // outside the root and be read through the link
            Path realRoot = normalizedRoot.toRealPath();
            Path realResolved = resolved.toRealPath();
            if (!realResolved.startsWith(realRoot)) {
                ArtifactResolutionException escaped = new ArtifactResolutionException(ErrorCode.ARTIFACT_PATH_TRAVERSAL,
                        "Artifact path resolves outside the model directory through a symlink: " + child,
                        "Manifest artifact paths must stay inside the model directory "
                                + "and must not symlink outside it.");
                escaped.detail("path", child);
                throw escaped;
            }
        } catch (IOException e) {
            // missing artifact entry: fall back to the lexical check above
        }
        return resolved;
    }

    private static String cacheKey(Path path) {
        try {
            return path.toAbsolutePath() + "|" + Files.size(path) + "|"
                    + Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return null;
        }
    }

    private static String cachedSha256Hex(Path path) {
        String key = cacheKey(path);
        if (key == null) {
            return sha256Hex(path);
        }
        String cached = CHECKSUM_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        String computed = sha256Hex(path);
        if (computed != null) {
            CHECKSUM_CACHE.put(key, computed);
        }
        return computed;
    }

    private static long sizeOf(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0L;
        }
    }

    static String sha256Hex(Path path) {
        try {
            java.io.InputStream input = Files.newInputStream(path);
            try {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    digest.update(buffer, 0, read);
                }
                StringBuilder hex = new StringBuilder();
                for (byte b : digest.digest()) {
                    hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                    hex.append(Character.forDigit(b & 0xF, 16));
                }
                return hex.toString();
            } finally {
                input.close();
            }
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException e) {
            return null;
        }
    }

    private static final class LocalArtifact extends ModelArtifact.Abstract {

        LocalArtifact(String id, ArtifactFormat format, long sizeBytes, Checksum checksum, Map<String, String> metadata) {
            super(id, format, sizeBytes, checksum, metadata);
        }
    }

    private static final class LocalResolvedArtifact extends ResolvedArtifact.Abstract {

        LocalResolvedArtifact(ModelArtifact artifact, URI location) {
            super(artifact, location, Collections.<String, String>emptyMap());
        }
    }
}
