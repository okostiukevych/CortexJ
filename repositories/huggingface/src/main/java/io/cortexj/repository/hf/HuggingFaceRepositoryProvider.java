package io.cortexj.repository.hf;

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
import io.cortexj.spi.repository.TokenSupplier;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class HuggingFaceRepositoryProvider implements ModelRepositoryProvider {

    public static final String DEFAULT_API_BASE = "https://huggingface.co";
    public static final String HF_TOKEN_ENV = "HF_TOKEN";
    public static final String CORTEXJ_HF_TOKEN_ENV = "CORTEXJ_HF_TOKEN";

    private final RepositoryId id = RepositoryId.of("huggingface");
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiBase;
    private final TokenSupplier defaultTokenSupplier;

    public HuggingFaceRepositoryProvider() {
        this(DEFAULT_API_BASE, null);
    }

    public HuggingFaceRepositoryProvider(String apiBase, TokenSupplier tokenSupplier) {
        this.apiBase = apiBase == null || apiBase.trim().isEmpty() ? DEFAULT_API_BASE : apiBase.trim();
        this.defaultTokenSupplier = tokenSupplier;
    }

    @Override
    public RepositoryId id() {
        return id;
    }

    @Override
    public boolean supports(ModelReference reference) {
        String value = reference.value();
        if (value.startsWith("hf:")) {
            return true;
        }
        if (value.startsWith("file:") || value.startsWith("/") || value.startsWith("./") || value.startsWith("~")) {
            return false;
        }
        return value.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+");
    }

    String modelSlug(ModelReference reference) {
        String value = reference.value();
        return value.startsWith("hf:") ? value.substring(3) : value;
    }

    @Override
    public ResolvedModel resolve(ModelReference reference, ResolutionContext context) {
        if (context.isOffline()) {
            ModelResolutionException failure = new ModelResolutionException(ErrorCode.MODEL_OFFLINE_UNRESOLVED,
                    "Cannot resolve " + reference.value() + " while offline mode is active.",
                    "Pre-download the model, use a local model bundle, or disable offline mode.");
            failure.detail("model", reference.value());
            failure.detail("repository", id.value());
            throw failure;
        }
        String slug = modelSlug(reference);
        JsonNode response = fetchModelInfo(slug, token(context));
        return parseModelInfo(slug, response);
    }

    public ResolvedModel parseModelInfo(String slug, JsonNode response) {
        String canonicalId = response.path("id").asText(slug);
        String revision = response.path("sha").asText(null);
        String pipelineTag = response.path("pipeline_tag").asText(null);
        String license = extractLicense(response);

        ModelDescriptor.Builder builder = ModelDescriptor.builder(canonicalId)
                .revision(revision)
                .license(license);
        boolean taskFound = false;
        for (JsonNode tag : response.path("tags")) {
            TaskCapability capability = TaskCapability.fromCommonTag(tag.asText());
            if (capability != null) {
                builder.task(capability);
                taskFound = true;
            }
        }
        TaskCapability fromPipeline = TaskCapability.fromCommonTag(pipelineTag);
        if (fromPipeline != null) {
            builder.task(fromPipeline);
            taskFound = true;
        }
        if (!taskFound) {
            builder.task(TaskCapability.TEXT_GENERATION);
        }

        List<ResolvedArtifact> artifacts = new ArrayList<ResolvedArtifact>();
        JsonNode siblings = response.path("siblings");
        for (Iterator<JsonNode> it = siblings.elements(); it.hasNext(); ) {
            JsonNode sibling = it.next();
            String fileName = sibling.path("rfilename").asText(null);
            if (fileName == null) {
                continue;
            }
            ArtifactFormat format = ArtifactFormat.fromFileName(fileName);
            if (format == ArtifactFormat.UNKNOWN) {
                continue;
            }
            long size = sibling.path("size").asLong(0L);
            if (size <= 0 && sibling.has("blob")) {
                size = sibling.path("blob").asLong(0L);
            }
            String quantization = io.cortexj.model.descriptor.DescriptorEnricher.quantizationFromFileName(fileName);
            Map<String, String> metadata = new LinkedHashMap<String, String>();
            if (quantization != null) {
                metadata.put("quantization", quantization);
            }
            String downloadUrl = apiBase + "/" + slug + "/resolve/" + (revision == null ? "main" : revision) + "/" + fileName;

            HfArtifact artifact = new HfArtifact(canonicalId + "/" + fileName, format, size, null, metadata);
            Map<String, String> headers = new LinkedHashMap<String, String>();
            artifacts.add(new HfResolvedArtifact(artifact, URI.create(downloadUrl), headers));
        }

        if (revision == null) {
            throw new ModelResolutionException(ErrorCode.MODEL_REVISION_UNRESOLVED,
                    "Hugging Face did not report an immutable revision for " + slug,
                    "Retry resolution or pin an explicit revision.");
        }
        return new ResolvedModel(builder.artifacts(toArtifactList(artifacts)).build(), revision, id, artifacts);
    }

    private JsonNode fetchModelInfo(String slug, String token) {
        HttpURLConnection connection = null;
        try {
            URL url = new URL(apiBase + "/api/models/" + slug + "?blobs=true");
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(30_000);
            connection.setRequestProperty("Accept", "application/json");
            if (token != null && !token.isEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }
            int status = connection.getResponseCode();
            if (status == 404) {
                ModelResolutionException notFound = new ModelResolutionException(ErrorCode.MODEL_NOT_FOUND,
                        "Hugging Face model not found: " + slug,
                        "Check the model id (expected 'owner/name').");
                notFound.detail("model", slug);
                throw notFound;
            }
            if (status != 200) {
                ModelResolutionException failed = new ModelResolutionException(ErrorCode.MODEL_RESOLUTION_FAILED,
                        "Hugging Face API returned status " + status + " for " + slug, "Retry later or check network access.");
                failed.detail("status", String.valueOf(status));
                throw failed;
            }
            try (InputStream input = connection.getInputStream()) {
                return mapper.readTree(input);
            }
        } catch (IOException e) {
            throw new ModelResolutionException(ErrorCode.MODEL_RESOLUTION_FAILED,
                    "Cannot reach Hugging Face for model " + slug + ": " + e.getMessage(),
                    "Verify network access to huggingface.co.", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String token(ResolutionContext context) {
        TokenSupplier supplier = context.tokenSupplier();
        if (supplier != null) {
            String token = supplier.token();
            if (token != null && !token.isEmpty()) {
                return token;
            }
        }
        String env = System.getenv(CORTEXJ_HF_TOKEN_ENV);
        if (env != null && !env.isEmpty()) {
            return env;
        }
        String generic = System.getenv(HF_TOKEN_ENV);
        if (generic != null && !generic.isEmpty()) {
            return generic;
        }
        String property = System.getProperty("cortexj.hf.token");
        return property == null || property.isEmpty() ? null : property;
    }

    private static String extractLicense(JsonNode response) {
        JsonNode licenseField = response.get("license");
        if (licenseField != null && licenseField.isTextual()) {
            return licenseField.asText();
        }
        for (JsonNode tag : response.path("tags")) {
            String text = tag.asText("");
            if (text.startsWith("license:")) {
                return text.substring("license:".length());
            }
        }
        return null;
    }

    private static List<ModelArtifact> toArtifactList(List<ResolvedArtifact> artifacts) {
        List<ModelArtifact> list = new ArrayList<ModelArtifact>();
        for (ResolvedArtifact artifact : artifacts) {
            list.add(artifact.artifact());
        }
        return list;
    }

    static final class HfArtifact extends ModelArtifact.Abstract {

        HfArtifact(String id, ArtifactFormat format, long sizeBytes, Checksum checksum, Map<String, String> metadata) {
            super(id, format, sizeBytes, checksum, metadata);
        }
    }

    static final class HfResolvedArtifact extends ResolvedArtifact.Abstract {

        HfResolvedArtifact(ModelArtifact artifact, URI location, Map<String, String> headers) {
            super(artifact, location, headers);
        }
    }
}
