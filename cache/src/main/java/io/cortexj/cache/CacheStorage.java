package io.cortexj.cache;

import io.cortexj.ErrorCode;
import io.cortexj.error.ArtifactIntegrityException;
import io.cortexj.error.ArtifactResolutionException;
import io.cortexj.model.Checksum;
import io.cortexj.observability.PlatformEvent;
import io.cortexj.observability.PlatformEventListener;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
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
    private static final long EVICT_TEMP_GRACE_MILLIS = 3_600_000L;
    private static final int ATOMIC_REPLACE_RETRIES = 5;
    private static final long ATOMIC_REPLACE_BACKOFF_MILLIS = 20L;
    private static final long LARGE_WRITE_USABLE_SPACE_THRESHOLD_BYTES = 64L * 1024L * 1024L;
    private static final long LARGE_WRITE_USABLE_SPACE_MARGIN_BYTES = 64L * 1024L * 1024L;
    private static final java.util.concurrent.atomic.AtomicLong TEMP_SEQUENCE =
            new java.util.concurrent.atomic.AtomicLong();

    private static final int LOCK_STRIPES = 16;

    private final Path root;
    private final long maxSizeBytes;
    private final Object[] keyLocks = new Object[LOCK_STRIPES];
    private final Object evictionLock = new Object();
    private final java.util.Set<Path> activePartials =
            java.util.Collections.newSetFromMap(
                    new java.util.concurrent.ConcurrentHashMap<Path, Boolean>());
    private final java.util.concurrent.atomic.AtomicLong trackedBytes =
            new java.util.concurrent.atomic.AtomicLong();
    private volatile boolean trackedInitialized;
    private final java.util.concurrent.atomic.AtomicLong listCalls =
            new java.util.concurrent.atomic.AtomicLong();
    private volatile PlatformEventListener eventListener = PlatformEventListener.NOOP;
    private volatile java.util.function.LongSupplier usableSpaceProbe;

    public CacheStorage(Path root) {
        this(root, DEFAULT_MAX_SIZE_BYTES);
    }

    public CacheStorage(Path root, long maxSizeBytes) {
        this.root = root;
        this.maxSizeBytes = maxSizeBytes;
        this.usableSpaceProbe = defaultUsableSpaceProbe();
        for (int i = 0; i < LOCK_STRIPES; i++) {
            this.keyLocks[i] = new Object();
        }
    }

    private java.util.function.LongSupplier defaultUsableSpaceProbe() {
        return new java.util.function.LongSupplier() {
            @Override
            public long getAsLong() {
                try {
                    return Files.getFileStore(root).getUsableSpace();
                } catch (IOException e) {
                    return Long.MAX_VALUE;
                }
            }
        };
    }

    void setUsableSpaceProbeForTesting(java.util.function.LongSupplier probe) {
        if (probe != null) {
            this.usableSpaceProbe = probe;
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
        // audit #123: sanitize lowercases, so two ids differing only in case
        // map to one path and evict each other through verify-delete; a short
        // hash of the ORIGINAL id (as a path-segment prefix) keeps case
        // variants distinct
        String safeRepository = shortHash(repositoryId) + "-" + sanitize(repositoryId);
        String safeRevision = shortHash(revision == null || revision.isEmpty() ? "unresolved" : revision)
                + "-" + sanitize(revision == null || revision.isEmpty() ? "unresolved" : revision);
        String safeArtifact = shortHash(artifactId) + "-" + sanitize(artifactId);
        return root.resolve(safeRepository).resolve(safeRevision).resolve(safeArtifact);
    }

    private static String shortHash(String value) {
        if (value == null || value.isEmpty()) {
            return "0";
        }
        return Integer.toHexString(value.hashCode() & 0x7fffffff);
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
     * Registers an in-flight download partial so LRU/age eviction never
     * deletes an active download's resume file mid-flight (audit M-12).
     */
    public void registerActivePartial(Path partialFile) {
        activePartials.add(partialFile.toAbsolutePath().normalize());
    }

    public void unregisterActivePartial(Path partialFile) {
        activePartials.remove(partialFile.toAbsolutePath().normalize());
    }

    public boolean isActivePartial(Path partialFile) {
        return partialFile != null && activePartials.contains(partialFile.toAbsolutePath().normalize());
    }

    private boolean isActivePartial(CacheEntry entry) {
        return isActivePartial(entry.path());
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
        Path manifestPath = path.resolveSibling(fileNameOf(path) + ".manifest");
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

    /**
     * Audit 2026-10-10: takes ownership of an already-downloaded file
     * (typically the download manager's resume partial) by moving it into
     * the cache instead of copying it. A 70 GB artifact used to occupy
     * ~140 GB transiently (partial + copy); the move keeps peak disk at
     * ~1x. Same barriers as {@link #put}: size caps, checksum verification
     * before commit, eviction, usable-space check, atomic replace, manifest
     * and size-index credit. The source file is consumed (moved or, on
     * checksum mismatch, deleted).
     */
    public Path adopt(Path sourceFile, String repositoryId, String revision,
                      String artifactId, Checksum expectedChecksum) throws IOException {
        if (sourceFile == null || !Files.isRegularFile(sourceFile)) {
            throw new IOException("Cannot adopt a missing download file for " + artifactId);
        }
        long bytesCopied = Files.size(sourceFile);
        if (maxSizeBytes > 0 && trackedInitialized && trackedBytes.get() > maxSizeBytes) {
            evictToFit(0L);
        }
        Path target = artifactPath(repositoryId, revision, artifactId);
        Path parentDirectory = target.getParent();
        if (parentDirectory != null) {
            Files.createDirectories(parentDirectory);
        }
        try {
            if (maxSizeBytes > 0 && bytesCopied > maxSizeBytes) {
                throw new ArtifactResolutionException(ErrorCode.ARTIFACT_TOO_LARGE,
                        "Artifact " + artifactId + " is " + bytesCopied
                                + " bytes, exceeding the cache cap of " + maxSizeBytes + " bytes.",
                        "Raise the cache cap or choose a smaller variant.")
                        .detail("artifact", artifactId)
                        .detail("artifactBytes", String.valueOf(bytesCopied))
                        .detail("capBytes", String.valueOf(maxSizeBytes));
            }
            if (expectedChecksum != null) {
                if (!Checksums.verify(sourceFile, expectedChecksum.algorithm(), expectedChecksum.value())) {
                    Files.deleteIfExists(sourceFile);
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
            if (bytesCopied > LARGE_WRITE_USABLE_SPACE_THRESHOLD_BYTES) {
                long usableSpace = usableSpaceProbe.getAsLong();
                if (usableSpace < bytesCopied + LARGE_WRITE_USABLE_SPACE_MARGIN_BYTES) {
                    throw new ArtifactResolutionException(ErrorCode.ARTIFACT_TOO_LARGE,
                            "Not enough usable disk space to store artifact " + artifactId
                                    + ": " + usableSpace + " bytes free, need "
                                    + (bytesCopied + LARGE_WRITE_USABLE_SPACE_MARGIN_BYTES) + " bytes.",
                            "Free disk space or raise the cache cap.")
                            .detail("artifact", artifactId)
                            .detail("artifactBytes", String.valueOf(bytesCopied))
                            .detail("usableBytes", String.valueOf(usableSpace));
                }
            }
            synchronized (evictionLock) {
                synchronized (keyLock(target)) {
                    boolean moved = false;
                    try {
                        Files.move(sourceFile, target, StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING);
                        moved = true;
                    } catch (IOException atomicReplaceUnsupported) {
                        // fall through to the fallback ladder
                    }
                    if (!moved) {
                        for (int attempt = 0; attempt < ATOMIC_REPLACE_RETRIES && !moved; attempt++) {
                            try {
                                Thread.sleep(ATOMIC_REPLACE_BACKOFF_MILLIS);
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                            try {
                                Files.move(sourceFile, target, StandardCopyOption.ATOMIC_MOVE,
                                        StandardCopyOption.REPLACE_EXISTING);
                                moved = true;
                            } catch (IOException retryFailed) {
                                // scanner still holding the file; keep backing off
                            }
                        }
                    }
                    if (!moved) {
                        try {
                            Files.move(sourceFile, target, StandardCopyOption.ATOMIC_MOVE);
                            moved = true;
                        } catch (IOException atomicFailed) {
                            Files.move(sourceFile, target, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                    writeManifest(target, repositoryId, revision, artifactId, expectedChecksum, bytesCopied);
                }
                trackedBytes.addAndGet(bytesCopied);
            }
            eventListener.onEvent(new PlatformEvent(PlatformEvent.Type.DOWNLOAD_COMPLETED,
                        PlatformEvent.Level.INFO,
                        "artifact stored: " + artifactId,
                        null, System.currentTimeMillis()));
            return target;
        } catch (IOException | RuntimeException failure) {
            // the source partial stays for resume unless it failed checksum
            // (deleted above); never strand it on eviction/move failures
            throw failure;
        }
    }

    public Path put(String repositoryId, String revision, String artifactId, Checksum expectedChecksum, InputStream content) throws IOException {
        // audit GitHub #72: when the incremental index already says over-limit,
        // evict BEFORE the temp copy instead of doubling peak disk usage
        if (maxSizeBytes > 0 && trackedInitialized && trackedBytes.get() > maxSizeBytes) {
            evictToFit(0L);
        }
        Path target = artifactPath(repositoryId, revision, artifactId);
        Path parentDirectory = target.getParent();
        if (parentDirectory != null) {
            Files.createDirectories(parentDirectory);
        }
        Path temp = target.resolveSibling(fileNameOf(target) + ".part-"
                + Long.toHexString(System.nanoTime())
                + "-" + TEMP_SEQUENCE.incrementAndGet());

        long bytesCopied = 0L;
        try (InputStream input = content) {
            bytesCopied = Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            deleteSilently(temp);
            throw e;
        }
        try {
            // audit GitHub #72: an artifact larger than the whole cap can never
            // fit - reject before eviction instead of evicting every other entry
            if (maxSizeBytes > 0 && bytesCopied > maxSizeBytes) {
                throw new ArtifactResolutionException(ErrorCode.ARTIFACT_TOO_LARGE,
                        "Artifact " + artifactId + " is " + bytesCopied
                                + " bytes, exceeding the cache cap of " + maxSizeBytes + " bytes.",
                        "Raise the cache cap or choose a smaller variant.")
                        .detail("artifact", artifactId)
                        .detail("artifactBytes", String.valueOf(bytesCopied))
                        .detail("capBytes", String.valueOf(maxSizeBytes));
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
            if (bytesCopied > LARGE_WRITE_USABLE_SPACE_THRESHOLD_BYTES) {
                // audit GitHub #72: free-space pre-check before committing a
                // large artifact - eviction above already reclaimed what it
                // could, so this measures the real headroom left for the move
                long usableSpace = usableSpaceProbe.getAsLong();
                if (usableSpace < bytesCopied + LARGE_WRITE_USABLE_SPACE_MARGIN_BYTES) {
                    throw new ArtifactResolutionException(ErrorCode.ARTIFACT_TOO_LARGE,
                            "Not enough usable disk space to store artifact " + artifactId
                                    + ": " + usableSpace + " bytes free, need "
                                    + (bytesCopied + LARGE_WRITE_USABLE_SPACE_MARGIN_BYTES) + " bytes.",
                            "Free disk space or raise the cache cap; the temp copy was discarded.")
                            .detail("artifact", artifactId)
                            .detail("artifactBytes", String.valueOf(bytesCopied))
                            .detail("usableBytes", String.valueOf(usableSpace));
                }
            }
            // audit 2026-10-24 (macOS JDK 8 CI flake, CacheClearProtectionTest):
            // the scanner-contention ladder used to run under the per-key lock
            // only, so a clear()/purge() holding the eviction lock could walk
            // the tree between the pre-move and the size credit: the credit
            // then counted an entry the clear had already swept, or a second
            // walk counted a committed-but-not-yet-credited entry twice.
            // The move, the manifest and the credit are one critical section
            // now - lock order evictionLock -> keyLock stays what eviction
            // itself takes, and get() (keyLock only) still never deadlocks.
            boolean moved = false;
            synchronized (evictionLock) {
                synchronized (keyLock(target)) {
                    try {
                        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                        moved = true;
                    } catch (IOException atomicReplaceUnsupported) {
                        // fall through to the retry ladder
                    }
                    for (int attempt = 0; attempt < ATOMIC_REPLACE_RETRIES && !moved; attempt++) {
                        try {
                            Thread.sleep(ATOMIC_REPLACE_BACKOFF_MILLIS);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                        try {
                            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                            moved = true;
                        } catch (IOException retryFailed) {
                            // scanner still holding the file; keep backing off
                        }
                    }
                    if (!moved) {
                        // replace-on-rename without an explicit delete: rename(2)
                        // atomically replaces an existing target on POSIX, and the
                        // Windows atomic path is MoveFileEx(REPLACE_EXISTING) - the
                        // target is never left missing, unlike an explicit delete
                        // followed by a separate move, whose gap a concurrent get()
                        // observes when a scanner delays the rename (Windows CI
                        // flake, audit M-5b)
                        try {
                            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
                            moved = true;
                        } catch (IOException atomicFailed) {
                            // last resort: filesystems that reject the atomic
                            // replace entirely - non-atomic replace-on-move keeps
                            // the gap inside a single JDK move call at worst
                            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
                        }
                    }
                    writeManifest(target, repositoryId, revision, artifactId, expectedChecksum, bytesCopied);
                }
                // audit GitHub #71: the size credit lands under the eviction lock
                // so a concurrent clear()/purge() can never lose it via set(0)
                trackedBytes.addAndGet(bytesCopied);
            }
            eventListener.onEvent(new PlatformEvent(PlatformEvent.Type.DOWNLOAD_COMPLETED,
                        PlatformEvent.Level.INFO,
                        "artifact stored: " + artifactId,
                        null, System.currentTimeMillis()));
            return target;
        } catch (IOException | RuntimeException failure) {
            // audit M-9: no stranded temps on any failure path - a failed
            // eviction or move must not orphan a full-size .part- file
            deleteSilently(temp);
            throw failure;
        }
    }

    public List<CacheEntry> list() throws IOException {
        listCalls.incrementAndGet();
        final List<CacheEntry> entries = new ArrayList<CacheEntry>();
        if (!Files.isDirectory(root)) {
            return entries;
        }
        Files.walkFileTree(root, new java.nio.file.SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String name = fileNameOf(file);
                if (name.endsWith(".lock") || name.endsWith(".manifest")
                        || name.endsWith(".pinned")) {
                    return FileVisitResult.CONTINUE;
                }
                long size = attrs.size();
                long lastModified = attrs.lastModifiedTime().toMillis();
                entries.add(new CacheEntry(root.relativize(file).toString(), file, size, lastModified));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException error) {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException error) {
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
                .resolveSibling(fileNameOf(artifactPath(repositoryId, revision, artifactId)) + ".pinned"));
    }

    public void pin(String repositoryId, String revision, String artifactId) throws IOException {
        Path artifact = artifactPath(repositoryId, revision, artifactId);
        Path parentDirectory = artifact.getParent();
        if (parentDirectory != null) {
            Files.createDirectories(parentDirectory);
        }
        // audit 2026-10-10: the marker lands under the same per-key stripe
        // lock that eviction deletes under, so a pin can never slip between
        // the eviction scan and the delete (see deleteUnderKeyLock)
        synchronized (keyLock(artifact)) {
            if (!Files.isRegularFile(artifact)) {
                throw new IOException("cannot pin a missing artifact: " + artifactId);
            }
            Path marker = artifact.resolveSibling(fileNameOf(artifact) + ".pinned");
            try {
                Files.createFile(marker);
            } catch (FileAlreadyExistsException alreadyPinned) {
                // audit #94: two concurrent pins of one artifact - the marker
                // already being present is the success case, not a failure
            }
        }
    }

    public void unpin(String repositoryId, String revision, String artifactId) throws IOException {
        Path artifact = artifactPath(repositoryId, revision, artifactId);
        Files.deleteIfExists(artifact.resolveSibling(fileNameOf(artifact) + ".pinned"));
    }

    /**
     * audit #115: eviction deletes take the same per-key stripe lock that
     * get() holds during the full-hash verify, so a file can never vanish
     * mid-hash (lock order evictionLock -> keyLock is consistent with get(),
     * which takes only the keyLock - no deadlock).
     */
    private boolean deleteUnderKeyLock(CacheEntry entry) throws IOException {
        synchronized (keyLock(entry.path())) {
            // audit 2026-10-10: re-check the pin marker under the keyLock -
            // a concurrent pin() may have landed between the entry scan and
            // this delete, unlinking a live handle's artifact from under it
            if (Files.isRegularFile(entry.path().resolveSibling(
                    fileNameOf(entry.path()) + ".pinned"))) {
                return false;
            }
            if (!Files.deleteIfExists(entry.path())) {
                return false;
            }
            deleteSilently(entry.path().resolveSibling(fileNameOf(entry.path()) + ".manifest"));
            deleteSilently(entry.path().resolveSibling(fileNameOf(entry.path()) + ".lock"));
            return true;
        }
    }

    public long evictOlderThan(long cutoffEpochMillis) throws IOException {
        synchronized (evictionLock) {
            List<CacheEntry> entries = list();
            long freed = 0L;
            for (CacheEntry entry : entries) {
                if (entry.lastModifiedMillis() >= cutoffEpochMillis) {
                    continue;
                }
                if (isActivePartial(entry)) {
                    continue;
                }
                if (Files.isRegularFile(entry.path().resolveSibling(fileNameOf(entry.path()) + ".pinned"))) {
                    continue;
                }
                if (deleteUnderKeyLock(entry)) {
                    freed += entry.sizeBytes();
                }
            }
            trackedBytes.addAndGet(-freed);
            return freed;
        }
    }

    public long clear() throws IOException {
        synchronized (evictionLock) {
            return deleteEvictableAndRebaseline();
        }
    }

    public long evictToFit(long incomingBytes) throws IOException {
        synchronized (evictionLock) {
            // amortized eviction (audit M-16): the common case (durable content
            // plus the incoming artifact fits the advisory cap) is decided from
            // the incremental size index - no tree-wide walk per put; the walk
            // runs only when the index says over-limit and re-baselines it
            long tracked = trackedBytes.get();
            if (trackedInitialized && tracked + incomingBytes <= maxSizeBytes) {
                return 0L;
            }
            List<CacheEntry> entries = list();
            long now = System.currentTimeMillis();
            long total = 0L;
            for (CacheEntry entry : entries) {
                if (isTempEntry(entry) && now - entry.lastModifiedMillis() < EVICT_TEMP_GRACE_MILLIS) {
                    continue;
                }
                if (isActivePartial(entry)) {
                    continue;
                }
                total += entry.sizeBytes();
            }
            trackedBytes.set(total);
            trackedInitialized = true;
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
                if (isTempEntry(entry) && now - entry.lastModifiedMillis() < EVICT_TEMP_GRACE_MILLIS) {
                    continue;
                }
                if (isActivePartial(entry)) {
                    continue;
                }
                if (Files.isRegularFile(entry.path().resolveSibling(fileNameOf(entry.path()) + ".pinned"))) {
                    continue;
                }
                if (deleteUnderKeyLock(entry)) {
                    freed += entry.sizeBytes();
                }
            }
            trackedBytes.addAndGet(-freed);
            return freed;
        }
    }

    long listCallsForTesting() {
        return listCalls.get();
    }

    long trackedBytesForTesting() {
        return trackedBytes.get();
    }

    private static boolean isTempEntry(CacheEntry entry) {
        return fileNameOf(entry.path()).contains(".part-");
    }

    public long purge() throws IOException {
        synchronized (evictionLock) {
            return deleteEvictableAndRebaseline();
        }
    }

    private long deleteEvictableAndRebaseline() throws IOException {
        // audit GitHub #71: clear/purge run under the eviction lock and never
        // touch pinned artifacts or in-flight download partials; freed counts
        // only entries actually deleted and the size index is re-baselined
        // from the surviving tree instead of a blind set(0) that would erase
        // concurrent put() credits
        List<CacheEntry> entries = list();
        long now = System.currentTimeMillis();
        long freed = 0L;
        long tracked = 0L;
        for (CacheEntry entry : entries) {
            if (isActivePartial(entry)) {
                continue;
            }
            if (Files.isRegularFile(entry.path().resolveSibling(fileNameOf(entry.path()) + ".pinned"))) {
                tracked += entry.sizeBytes();
                continue;
            }
            if (deleteUnderKeyLock(entry)) {
                freed += entry.sizeBytes();
                continue;
            }
            if (!isTempEntry(entry) || now - entry.lastModifiedMillis() >= EVICT_TEMP_GRACE_MILLIS) {
                tracked += entry.sizeBytes();
            }
        }
        trackedBytes.set(tracked);
        trackedInitialized = true;
        return freed;
    }

    private static final long TOUCH_THROTTLE_MILLIS = 60_000L;

    private static void touch(Path path) {
        // audit 2026-10-07 (#146): the read path used to rewrite mtime on
        // every get() - metadata write traffic on hot models. Throttle to at
        // most one mtime write per minute; LRU granularity below a minute was
        // never meaningful for multi-GB artifacts anyway.
        try {
            long age = System.currentTimeMillis()
                    - Files.getLastModifiedTime(path).toMillis();
            if (age < TOUCH_THROTTLE_MILLIS) {
                return;
            }
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
            Path manifestPath = target.resolveSibling(fileNameOf(target) + ".manifest");
            try (java.io.OutputStream out = Files.newOutputStream(manifestPath)) {
                manifest.store(out, "cortexj cache manifest");
            }
        } catch (IOException ignored) {
        }
    }

    private static String fileNameOf(Path path) {
        java.nio.file.Path name = path == null ? null : path.getFileName();
        return name == null ? "" : name.toString();
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
