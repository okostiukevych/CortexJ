package io.cortexj.core.download;

import io.cortexj.ErrorCode;
import io.cortexj.cache.CacheStorage;
import io.cortexj.error.ArtifactResolutionException;
import io.cortexj.observability.PlatformEvent;
import io.cortexj.spi.repository.ResolvedArtifact;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.Map;

public final class DownloadManager {

    private static final int MAX_REDIRECTS = 5;
    private static final int MAX_ATTEMPTS = 3;
    private static final long PROGRESS_REPORT_BYTES = 64L * 1024L * 1024L;
    private static final long DEFAULT_MAX_ARTIFACT_BYTES = 100L * 1024L * 1024L * 1024L;
    private static final int CONNECT_TIMEOUT = 15_000;
    private static final int READ_TIMEOUT = 120_000;

    private final io.cortexj.core.EventBus events;
    private final java.net.Proxy proxy;

    public DownloadManager(io.cortexj.core.EventBus events) {
        this(events, null, null);
    }

    public DownloadManager(io.cortexj.core.EventBus events, String proxyHost, String proxyPort) {
        this.events = events;
        if (proxyHost != null && !proxyHost.trim().isEmpty()
                && proxyPort != null && !proxyPort.trim().isEmpty()) {
            this.proxy = new java.net.Proxy(java.net.Proxy.Type.HTTP,
                    new java.net.InetSocketAddress(proxyHost.trim(),
                            Integer.parseInt(proxyPort.trim())));
        } else {
            this.proxy = null;
        }
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

        Map<String, String> attributes = new LinkedHashMap<String, String>();
        attributes.put("artifact", artifact.artifact().id());
        attributes.put("bytes", String.valueOf(declaredSize));
        events.publish(PlatformEvent.Type.DOWNLOAD_STARTED, PlatformEvent.Level.INFO,
                "download started: " + artifact.artifact().id(), attributes);

        IOException lastError = null;
        long downloadStartedMillis = System.currentTimeMillis();
        java.nio.file.Path resumeFile = cache.root().resolve("downloads-partial")
                .resolve(safeName(repositoryId) + "~" + safeName(revision) + "~"
                        + safeName(artifact.artifact().id()) + ".part");
        boolean resumedOnce = java.nio.file.Files.exists(resumeFile);
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                if (cancelFlag != null && cancelFlag.get()) {
                    throw new java.io.IOException("download cancelled");
                }
                java.nio.file.Path path = attemptDownload(artifact, repositoryId, revision, cache,
                        declaredSize, resumeFile, cancelFlag);
                java.nio.file.Files.deleteIfExists(resumeFile);
                Map<String, String> completed = new LinkedHashMap<String, String>(attributes);
                completed.put("durationMillis",
                        String.valueOf(System.currentTimeMillis() - downloadStartedMillis));
                events.publish(PlatformEvent.Type.DOWNLOAD_COMPLETED, PlatformEvent.Level.INFO,
                        "download completed: " + artifact.artifact().id(), completed);
                return path;
            } catch (IOException e) {
                lastError = e;
                events.publish(PlatformEvent.Type.DOWNLOAD_FAILED, PlatformEvent.Level.WARNING,
                        "download attempt " + attempt + " failed for " + artifact.artifact().id() + ": " + e.getMessage(), attributes);
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
        ArtifactResolutionException failure = new ArtifactResolutionException(ErrorCode.ARTIFACT_RESOLUTION_FAILED,
                "Download failed after " + MAX_ATTEMPTS + " attempts: " + artifact.artifact().id(),
                "Check network access and retry; the artifact location was "
                        + artifact.location()
                        + (resumedOnce && java.nio.file.Files.exists(resumeFile)
                        ? ". Partial progress is kept and will be resumed (HTTP Range)."
                        : ""), lastError);
        failure.detail("artifact", artifact.artifact().id());
        throw failure;
    }

    private static String safeName(String value) {
        return (value == null ? "unknown" : value).replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private java.nio.file.Path attemptDownload(ResolvedArtifact artifact,
                                               String repositoryId,
                                               String revision,
                                               CacheStorage cache,
                                               long declaredSize,
                                               java.nio.file.Path resumeFile,
                                               java.util.concurrent.atomic.AtomicBoolean cancelFlag) throws IOException {
        URL url = openFollowingRedirects(artifact.location(), artifact.downloadHeaders());
        long alreadyDownloaded = 0L;
        java.nio.file.Path partialDirectory = resumeFile.getParent();
        java.nio.file.Files.createDirectories(partialDirectory);
        if (java.nio.file.Files.exists(resumeFile)) {
            alreadyDownloaded = java.nio.file.Files.size(resumeFile);
        } else {
            java.nio.file.Files.createFile(resumeFile);
        }

        HttpURLConnection connection = open(url);
        connection.setConnectTimeout(CONNECT_TIMEOUT);
        connection.setReadTimeout(READ_TIMEOUT);
        for (Map.Entry<String, String> header : artifact.downloadHeaders().entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }
        if (declaredSize > 0 && alreadyDownloaded >= declaredSize) {
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
                java.nio.file.Files.deleteIfExists(resumeFile);
                throw new IOException("HTTP " + status + " while downloading " + artifact.artifact().id());
            }
            boolean serverResumed = status == 206;
            try (InputStream input = connection.getInputStream()) {
                java.io.OutputStream output = new java.io.FileOutputStream(
                        resumeFile.toFile(), serverResumed);
                try {
                    copyCancellable(input, output, cancelFlag);
                } finally {
                    output.close();
                }
            }
            long written = java.nio.file.Files.size(resumeFile);
            if (declaredSize > 0 && written != declaredSize) {
                throw new IOException("truncated download: " + written + " of " + declaredSize
                        + " bytes (partial progress is kept for resume)");
            }
            return cache.put(repositoryId, revision, artifact.artifact().id(),
                    artifact.artifact().checksum(),
                    new java.io.FileInputStream(resumeFile.toFile()));
        } catch (java.io.IOException downloadFailure) {
            // keep the partial file for the next resume attempt
            throw downloadFailure;
        } catch (RuntimeException downloadFailure) {
            try {
                java.nio.file.Files.deleteIfExists(resumeFile);
            } catch (IOException ignored) {
                // best effort cleanup
            }
            throw downloadFailure;
        } finally {
            connection.disconnect();
        }
    }

    private void copyCancellable(InputStream input,
                                 java.io.OutputStream output,
                                 java.util.concurrent.atomic.AtomicBoolean cancelFlag) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (cancelFlag != null && cancelFlag.get()) {
                throw new IOException("download cancelled");
            }
            output.write(buffer, 0, read);
        }
    }

    private URL openFollowingRedirects(URI location, Map<String, String> headers) throws IOException {
        URI current = location;
        for (int hop = 0; hop < MAX_REDIRECTS; hop++) {
            HttpURLConnection probe = open(current.toURL());
            probe.setConnectTimeout(CONNECT_TIMEOUT);
            probe.setReadTimeout(READ_TIMEOUT);
            probe.setInstanceFollowRedirects(false);
            for (Map.Entry<String, String> header : headers.entrySet()) {
                probe.setRequestProperty(header.getKey(), header.getValue());
            }
            int status = probe.getResponseCode();
            if (status >= 300 && status < 400) {
                String target = probe.getHeaderField("Location");
                probe.disconnect();
                if (target == null) {
                    throw new IOException("Redirect without Location header from " + current);
                }
                current = current.resolve(target);
                continue;
            }
            if (status == 200) {
                probe.disconnect();
                return current.toURL();
            }
            probe.disconnect();
            throw new IOException("HTTP " + status + " while resolving " + location);
        }
        throw new IOException("Too many redirects while downloading " + location);
    }

}
