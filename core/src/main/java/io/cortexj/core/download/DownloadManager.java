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

    public DownloadManager(io.cortexj.core.EventBus events) {
        this.events = events;
    }

    public java.nio.file.Path download(ResolvedArtifact artifact,
                                       String repositoryId,
                                       String revision,
                                       CacheStorage cache,
                                       long maxArtifactBytes) {
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
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                java.nio.file.Path path = attemptDownload(artifact, repositoryId, revision, cache, declaredSize);
                events.publish(PlatformEvent.Type.DOWNLOAD_COMPLETED, PlatformEvent.Level.INFO,
                        "download completed: " + artifact.artifact().id(), attributes);
                return path;
            } catch (IOException e) {
                lastError = e;
                events.publish(PlatformEvent.Type.DOWNLOAD_FAILED, PlatformEvent.Level.WARNING,
                        "download attempt " + attempt + " failed for " + artifact.artifact().id() + ": " + e.getMessage(), attributes);
            }
        }
        ArtifactResolutionException failure = new ArtifactResolutionException(ErrorCode.ARTIFACT_RESOLUTION_FAILED,
                "Download failed after " + MAX_ATTEMPTS + " attempts: " + artifact.artifact().id(),
                "Check network access and retry; the artifact location was "
                        + artifact.location(), lastError);
        failure.detail("artifact", artifact.artifact().id());
        throw failure;
    }

    private java.nio.file.Path attemptDownload(ResolvedArtifact artifact,
                                               String repositoryId,
                                               String revision,
                                               CacheStorage cache,
                                               long declaredSize) throws IOException {
        URL url = openFollowingRedirects(artifact.location(), artifact.downloadHeaders());
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(CONNECT_TIMEOUT);
        connection.setReadTimeout(READ_TIMEOUT);
        for (Map.Entry<String, String> header : artifact.downloadHeaders().entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }
        try {
            int status = connection.getResponseCode();
            if (status != 200) {
                throw new IOException("HTTP " + status + " while downloading " + artifact.artifact().id());
            }
            try (InputStream input = connection.getInputStream()) {
                ProgressStream progress = new ProgressStream(input, artifact.artifact().id(), declaredSize);
                return cache.put(repositoryId, revision, artifact.artifact().id(),
                        artifact.artifact().checksum(), progress);
            }
        } finally {
            connection.disconnect();
        }
    }

    private URL openFollowingRedirects(URI location, Map<String, String> headers) throws IOException {
        URI current = location;
        for (int hop = 0; hop < MAX_REDIRECTS; hop++) {
            HttpURLConnection probe = (HttpURLConnection) current.toURL().openConnection();
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

    private final class ProgressStream extends InputStream {

        private final InputStream delegate;
        private final String artifactId;
        private final long declaredSize;
        private long totalRead;
        private long lastReportedAt;

        ProgressStream(InputStream delegate, String artifactId, long declaredSize) {
            this.delegate = delegate;
            this.artifactId = artifactId;
            this.declaredSize = declaredSize;
        }

        @Override
        public int read() throws IOException {
            int value = delegate.read();
            if (value >= 0) {
                totalRead++;
                report(false);
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = delegate.read(buffer, offset, length);
            if (read > 0) {
                totalRead += read;
                report(false);
            }
            return read;
        }

        @Override
        public void close() throws IOException {
            report(true);
            delegate.close();
        }

        private void report(boolean force) {
            if (!force && totalRead - lastReportedAt < PROGRESS_REPORT_BYTES) {
                return;
            }
            lastReportedAt = totalRead;
            Map<String, String> attributes = new LinkedHashMap<String, String>();
            attributes.put("artifact", artifactId);
            attributes.put("readBytes", String.valueOf(totalRead));
            if (declaredSize > 0) {
                attributes.put("percent", String.valueOf(totalRead * 100 / declaredSize));
            }
            events.publish(PlatformEvent.Type.DOWNLOAD_PROGRESS, PlatformEvent.Level.INFO,
                    "download progress: " + artifactId + " " + totalRead + " bytes", attributes);
        }
    }
}
