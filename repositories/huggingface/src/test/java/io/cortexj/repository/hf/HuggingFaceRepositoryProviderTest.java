package io.cortexj.repository.hf;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.cortexj.error.ModelResolutionException;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelReference;
import io.cortexj.model.TaskCapability;
import io.cortexj.spi.repository.ResolvedModel;
import io.cortexj.spi.repository.ResolutionContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HuggingFaceRepositoryProviderTest {

    private final HuggingFaceRepositoryProvider provider = new HuggingFaceRepositoryProvider();
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String MODEL_JSON = "{"
            + "\"id\":\"Qwen/Qwen3-8B\","
            + "\"sha\":\"7f62abc123\","
            + "\"pipeline_tag\":\"text-generation\","
            + "\"tags\":[\"text-generation\",\"transformers\",\"license:apache-2.0\"],"
            + "\"siblings\":["
            + "{\"rfilename\":\"model.safetensors\",\"size\":16000000000},"
            + "{\"rfilename\":\"qwen3-8b-q6_k.gguf\",\"size\":6600000000,"
            + "\"blob\":{\"sha256\":\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\",\"size\":6600000000}},"
            + "{\"rfilename\":\"model.onnx\",\"size\":15000000000},"
            + "{\"rfilename\":\"tokenizer.json\",\"size\":11000000},"
            + "{\"rfilename\":\"README.md\"}"
            + "]}";

    @Test
    void supportsRecognizesHfReferences() {
        assertTrue(provider.supports(ModelReference.of("hf:Qwen/Qwen3-8B")));
        assertTrue(provider.supports(ModelReference.of("Qwen/Qwen3-8B")));
        assertFalse(provider.supports(ModelReference.of("file:/tmp/model.gguf")));
        assertFalse(provider.supports(ModelReference.of("/tmp/model.gguf")));
        assertFalse(provider.supports(ModelReference.of("plain-name")));
    }

    @Test
    void parsesModelInfoIntoResolvedModel() throws Exception {
        ResolvedModel model = provider.parseModelInfo("Qwen/Qwen3-8B", mapper.readTree(MODEL_JSON));

        assertEquals("Qwen/Qwen3-8B", model.descriptor().canonicalId());
        assertEquals("7f62abc123", model.revision());
        assertTrue(model.hasRevision());
        assertEquals("apache-2.0", model.descriptor().license());
        assertTrue(model.descriptor().supports(TaskCapability.TEXT_GENERATION));

        assertEquals(4, model.artifacts().size());
        assertEquals(ArtifactFormat.SAFETENSORS, model.artifacts().get(0).artifact().format());
        assertEquals(ArtifactFormat.GGUF, model.artifacts().get(1).artifact().format());
        assertEquals(ArtifactFormat.ONNX, model.artifacts().get(2).artifact().format());
        assertEquals(ArtifactFormat.TOKENIZER, model.artifacts().get(3).artifact().format());

        assertEquals(6600000000L, model.artifacts().get(1).artifact().sizeBytes());
        assertEquals("q6_k", model.artifacts().get(1).artifact().metadata().get("quantization"));
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                model.artifacts().get(1).artifact().checksum().value(),
                "the LFS sha256 from the blobs response must be carried into the artifact checksum");
        assertEquals("sha-256", model.artifacts().get(1).artifact().checksum().algorithm());
        assertEquals("sha256-verified", model.artifacts().get(1).artifact().metadata().get("integrity"));
        assertTrue(model.artifacts().get(1).location().toString()
                .endsWith("/Qwen/Qwen3-8B/resolve/7f62abc123/qwen3-8b-q6_k.gguf"));
    }

    @Test
    void siblingWithoutBlobIsMarkedUnverified() throws Exception {
        String json = "{\"id\":\"x/y\",\"sha\":\"abc\",\"siblings\":["
                + "{\"rfilename\":\"model.gguf\",\"size\":1234}]}";
        ResolvedModel model = provider.parseModelInfo("x/y", mapper.readTree(json));
        io.cortexj.model.ModelArtifact artifact = model.artifacts().get(0).artifact();
        assertEquals(null, artifact.checksum(),
                "no blob info means no checksum - but the gap must be visible");
        assertEquals("unverified", artifact.metadata().get("integrity"));
    }

    @Test
    void checksumLessArtifactCarriesTrustNote() throws Exception {
        String json = "{\"id\":\"x/y\",\"sha\":\"abc\",\"gated\":false,\"siblings\":["
                + "{\"rfilename\":\"model.gguf\",\"size\":1234}]}";
        ResolvedModel model = provider.parseModelInfo("x/y", mapper.readTree(json));
        String trustNote = model.descriptor().trustNote();
        assertTrue(trustNote != null && trustNote.contains("sha256 not verified against the Hugging Face LFS blob"),
                "a checksum-less artifact must carry a trust note explaining why it is untrusted, got: " + trustNote);
    }

    @Test
    void lfsVerifiedGatedModelCarriesNoTrustNote() throws Exception {
        String json = "{\"id\":\"x/y\",\"sha\":\"abc\",\"gated\":true,\"siblings\":["
                + "{\"rfilename\":\"model.gguf\",\"size\":1234,"
                + "\"blob\":{\"sha256\":\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\",\"size\":1234}}]}";
        ResolvedModel model = provider.parseModelInfo("x/y", mapper.readTree(json));
        assertEquals(null, model.descriptor().trustNote(),
                "an LFS-verified gated model must not carry a trust note");
    }

    @Test
    void lfsVerifiedNonGatedModelNotesCommunityProvenance() throws Exception {
        String json = "{\"id\":\"x/y\",\"sha\":\"abc\",\"siblings\":["
                + "{\"rfilename\":\"model.gguf\",\"size\":1234,"
                + "\"blob\":{\"sha256\":\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\",\"size\":1234}}]}";
        ResolvedModel model = provider.parseModelInfo("x/y", mapper.readTree(json));
        assertEquals("community upload without verified provenance", model.descriptor().trustNote(),
                "an LFS-verified but non-gated model must still be marked as unverified provenance");
    }

    @Test
    void gatedStringVariantIsTreatedAsGated() throws Exception {
        String json = "{\"id\":\"x/y\",\"sha\":\"abc\",\"gated\":\"manual\",\"siblings\":["
                + "{\"rfilename\":\"model.gguf\",\"size\":1234,"
                + "\"blob\":{\"sha256\":\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\",\"size\":1234}}]}";
        ResolvedModel model = provider.parseModelInfo("x/y", mapper.readTree(json));
        assertEquals(null, model.descriptor().trustNote(),
                "gated:'manual' is a provenance signal and must suppress the community note");
    }

    @Test
    void offlineModeYieldsClearError() {
        ResolutionContext offline = ResolutionContext.create(true, null, null, null);
        ModelResolutionException exception = assertThrows(ModelResolutionException.class,
                () -> provider.resolve(ModelReference.ofHuggingFace("Qwen/Qwen3-8B"), offline));
        assertEquals("CORTEXJ-MODEL-1003", exception.code());
        assertTrue(exception.remediation().size() >= 1);
    }

    @Test
    void missingRevisionFailsResolution() throws Exception {
        String json = "{\"id\":\"x/y\",\"siblings\":[{\"rfilename\":\"model.gguf\"}]}";
        ModelResolutionException exception = assertThrows(ModelResolutionException.class,
                () -> provider.parseModelInfo("x/y", mapper.readTree(json)));
        assertEquals("CORTEXJ-MODEL-1004", exception.code());
    }

    @Test
    void rejectsPathTraversalInSiblingFilenameAndRevision() throws Exception {
        String badFileJson = "{\"id\":\"x/y\",\"sha\":\"abc\",\"siblings\":[{\"rfilename\":\"../escape.gguf\"}]}";
        assertThrows(ModelResolutionException.class,
                () -> provider.parseModelInfo("x/y", mapper.readTree(badFileJson)));

        String badShaJson = "{\"id\":\"x/y\",\"sha\":\"../abc\",\"siblings\":[{\"rfilename\":\"model.gguf\"}]}";
        assertThrows(ModelResolutionException.class,
                () -> provider.parseModelInfo("x/y", mapper.readTree(badShaJson)));
    }

    @Test
    void encodesSpecialCharactersInArtifactDownloadUri() throws Exception {
        String json = "{\"id\":\"x/y\",\"sha\":\"abc123\",\"siblings\":["
                + "{\"rfilename\":\"sub dir/model #1.gguf\",\"size\":10}]}";
        ResolvedModel model = provider.parseModelInfo("x/y", mapper.readTree(json));
        assertEquals(1, model.artifacts().size());
        String uri = model.artifacts().get(0).location().toString();
        assertTrue(uri.endsWith("/x/y/resolve/abc123/sub%20dir/model%20%231.gguf"),
                "special characters in rfilename must be percent-encoded in download URI, got: " + uri);
    }
}
