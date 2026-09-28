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

    private static final int LOCK_STRIPES = 16;

    private final Path root;
    private final long maxSizeBytes;
    private final Object[] keyLocks = new Object[LOCK_STRIPES];
    private final Object evictionLock = new Object();
    private volatile PlatformEventListener eventListener = PlatformEventListener.NOOP;

    public CacheStorage(Path root) {
        this(root, DEFAULT_MAX_SIZE_BYTES);
    }

    public CacheStorage(Path root, long maxSizeBytes) {
        this.root = root;
        this.maxSizeBytes = maxSizeBytes;
        for (int i = 0; i < LOCK_STRIPES; i++) {
            this.keyLocks[i] = new Object();
        }
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
        if (expectedChecksum != null && !manifestMatches(path, expectedChecksum)) {
            synchronized (keyLock(path)) {
                // re-check under the per-key lock: a concurrent put() may have
                // already replaced the artifact and its manifest since the
                // unlocked manifest read above - never verify-and-delete the
                // freshly written artifact because of that race (audit M-5a)
                if (Files.isRegularFile(path) && !manifestMatches(path, expectedChecksum)) {
                    verifyFullyAndRecord(path, repositoryId, revision, artifactId, expectedChecksum);
                }
            }
        }
        touch(path);
        return path;
    }

    private Object keyLock(Path artifact) {
        int index = (artifact.hashCode() & 0x7fffffff) % LOCK_STRIPES;
        return keyLocks[index];
    }

    /**
     * On-demand paranoid verification: always reads and hashes the full file,
     * regardless of the cache manifest. The normal get() path trusts the
     * manifest recorded at put() time (verify-once-then-trust, audit H-13) and
     * only re-hashes when the manifest does not match the expected checksum or
     * the file size drifted - this method is the explicit escape hatch.
     */
    public void verifyIntegrity(String repositoryId, String revision, String artifactId,
                                Checksum expectedChecksum) {
        Path path = artifactPath(repositoryId, revision, artifactId);
        if (!Files.isRegularFile(path)) {
            throw new ArtifactIntegrityException(ErrorCode.ARTIFACT_CHECKSUM_MISMATCH,
                    "Cached artifact does not exist: " + artifactId,
                    "Download the artifact before verifying it.");
        }
        verifyFullyAndRecord(path, repositoryId, revision, artifactId, expectedChecksum);
    }

    private void verifyFullyAndRecord(Path path, String repositoryId, String revision,
                                     String artifactId, Checksum expectedChecksum) {
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
        // verified now: record it so subsequent gets skip the full-file hash
        try {
            writeManifest(path, repositoryId, revision, artifactId, expectedChecksum, Files.size(path));
        } catch (IOException ignored) {
            // manifest is an optimization; a missing manifest just means re-verify next time
        }
    }

    /**
     * Verify-once-then-trust check (audit H-13): the artifact was hashed when it
     * was written (put() verifies before the atomic move) and the manifest
     * recorded the checksum and size. A cheap manifest+size comparison replaces
     * re-reading and re-hashing a 10 GB file on every cache hit.
     */
    private boolean manifestMatches(Path path, Checksum expectedChecksum) {
        Path manifestPath = path.resolveSibling(path.getFileName() + ".manifest");
        if (!Files.isRegularFile(manifestPath)) {
            return false;
        }
        Properties manifest = new Properties();
        try (java.io.InputStream in = Files.newInputStream(manifestPath)) {
            manifest.load(in);
        } catch (IOException e) {
            return false;
        }
        if (!expectedChecksum.value().equals(manifest.getProperty("checksum", ""))) {
            return false;
        }
        long recordedSize;
        try {
            recordedSize = Long.parseLong(manifest.getProperty("sizeBytes", "-1"));
        } catch (NumberFormatException e) {
            return false;
        }
        try {
            return recordedSize == Files.size(path);
        } catch (IOException e) {
            return false;
        }
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
        synchronized (keyLock(target)) {
            // atomic replace first: no delete->move window where concurrent
            // get()/contains() would report "not cached" (audit M-5b); the
            // delete+move ladder stays as the fallback for filesystems that
            // reject an atomic replace, and the manifest write happens under
            // the same lock so a concurrent get() can never pair the new
            // artifact with the stale manifest and verify-and-delete it
            boolean moved = false;
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                moved = true;
            } catch (IOException atomicReplaceUnsupported) {
                // fall through to the delete+move ladder
            }
            if (!moved) {
                if (Files.exists(target)) {
                    Files.delete(target);
                }
                try {
                    Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicFailed) {
                    Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            writeManifest(target, repositoryId, revision, artifactId, expectedChecksum, bytesCopied);
        }
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
                if (name.contains(".part-") || name.endsWith(".lock") || name.endsWith(".manifest")
                        || name.endsWith(".pinned")) {
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

    public boolean isPinned(String repositoryId, String revision, String artifactId) {
        return Files.isRegularFile(artifactPath(repositoryId, revision, artifactId)
                .resolveSibling(artifactPath(repositoryId, revision, artifactId).getFileName() + ".pinned"));
    }

    public void pin(String repositoryId, String revision, String artifactId) throws IOException {
        Path artifact = artifactPath(repositoryId, revision, artifactId);
        if (!Files.isRegularFile(artifact)) {
            throw new IOException("cannot pin a missing artifact: " + artifactId);
        }
        Files.createDirectories(artifact.getParent());
        Path marker = artifact.resolveSibling(artifact.getFileName() + ".pinned");
        if (!Files.exists(marker)) {
            Files.createFile(marker);
        }
    }

    public void unpin(String repositoryId, String revision, String artifactId) throws IOException {
        Path artifact = artifactPath(repositoryId, revision, artifactId);
        Files.deleteIfExists(artifact.resolveSibling(artifact.getFileName() + ".pinned"));
    }

    public long evictOlderThan(long cutoffEpochMillis) throws IOException {
        synchronized (evictionLock) {
            List<CacheEntry> entries = list();
            long freed = 0L;
            for (CacheEntry entry : entries) {
                if (entry.lastModifiedMillis() >= cutoffEpochMillis) {
                    continue;
                }
                if (Files.isRegularFile(entry.path().resolveSibling(entry.path().getFileName() + ".pinned"))) {
                    continue;
                }
                if (Files.deleteIfExists(entry.path())) {
                    deleteSilently(entry.path().resolveSibling(entry.path().getFileName() + ".manifest"));
                    freed += entry.sizeBytes();
                }
            }
            return freed;
        }
    }

    public long clear() throws IOException {
        List<CacheEntry> entries = list();
        long freed = 0L;
        for (CacheEntry entry : entries) {
            if (Files.deleteIfExists(entry.path())) {
                deleteSilently(entry.path().resolveSibling(entry.path().getFileName() + ".manifest"));
                deleteSilently(entry.path().resolveSibling(entry.path().getFileName() + ".pinned"));
                freed += entry.sizeBytes();
            }
        }
        return freed;
    }

    public long evictToFit(long incomingBytes) throws IOException {
        synchronized (evictionLock) {
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
                if (Files.isRegularFile(entry.path().resolveSibling(entry.path().getFileName() + ".pinned"))) {
                    continue;
                }
                if (Files.deleteIfExists(entry.path())) {
                    deleteSilently(entry.path().resolveSibling(entry.path().getFileName() + ".manifest"));
                    freed += entry.sizeBytes();
                }
            }
            return freed;
        }
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
