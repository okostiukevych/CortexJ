package io.cortexj.repository.http;

import io.cortexj.ErrorCode;
import io.cortexj.error.ModelResolutionException;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelDescriptor;
import io.cortexj.model.ModelReference;
import io.cortexj.model.TaskCapability;
import io.cortexj.model.ModelArtifact;
import io.cortexj.spi.repository.ModelRepositoryProvider;
import io.cortexj.spi.repository.RepositoryId;
import io.cortexj.spi.repository.ResolvedArtifact;
import io.cortexj.spi.repository.ResolvedModel;
import io.cortexj.spi.repository.ResolutionContext;
import io.cortexj.spi.support.SimpleResolvedArtifact;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class HttpRepositoryProvider implements ModelRepositoryProvider {

    private static final int CONNECT_TIMEOUT = 15_000;
    private static final int READ_TIMEOUT = 30_000;

    private final RepositoryId id = RepositoryId.of("http");

    @Override
    public RepositoryId id() {
        return id;
    }

    @Override
    public boolean supports(ModelReference reference) {
        String value = reference.value();
        return value != null
                && (value.startsWith("http://") || value.startsWith("https://"));
    }

    @Override
    public ResolvedModel resolve(ModelReference reference, ResolutionContext context) {
        String url = reference.value();
        if (context.isOffline()) {
            throw offline(url);
        }
        HeadInfo head = head(url);
        String fileName = fileNameOf(url);
        ArtifactFormat format = ArtifactFormat.fromFileName(fileName);
        Map<String, String> metadata = new LinkedHashMap<String, String>();
        metadata.put("sourceUrl", url);
        String quantization = io.cortexj.model.descriptor.DescriptorEnricher.quantizationFromFileName(fileName);
        if (quantization != null) {
            metadata.put("quantization", quantization);
        }
        ModelArtifact artifact = new ModelArtifact.Abstract(
                fileName, format, head.contentLength, null, metadata) {
        };
        ModelDescriptor descriptor = ModelDescriptor.builder(reference.value())
                .revision(head.revision)
                .artifact(artifact)
                .task(TaskCapability.TEXT_GENERATION)
                .trustNote("served over plain HTTP, publisher unverified")
                .build();
        List<ResolvedArtifact> artifacts = Collections.<ResolvedArtifact>singletonList(
                new SimpleResolvedArtifact(artifact, java.net.URI.create(url),
                        Collections.<String, String>emptyMap()));
        return new ResolvedModel(descriptor, head.revision, id, artifacts);
    }

    private static ModelResolutionException offline(String url) {
        ModelResolutionException failure = new ModelResolutionException(ErrorCode.MODEL_OFFLINE_UNRESOLVED,
                "HTTP model resolution is disabled in offline mode: " + url,
                "Pre-download the model with 'cortexj pull' or use a local repository bundle.");
        failure.detail("reference", url);
        return failure;
    }

    private static HeadInfo head(String url) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            try {
                connection.setRequestMethod("HEAD");
                connection.setConnectTimeout(CONNECT_TIMEOUT);
                connection.setReadTimeout(READ_TIMEOUT);
                int status = connection.getResponseCode();
                if (status != 200) {
                    ModelResolutionException notFound = new ModelResolutionException(ErrorCode.MODEL_NOT_FOUND,
                            "HTTP endpoint answered with status " + status + " for " + url,
                            "Check the model URL.");
                    notFound.detail("status", String.valueOf(status));
                    notFound.detail("reference", url);
                    throw notFound;
                }
                HeadInfo info = new HeadInfo();
                info.contentLength = connection.getContentLengthLong();
                String etag = connection.getHeaderField("ETag");
                if (etag != null) {
                    etag = etag.replace('"', ' ').trim();
                }
                String lastModified = connection.getHeaderField("Last-Modified");
                if (etag != null && !etag.isEmpty()) {
                    info.revision = "etag-" + etag;
                } else if (lastModified != null) {
                    info.revision = "mtime-" + Integer.toHexString(lastModified.hashCode());
                } else {
                    info.revision = "unversioned-" + Integer.toHexString(url.hashCode());
                }
                return info;
            } finally {
                connection.disconnect();
            }
        } catch (IOException e) {
            ModelResolutionException failure = new ModelResolutionException(ErrorCode.MODEL_RESOLUTION_FAILED,
                    "HTTP model resolution failed for " + url + ": " + e.getMessage(),
                    "Check the URL, network connectivity and TLS configuration.");
            failure.detail("reference", url);
            throw failure;
        }
    }

    private static String fileNameOf(String url) {
        String path = url;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        if (name.trim().isEmpty()) {
            name = "http-model";
        }
        return name;
    }

    private static final class HeadInfo {
        long contentLength;
        String revision;
    }
}
