package io.cortexj.cache;

import io.cortexj.ErrorCode;
import io.cortexj.error.ArtifactIntegrityException;
import io.cortexj.model.Checksum;
import io.cortexj.observability.PlatformEvent;
import io.cortexj.observability.PlatformEventListener;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

public final class CacheStorage {

    public static final long DEFAULT_MAX_SIZE_BYTES = 10L * 1024L * 1024L * 1024L;

    private final Path root;
    private final long maxSizeBytes;
    private PlatformEventListener eventListener = PlatformEventListener.NOOP;

    public CacheStorage(Path root) {
        this(root, DEFAULT_MAX_SIZE_BYTES);
    }

    public CacheStorage(Path root, long maxSizeBytes) {
        this.root = root;
        this.maxSizeBytes = maxSizeBytes;
    }

    public Path root() {
        return root;
    }

    public long maxSizeBytes() {
        return maxSizeBytes;
    }

    public void setEventListener(PlatformEventListener listener) {
        this.eventListener = listener == null ? PlatformEventListener.NOOP : listener;
    }

    public Path artifactPath(String repositoryId, String revision, String artifactId) {
        String safeRepository = sanitize(repositoryId);
        String safeRevision = sanitize(revision == null || revision.isEmpty() ? "unresolved" : revision);
        String safeArtifact = sanitize(artifactId);
        return root.resolve(safeRepository).resolve(safeRevision).resolve(safeArtifact);
    }

    public boolean contains(String repositoryId, String revision, String artifactId) {
        Path path = artifactPath(repositoryId, revision, artifactId);
        return Files.isRegularFile(path);
    }

    public Path get(String repositoryId, String revision, String artifactId, Checksum expectedChecksum) {
        Path path = artifactPath(repositoryId, revision, artifactId);
        if (!Files.isRegularFile(path)) {
            return null;
        }
        if (expectedChecksum != null) {
            try {
                if (!Checksums.verify(path, expectedChecksum.algorithm(), expectedChecksum.value())) {
                    Files.deleteIfExists(path);
                    ArtifactIntegrityException failure = new ArtifactIntegrityException(ErrorCode.ARTIFACT_CHECKSUM_MISMATCH,
                            "Cached artifact failed checksum validation and was removed: " + artifactId,
                            "The cache entry was corrupted; retry the download.");
                    failure.detail("artifact", artifactId);
                    failure.detail("expected", expectedChecksum.value());
                    throw failure;
                }
            } catch (IOException e) {
                throw new ArtifactIntegrityException(ErrorCode.ARTIFACT_CHECKSUM_MISMATCH,
                        "Cannot validate cached artifact " + artifactId + ": " + e.getMessage(), e);
            }
        }
        touch(path);
        return path;
    }

    public Path put(String repositoryId, String revision, String artifactId, Checksum expectedChecksum, InputStream content) throws IOException {
        Path target = artifactPath(repositoryId, revision, artifactId);
        Files.createDirectories(target.getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".part-" + Long.toHexString(System.nanoTime()));

        long bytesCopied = 0L;
        try (InputStream input = content) {
            bytesCopied = Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }

        if (expectedChecksum != null) {
            if (!Checksums.verify(temp, expectedChecksum.algorithm(), expectedChecksum.value())) {
                Files.deleteIfExists(temp);
                throw new ArtifactIntegrityException(ErrorCode.ARTIFACT_CHECKSUM_MISMATCH,
                        "Downloaded artifact failed checksum validation: " + artifactId,
                        "The source artifact is corrupted or was tampered with; verify the model repository.")
                        .detail("artifact", artifactId)
                        .detail("expected", expectedChecksum.value());
            }
        }

        if (maxSizeBytes > 0) {
            evictToFit(bytesCopied);
        }
        if (Files.exists(target)) {
            Files.delete(target);
        }
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailed) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        writeManifest(target, repositoryId, revision, artifactId, expectedChecksum, bytesCopied);
        eventListener.onEvent(new PlatformEvent(PlatformEvent.Type.DOWNLOAD_COMPLETED,
                PlatformEvent.Level.INFO,
                "artifact stored: " + artifactId,
                null, System.currentTimeMillis()));
        return target;
    }

    public List<CacheEntry> list() throws IOException {
        final List<CacheEntry> entries = new ArrayList<CacheEntry>();
        if (!Files.isDirectory(root)) {
            return entries;
        }
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String name = file.getFileName().toString();
                if (name.endsWith(".part-") || name.endsWith(".lock") || name.endsWith(".manifest")) {
                    return FileVisitResult.CONTINUE;
                }
                long size = attrs.size();
                long lastModified = attrs.lastModifiedTime().toMillis();
                entries.add(new CacheEntry(root.relativize(file).toString(), file, size, lastModified));
                return FileVisitResult.CONTINUE;
            }
        });
        return entries;
    }

    public long totalSizeBytes() throws IOException {
        long total = 0L;
        for (CacheEntry entry : list()) {
            total += entry.sizeBytes();
        }
        return total;
    }

    public long evictToFit(long incomingBytes) throws IOException {
        List<CacheEntry> entries = list();
        long total = 0L;
        for (CacheEntry entry : entries) {
            total += entry.sizeBytes();
        }
        if (total + incomingBytes <= maxSizeBytes) {
            return 0L;
        }
        entries.sort(new Comparator<CacheEntry>() {
            @Override
            public int compare(CacheEntry a, CacheEntry b) {
                return Long.compare(a.lastModifiedMillis(), b.lastModifiedMillis());
            }
        });
        long freed = 0L;
        for (CacheEntry entry : entries) {
            if (total + incomingBytes - freed <= maxSizeBytes) {
                break;
            }
            Files.deleteIfExists(entry.path());
            deleteSilently(entry.path().resolveSibling(entry.path().getFileName() + ".manifest"));
            freed += entry.sizeBytes();
        }
        return freed;
    }

    public long purge() throws IOException {
        long freed = 0L;
        for (CacheEntry entry : list()) {
            freed += entry.sizeBytes();
            Files.deleteIfExists(entry.path());
            deleteSilently(entry.path().resolveSibling(entry.path().getFileName() + ".manifest"));
        }
        return freed;
    }

    private static void touch(Path path) {
        try {
            Files.setLastModifiedTime(path, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
        } catch (Exception ignored) {
        }
    }

    private static void deleteSilently(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private void writeManifest(Path target, String repositoryId, String revision, String artifactId, Checksum checksum, long sizeBytes) {
        try {
            Properties manifest = new Properties();
            manifest.setProperty("repository", repositoryId == null ? "" : repositoryId);
            manifest.setProperty("revision", revision == null ? "" : revision);
            manifest.setProperty("artifact", artifactId == null ? "" : artifactId);
            manifest.setProperty("checksum", checksum == null ? "" : checksum.value());
            manifest.setProperty("sizeBytes", String.valueOf(sizeBytes));
            manifest.setProperty("storedAtMillis", String.valueOf(System.currentTimeMillis()));
            Path manifestPath = target.resolveSibling(target.getFileName() + ".manifest");
            try (java.io.OutputStream out = Files.newOutputStream(manifestPath)) {
                manifest.store(out, "cortexj cache manifest");
            }
        } catch (IOException ignored) {
        }
    }

    private static String sanitize(String value) {
        String cleaned = value == null ? "unknown" : value.trim().toLowerCase(Locale.ROOT);
        cleaned = cleaned.replaceAll("[^a-z0-9._-]", "_");
        while (cleaned.startsWith(".") || cleaned.startsWith("-")) {
            cleaned = "_" + cleaned.substring(1);
        }
        if (cleaned.isEmpty() || cleaned.equals("..")) {
            cleaned = "unknown";
        }
        return cleaned;
    }
}
