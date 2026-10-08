package io.cortexj.core.download;

import io.cortexj.ErrorCode;
import io.cortexj.cache.CacheStorage;
import io.cortexj.cache.Checksums;
import io.cortexj.error.ArtifactResolutionException;
import io.cortexj.observability.PlatformEvent;
import io.cortexj.spi.repository.ResolvedArtifact;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public final class DownloadManager {

    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_ATTEMPTS = 3;
    // audit #106: partials older than this are swept at download start - the
    // JVM that owned them is long gone
    private static final long STALE_PARTIAL_TTL_MILLIS = 7L * 24L * 60L * 60L * 1000L;
    private static final long PROGRESS_REPORT_BYTES = 64L * 1024L * 1024L;
    private static final long DEFAULT_MAX_ARTIFACT_BYTES = 100L * 1024L * 1024L * 1024L;
    private static final long BASE_BACKOFF_MILLIS = 500L;
    private static final long MAX_RETRY_AFTER_MILLIS = 30_000L;
    private static final int CONNECT_TIMEOUT = 15_000;
    private static final int READ_TIMEOUT = 120_000;

    private final io.cortexj.core.EventBus events;
    private final java.net.Proxy proxy;
    private final long baseBackoffMillis;

    public DownloadManager(io.cortexj.core.EventBus events) {
        this(events, null, null);
    }

    public DownloadManager(io.cortexj.core.EventBus events, String proxyHost, String proxyPort) {
        this(events, proxyHost, proxyPort, BASE_BACKOFF_MILLIS);
    }

    public DownloadManager(io.cortexj.core.EventBus events, String proxyHost, String proxyPort,
                           long baseBackoffMillis) {
        this.events = events;
        if (proxyHost != null && !proxyHost.trim().isEmpty()
                && proxyPort != null && !proxyPort.trim().isEmpty()) {
            this.proxy = new java.net.Proxy(java.net.Proxy.Type.HTTP,
                    new java.net.InetSocketAddress(proxyHost.trim(),
                            Integer.parseInt(proxyPort.trim())));
        } else {
            this.proxy = null;
        }
        this.baseBackoffMillis = baseBackoffMillis > 0 ? baseBackoffMillis : BASE_BACKOFF_MILLIS;
    }

    private HttpURLConnection open(URL url) throws IOException {
        return proxy == null
                ? (HttpURLConnection) url.openConnection()
                : (HttpURLConnection) url.openConnection(proxy);
    }

    public java.nio.file.Path download(ResolvedArtifact artifact,
                                       String repositoryId,
                                       String revision,
                                       CacheStorage cache,
                                       long maxArtifactBytes) {
        return download(artifact, repositoryId, revision, cache, maxArtifactBytes, null);
    }

    private static void requireUsableDiskSpace(java.nio.file.Path root, long requiredBytes,
                                               String artifactId) {
        // audit #121: without a pre-check the download fills $TMPDIR until the
        // 100GB artifact cap fires inside CacheStorage.put - by then the disk
        // may already be exhausted
        try {
            java.nio.file.Path partials = root.resolve("downloads-partial");
            java.nio.file.Path probe = java.nio.file.Files.isDirectory(partials) ? partials : root;
            long usable = java.nio.file.Files.getFileStore(probe).getUsableSpace();
            if (usable > 0 && usable < requiredBytes) {
                ArtifactResolutionException insufficient =
                        new ArtifactResolutionException(ErrorCode.ARTIFACT_TOO_LARGE,
                                "Artifact " + artifactId + " needs about " + requiredBytes
                                        + " bytes but only " + usable
                                        + " bytes are usable on the cache volume.",
                                "Free up disk space on the cache volume or point "
                                        + "the working directory at a larger disk.");
                insufficient.detail("artifact", artifactId);
                insufficient.detail("usableBytes", String.valueOf(usable));
                throw insufficient;
            }
        } catch (java.io.IOException probeFailure) {
            // a failed space probe must not block the download
        }
    }

    public java.nio.file.Path download(ResolvedArtifact artifact,
                                       String repositoryId,
                                       String revision,
                                       CacheStorage cache,
                                       long maxArtifactBytes,
                                       java.util.concurrent.atomic.AtomicBoolean cancelFlag) {
        if (artifact.location() == null) {
            throw new ArtifactResolutionException(ErrorCode.ARTIFACT_RESOLUTION_FAILED,
                    "Artifact " + artifact.artifact().id() + " has no downloadable location",
                    "Check the repository provider implementation.");
        }
        String scheme = artifact.location().getScheme();
        if (scheme.equals("file")) {
            return java.nio.file.Paths.get(artifact.location());
        }
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new ArtifactResolutionException(ErrorCode.ARTIFACT_RESOLUTION_FAILED,
                    "Unsupported artifact location scheme: " + scheme,
                    "Use a repository provider that exposes http(s) or file locations.");
        }
        // audit 2026-10-08: the initial location used to skip the SSRF guard
        // entirely (only redirect hops were validated) - an artifact from a
        // third-party provider pointing at an internal URL downloaded directly
        try {
            io.cortexj.spi.support.HttpGuards.requirePublicHttpUrl(artifact.location().toString());
        } catch (IllegalArgumentException blocked) {
            ArtifactResolutionException ssrf = new ArtifactResolutionException(
                    ErrorCode.ARTIFACT_RESOLUTION_FAILED,
                    "Artifact location blocked by the SSRF guard: " + blocked.getMessage(),
                    "Serve the artifact from a public URL, or set "
                            + io.cortexj.spi.support.HttpGuards.ALLOW_PRIVATE_HOSTS_PROPERTY
                            + "=true for intranet mirrors.");
            ssrf.detail("artifact", artifact.artifact().id());
            throw ssrf;
        }
        long declaredSize = artifact.artifact().sizeBytes();
        long limit = maxArtifactBytes > 0 ? maxArtifactBytes : DEFAULT_MAX_ARTIFACT_BYTES;
        if (declaredSize > limit) {
            ArtifactResolutionException tooLarge = new ArtifactResolutionException(ErrorCode.ARTIFACT_TOO_LARGE,
                    "Artifact " + artifact.artifact().id() + " declared size " + declaredSize
                            + " exceeds the configured maximum of " + limit + " bytes.",
                    "Raise the maximum artifact size or choose a smaller variant.");
            tooLarge.detail("artifact", artifact.artifact().id());
            tooLarge.detail("declaredBytes", String.valueOf(declaredSize));
            throw tooLarge;
        }
        requireUsableDiskSpace(cache.root(), declaredSize > 0 ? declaredSize : limit,
                artifact.artifact().id());

        Map<String, String> attributes = new LinkedHashMap<String, String>();
        attributes.put("artifact", artifact.artifact().id());
        attributes.put("bytes", String.valueOf(declaredSize));
        events.publish(PlatformEvent.Type.DOWNLOAD_STARTED, PlatformEvent.Level.INFO,
                "download started: " + artifact.artifact().id(), attributes);

        IOException lastError = null;
        long downloadStartedMillis = System.currentTimeMillis();
        java.nio.file.Path partialsDirectory = cache.root().resolve("downloads-partial");
        java.nio.file.Path resumeFile = partialsDirectory
                .resolve(partialFileName(repositoryId, revision, artifact.artifact().id()));
        sweepStalePartials(partialsDirectory);

        // audit #106: cross-process FileLock - two JVMs sharing CORTEXJ_HOME
        // used to append to the same .part concurrently, splicing bytes that
        // pass undetected on checksum-less HTTP sources. When another process
        // holds the lock, this download uses a process-unique partial without
        // resuming the foreign progress.
        java.nio.channels.FileChannel partialLockChannel = null;
        java.nio.channels.FileLock partialLock = null;
        try {
            if (!java.nio.file.Files.isDirectory(partialsDirectory)) {
                java.nio.file.Files.createDirectories(partialsDirectory);
            }
        } catch (java.io.IOException ignored) {
            // the lock open below fails too; the download falls back to a
            // process-unique partial
        }
        try {
            partialLockChannel = java.nio.channels.FileChannel.open(
                    resumeFile.resolveSibling(resumeFile.getFileName() + ".lock"),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.WRITE);
            partialLock = partialLockChannel.tryLock();
        } catch (java.io.IOException lockUnavailable) {
            // fall through to the process-unique partial below
        }
        java.nio.file.Path effectivePartial = resumeFile;
        if (partialLock == null) {
            // SpotBugs NP: getFileName() is null for root paths - resolveSibling
            // below already guarantees a file name, fail loudly otherwise
            java.nio.file.Path fileName = resumeFile.getFileName();
            if (fileName == null) {
                throw new ArtifactResolutionException(ErrorCode.ARTIFACT_RESOLUTION_FAILED,
                        "Cannot derive a partial-download name from " + resumeFile,
                        "Point the working directory at a regular cache location.");
            }
            effectivePartial = resumeFile.resolveSibling(
                    fileName.toString().replaceAll("\\.part$", "")
                            + "-" + java.util.UUID.randomUUID() + ".part");
        }
        boolean resumedOnce = java.nio.file.Files.exists(effectivePartial);
        // audit M-12: the in-flight partial is registered so LRU/age eviction
        // never deletes an active download's resume file mid-flight
        cache.registerActivePartial(effectivePartial);
        try {
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                try {
                    if (cancelFlag != null && cancelFlag.get()) {
                        throw new java.io.IOException("download cancelled");
                    }
                    java.nio.file.Path path = attemptDownload(artifact, repositoryId, revision, cache,
                            declaredSize, limit, effectivePartial, cancelFlag);
                    java.nio.file.Files.deleteIfExists(effectivePartial);
                    Map<String, String> completed = new LinkedHashMap<String, String>(attributes);
                    completed.put("durationMillis",
                            String.valueOf(System.currentTimeMillis() - downloadStartedMillis));
                    events.publish(PlatformEvent.Type.DOWNLOAD_COMPLETED, PlatformEvent.Level.INFO,
                            "download completed: " + artifact.artifact().id(), completed);
                    return path;
                } catch (IOException e) {
                    lastError = e;
                    // audit 2026-10-07 (#142): exception messages may embed the
                    // raw artifact location (presigned query, userinfo) - the
                    // event is log-shipped, so redact URL credentials first
                    events.publish(PlatformEvent.Type.DOWNLOAD_FAILED, PlatformEvent.Level.WARNING,
                            "download attempt " + attempt + " failed for " + artifact.artifact().id()
                                    + ": " + io.cortexj.spi.support.HttpGuards.redactUrlsInText(
                                    e.getMessage()), attributes);
                    if (isTerminal(e)) {
                        break;
                    }
                }
                if (attempt < MAX_ATTEMPTS) {
                    long backoff = backoffMillis(attempt, retryAfterMillisOf(lastError));
                    if (backoff > 0L) {
                        // audit 2026-10-08: slice the sleep so cancellation
                        // lands promptly instead of waiting out up to 30 s of
                        // backoff (Retry-After) after the user gave up
                        long deadline = System.currentTimeMillis() + backoff;
                        long remaining = backoff;
                        while (remaining > 0L) {
                            try {
                                Thread.sleep(Math.min(remaining, 100L));
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                lastError = new java.io.IOException("download cancelled");
                                break;
                            }
                            if (cancelFlag != null && cancelFlag.get()) {
                                lastError = new java.io.IOException("download cancelled");
                                break;
                            }
                            remaining = deadline - System.currentTimeMillis();
                        }
                    }
                }
            }
            if (lastError != null && lastError.getMessage() != null
                    && lastError.getMessage().contains("cancelled")) {
                ArtifactResolutionException cancelled = new ArtifactResolutionException(
                        ErrorCode.ARTIFACT_RESOLUTION_FAILED,
                        "Download of " + artifact.artifact().id() + " was cancelled",
                        "The partial download is kept for resuming on the next attempt.");
                cancelled.detail("artifact", artifact.artifact().id());
                throw cancelled;
            }
            if (lastError instanceof StreamSizeExceededException) {
                StreamSizeExceededException capped = (StreamSizeExceededException) lastError;
                ArtifactResolutionException tooLarge = new ArtifactResolutionException(
                        ErrorCode.ARTIFACT_TOO_LARGE,
                        "Artifact " + artifact.artifact().id() + " streamed " + capped.streamedBytes
                                + " bytes, exceeding the configured maximum of " + capped.limitBytes
                                + " bytes.",
                        "Raise the maximum artifact size or choose a smaller variant.");
                tooLarge.detail("artifact", artifact.artifact().id());
                tooLarge.detail("streamedBytes", String.valueOf(capped.streamedBytes));
                tooLarge.detail("limitBytes", String.valueOf(capped.limitBytes));
                throw tooLarge;
            }
            if (isDiskFull(lastError)) {
                ArtifactResolutionException diskFull = new ArtifactResolutionException(
                        ErrorCode.ARTIFACT_TOO_LARGE,
                        "No space left on device while downloading " + artifact.artifact().id(),
                        "Free disk space or raise the maximum artifact size; the partial download "
                                + "is kept for resuming.");
                diskFull.detail("artifact", artifact.artifact().id());
                throw diskFull;
            }
            if (lastError instanceof DownloadStatusException) {
                DownloadStatusException statusError = (DownloadStatusException) lastError;
                int status = statusError.status;
                if (status == 404) {
                    ArtifactResolutionException missing = new ArtifactResolutionException(
                            ErrorCode.ARTIFACT_MISSING,
                            "Artifact " + artifact.artifact().id() + " not found: HTTP 404 from "
                                    + redactedLocation(artifact),
                            "Check the repository coordinates and revision.");
                    missing.detail("artifact", artifact.artifact().id());
                    throw missing;
                }
                if (status == 401 || status == 403) {
                    ArtifactResolutionException denied = new ArtifactResolutionException(
                            ErrorCode.ARTIFACT_RESOLUTION_FAILED,
                            "HTTP " + status + " while downloading " + artifact.artifact().id()
                                    + ": access denied.",
                            "Check the repository credentials; the artifact location was "
                                    + redactedLocation(artifact) + ".");
                    denied.detail("artifact", artifact.artifact().id());
                    denied.detail("httpStatus", String.valueOf(status));
                    throw denied;
                }
                if (status == 416) {
                    ArtifactResolutionException badRange = new ArtifactResolutionException(
                            ErrorCode.ARTIFACT_RESOLUTION_FAILED,
                            "HTTP 416 while resuming " + artifact.artifact().id()
                                    + ": the saved partial is not usable.",
                            "The partial download was discarded; retry the download from scratch.");
                    badRange.detail("artifact", artifact.artifact().id());
                    throw badRange;
                }
                ArtifactResolutionException statusFailure = new ArtifactResolutionException(
                        ErrorCode.ARTIFACT_RESOLUTION_FAILED,
                        "HTTP " + status + " while downloading " + artifact.artifact().id(),
                        "Check network access and repository availability; the artifact location was "
                                + redactedLocation(artifact) + ".", lastError);
                statusFailure.detail("artifact", artifact.artifact().id());
                statusFailure.detail("httpStatus", String.valueOf(status));
                throw statusFailure;
            }
            ArtifactResolutionException failure = new ArtifactResolutionException(ErrorCode.ARTIFACT_RESOLUTION_FAILED,
                    "Download failed after " + MAX_ATTEMPTS + " attempts: " + artifact.artifact().id(),
                    "Check network access and retry; the artifact location was "
                            + redactedLocation(artifact)
                            + (resumedOnce && java.nio.file.Files.exists(effectivePartial)
                            ? ". Partial progress is kept and will be resumed (HTTP Range)."
                            : ""), lastError);
            failure.detail("artifact", artifact.artifact().id());
            throw failure;
        } finally {
            cache.unregisterActivePartial(effectivePartial);
            if (partialLock != null) {
                try {
                    partialLock.release();
                } catch (java.io.IOException ignored) {
                    // the OS releases the lock when the channel closes anyway
                }
            }
            if (partialLockChannel != null) {
                try {
                    partialLockChannel.close();
                } catch (java.io.IOException ignored) {
                    // the lock file itself stays (invisible to list()); deleting
                    // it under contention is racy
                }
            }
        }
    }

    /**
     * audit 2026-10-07 (#142): artifact locations are bearer credentials when
     * presigned (query token) or embed userinfo - error surfaces must carry
     * the redacted form only.
     */
    private static String redactedLocation(ResolvedArtifact artifact) {
        try {
            URI location = artifact.location() == null
                    ? null : new URI(artifact.location().toString());
            return io.cortexj.spi.support.HttpGuards.redactUrlForLogging(
                    location == null ? null : location.toString());
        } catch (Exception unparseable) {
            return "<unparseable-location>";
        }
    }

    /**
     * audit #106: stale partials (an interrupted download older than the TTL -
     * the JVM that owned it is long gone) are swept at download start; they
     * used to be cleaned only by cache pressure or a manual cache clean.
     */
    private static void sweepStalePartials(java.nio.file.Path partialsDirectory) {
        try {
            if (!java.nio.file.Files.isDirectory(partialsDirectory)) {
                return;
            }
            long cutoff = System.currentTimeMillis() - STALE_PARTIAL_TTL_MILLIS;
            try (java.nio.file.DirectoryStream<java.nio.file.Path> stream =
                         java.nio.file.Files.newDirectoryStream(partialsDirectory, "*.part")) {
                for (java.nio.file.Path candidate : stream) {
                    if (java.nio.file.Files.getLastModifiedTime(candidate).toMillis() < cutoff) {
                        java.nio.file.Files.deleteIfExists(candidate);
                    }
                }
            }
        } catch (java.io.IOException bestEffort) {
            // a failed sweep must not block the download
        }
    }

    public static String partialFileName(String repositoryId, String revision, String artifactId) {
        String raw = (repositoryId == null ? "unknown" : repositoryId)
                + "\n" + (revision == null ? "unknown" : revision)
                + "\n" + (artifactId == null ? "unknown" : artifactId);
        String readable = safeName(repositoryId) + "~" + safeName(revision) + "~" + safeName(artifactId);
        return readable + "-" + Checksums.sha256Hex(raw.getBytes(StandardCharsets.UTF_8)).substring(0, 16)
                + ".part";
    }

    private static String safeName(String value) {
        return (value == null ? "unknown" : value).replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private java.nio.file.Path attemptDownload(ResolvedArtifact artifact,
                                               String repositoryId,
                                               String revision,
                                               CacheStorage cache,
                                               long declaredSize,
                                               long limit,
                                               java.nio.file.Path resumeFile,
                                               java.util.concurrent.atomic.AtomicBoolean cancelFlag) throws IOException {
        RedirectTarget target = resolveRedirectTarget(artifact.location(), artifact.downloadHeaders());
        URL url = target.url;
        Map<String, String> effectiveHeaders = target.headers;
        long alreadyDownloaded = 0L;
        java.nio.file.Path partialDirectory = resumeFile.getParent();
        if (partialDirectory != null) {
            java.nio.file.Files.createDirectories(partialDirectory);
        }
        if (java.nio.file.Files.exists(resumeFile)) {
            alreadyDownloaded = java.nio.file.Files.size(resumeFile);
        } else {
            java.nio.file.Files.createFile(resumeFile);
        }

        HttpURLConnection connection = open(url);
        connection.setConnectTimeout(CONNECT_TIMEOUT);
        connection.setReadTimeout(READ_TIMEOUT);
        // audit #128: auto-follow stays off on the final connection too - if a
        // "resolved" URL answers a redirect, the JDK would follow it with the
        // current headers (its same-host logic is looser than sameOrigin);
        // credential stripping must be the only path that sets headers
        connection.setInstanceFollowRedirects(false);
        for (Map.Entry<String, String> header : effectiveHeaders.entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }
        if (alreadyDownloaded > 0L
                && ((declaredSize > 0 && alreadyDownloaded >= declaredSize) || alreadyDownloaded > limit)) {
            // stale/oversized partial from a different content version: start over
            java.nio.file.Files.deleteIfExists(resumeFile);
            java.nio.file.Files.createFile(resumeFile);
            alreadyDownloaded = 0L;
        }
        if (alreadyDownloaded > 0L) {
            connection.setRequestProperty("Range", "bytes=" + alreadyDownloaded + "-");
        }
        try {
            int status = connection.getResponseCode();
            if (status != 200 && status != 206) {
                throw statusFailure(status, connection, "while downloading " + artifact.artifact().id());
            }
            boolean append = false;
            if (status == 206) {
                append = validateContentRange(connection, artifact, declaredSize, alreadyDownloaded,
                        resumeFile);
            }
            try (InputStream input = connection.getInputStream()) {
                java.io.OutputStream output = new java.io.FileOutputStream(
                        resumeFile.toFile(), append);
                try {
                    copyCancellable(input, output, cancelFlag, limit,
                            append ? alreadyDownloaded : 0L);
                } finally {
                    output.close();
                }
            }
            long written = java.nio.file.Files.size(resumeFile);
            if (declaredSize > 0 && written != declaredSize) {
                throw new IOException("truncated download: " + written + " of " + declaredSize
                        + " bytes (partial progress is kept for resume)");
            }
            try (java.io.InputStream content = new java.io.FileInputStream(resumeFile.toFile())) {
                return cache.put(repositoryId, revision, artifact.artifact().id(),
                        artifact.artifact().checksum(), content);
            }
        } catch (java.io.IOException downloadFailure) {
            connection.disconnect();
            if (downloadFailure instanceof DownloadStatusException
                    && ((DownloadStatusException) downloadFailure).deletePartial) {
                try {
                    java.nio.file.Files.deleteIfExists(resumeFile);
                } catch (IOException ignored) {
                    // best effort cleanup
                }
            }
            // keep the partial file for the next resume attempt
            throw downloadFailure;
        } catch (RuntimeException downloadFailure) {
            connection.disconnect();
            try {
                java.nio.file.Files.deleteIfExists(resumeFile);
            } catch (IOException ignored) {
                // best effort cleanup
            }
            throw downloadFailure;
        }
    }

    private boolean validateContentRange(HttpURLConnection connection,
                                         ResolvedArtifact artifact,
                                         long declaredSize,
                                         long alreadyDownloaded,
                                         java.nio.file.Path resumeFile) throws IOException {
        long[] contentRange = parseContentRange(connection.getHeaderField("Content-Range"));
        boolean usable = contentRange != null
                && contentRange[0] == alreadyDownloaded
                && (declaredSize <= 0 || contentRange[2] < 0 || contentRange[2] == declaredSize);
        if (usable) {
            return alreadyDownloaded > 0L;
        }
        // audit #69: a proxy answering 206 from byte 0 (or a changed content
        // version) must not be appended onto the partial: discard it and fall
        // back to a fresh download on the next attempt
        java.nio.file.Files.write(resumeFile, new byte[0]);
        throw new IOException("Content-Range mismatch while resuming " + artifact.artifact().id()
                + "; the partial was discarded and the download restarts from scratch");
    }

    private static long[] parseContentRange(String header) {
        if (header == null) {
            return null;
        }
        String value = header.trim();
        if (!value.regionMatches(true, 0, "bytes", 0, "bytes".length())) {
            return null;
        }
        String remainder = value.substring("bytes".length()).trim();
        if (remainder.startsWith("=")) {
            remainder = remainder.substring(1).trim();
        }
        int slash = remainder.indexOf('/');
        int dash = remainder.indexOf('-');
        if (slash < 0 || dash < 0 || dash > slash) {
            return null;
        }
        try {
            long start = Long.parseLong(remainder.substring(0, dash).trim());
            long end = Long.parseLong(remainder.substring(dash + 1, slash).trim());
            String total = remainder.substring(slash + 1).trim();
            long totalBytes = "*".equals(total) ? -1L : Long.parseLong(total);
            return new long[]{start, end, totalBytes};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static IOException statusFailure(int status, HttpURLConnection connection, String context) {
        boolean retryable = status >= 500 || status == 429;
        return new DownloadStatusException(status, retryable, status == 416,
                retryable ? retryAfterMillis(connection) : -1L,
                "HTTP " + status + " " + context);
    }

    private static long retryAfterMillis(HttpURLConnection connection) {
        String header = connection.getHeaderField("Retry-After");
        if (header == null) {
            return -1L;
        }
        try {
            long seconds = Long.parseLong(header.trim());
            if (seconds <= 0L) {
                return 0L;
            }
            return Math.min(seconds * 1000L, MAX_RETRY_AFTER_MILLIS);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private static boolean isTerminal(IOException e) {
        if (e instanceof DownloadStatusException) {
            return !((DownloadStatusException) e).retryable;
        }
        if (isDiskFull(e)) {
            return true;
        }
        String message = e.getMessage();
        return message != null && message.contains("cancelled");
    }

    private static long retryAfterMillisOf(IOException e) {
        if (e instanceof DownloadStatusException) {
            return ((DownloadStatusException) e).retryAfterMillis;
        }
        return -1L;
    }

    private long backoffMillis(int attempt, long retryAfterMillis) {
        long backoff = baseBackoffMillis << (attempt - 1);
        if (retryAfterMillis >= 0L) {
            backoff = Math.max(backoff, Math.min(retryAfterMillis, MAX_RETRY_AFTER_MILLIS));
        }
        return backoff;
    }

    static boolean isDiskFull(IOException e) {
        IOException current = e;
        while (current != null) {
            if (current instanceof java.nio.file.FileSystemException) {
                return true;
            }
            String message = current.getMessage();
            if (message != null && message.contains("No space left on device")) {
                return true;
            }
            Throwable cause = current.getCause();
            current = cause instanceof IOException ? (IOException) cause : null;
        }
        return false;
    }

    private void copyCancellable(InputStream input,
                                 java.io.OutputStream output,
                                 java.util.concurrent.atomic.AtomicBoolean cancelFlag,
                                 long byteCap,
                                 long alreadyWritten) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        long written = alreadyWritten;
        int read;
        while ((read = input.read(buffer)) >= 0) {
            // audit 2026-10-08: cooperate with thread interruption too, not
            // just the cooperative flag - shutdownNow() must stop a stuck
            // copy instead of waiting out the 120 s socket timeout
            if (Thread.currentThread().isInterrupted()
                    || (cancelFlag != null && cancelFlag.get())) {
                throw new IOException("download cancelled");
            }
            if (written + read > byteCap) {
                throw new StreamSizeExceededException(written + read, byteCap);
            }
            output.write(buffer, 0, read);
            written += read;
        }
    }

    private RedirectTarget resolveRedirectTarget(URI location, Map<String, String> headers) throws IOException {
        URI current = location;
        Map<String, String> hopHeaders = headers;
        for (int hop = 0; hop < MAX_REDIRECTS; hop++) {
            HttpURLConnection probe = open(current.toURL());
            // audit L-7: the probe is disconnected on EVERY exit path - an
            // exception from getResponseCode() must not leave the socket
            // lingering in the keep-alive cache
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
                        throw new IOException("Redirect without Location header from " + current);
                    }
                    URI next = current.resolve(target);
                    String nextScheme = next.getScheme() == null ? "" : next.getScheme();
                    if (!nextScheme.equalsIgnoreCase("http") && !nextScheme.equalsIgnoreCase("https")) {
                        ArtifactResolutionException rejected = new ArtifactResolutionException(
                                ErrorCode.ARTIFACT_RESOLUTION_FAILED,
                                "Redirect target uses a non-http(s) scheme: "
                                        + io.cortexj.spi.support.HttpGuards.redactUrlForLogging(
                                        next.toString()),
                                "Redirect hops must stay http(s); the download was aborted "
                                        + "before any credential or data reached the target.");
                        rejected.detail("redirectTarget", io.cortexj.spi.support.HttpGuards
                                .redactUrlForLogging(next.toString()));
                        throw rejected;
                    }
                    // audit 2026-10-07 (#111): a redirect to an internal target
                    // is still fetched by the code below - validate the hop
                    // before any credential or data reaches it
                    try {
                        io.cortexj.spi.support.HttpGuards.requirePublicHttpUrl(next.toString());
                    } catch (IllegalArgumentException blocked) {
                        ArtifactResolutionException ssrf = new ArtifactResolutionException(
                                ErrorCode.ARTIFACT_RESOLUTION_FAILED,
                                "Redirect target blocked by the SSRF guard: " + blocked.getMessage(),
                                "Redirect hops must stay on public hosts; for intranet mirrors set "
                                        + io.cortexj.spi.support.HttpGuards.ALLOW_PRIVATE_HOSTS_PROPERTY
                                        + "=true.");
                        ssrf.detail("redirectTarget", io.cortexj.spi.support.HttpGuards
                                .redactUrlForLogging(next.toString()));
                        throw ssrf;
                    }
                    // audit M-18: credentials are dropped when the redirect leaves the
                    // original origin - a compromised CDN must not harvest the token;
                    // the stripped set also governs the final download connection
                    if (!sameOrigin(current, next)) {
                        hopHeaders = stripCredentials(hopHeaders);
                    }
                    current = next;
                    continue;
                }
                if (status == 200) {
                    return new RedirectTarget(current.toURL(), hopHeaders);
                }
                throw statusFailure(status, probe, "while resolving " + location);
            } finally {
                probe.disconnect();
            }
        }
        throw new IOException("Too many redirects while downloading " + location);
    }

    private static class DownloadStatusException extends java.io.IOException {
        final int status;
        final boolean retryable;
        final boolean deletePartial;
        final long retryAfterMillis;

        DownloadStatusException(int status, boolean retryable, boolean deletePartial,
                                long retryAfterMillis, String message) {
            super(message);
            this.status = status;
            this.retryable = retryable;
            this.deletePartial = deletePartial;
            this.retryAfterMillis = retryAfterMillis;
        }
    }

    private static final class StreamSizeExceededException extends DownloadStatusException {
        final long streamedBytes;
        final long limitBytes;

        StreamSizeExceededException(long streamedBytes, long limitBytes) {
            super(-1, false, true, -1L,
                    "download stream exceeded the maximum artifact size after " + streamedBytes
                            + " bytes (limit " + limitBytes + " bytes)");
            this.streamedBytes = streamedBytes;
            this.limitBytes = limitBytes;
        }
    }

    private static final class RedirectTarget {
        final URL url;
        final Map<String, String> headers;

        RedirectTarget(URL url, Map<String, String> headers) {
            this.url = url;
            this.headers = headers;
        }
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

}
