package io.cortexj.langchain4j;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.output.Response;
import io.cortexj.CortexJ;
import io.cortexj.inference.EmbeddingCapability;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelReference;

import java.util.ArrayList;
import java.util.List;

public final class CortexJEmbeddingModel implements dev.langchain4j.model.embedding.EmbeddingModel, AutoCloseable {

    private final CortexJ platform;
    private final ModelHandle handle;
    private final boolean ownsPlatform;

    public CortexJEmbeddingModel(String modelReference) {
        this(CortexJ.builder().build(), modelReference, true);
    }

    public CortexJEmbeddingModel(CortexJ platform, String modelReference) {
        this(platform, modelReference, false);
    }

    private CortexJEmbeddingModel(CortexJ platform, String modelReference, boolean ownsPlatform) {
        this.platform = platform;
        this.ownsPlatform = ownsPlatform;
        this.handle = platform.models().load(ModelReference.of(modelReference));
    }

    @Override
    public Response<List<Embedding>> embedAll(List<TextSegment> textSegments) {
        List<String> texts = new ArrayList<String>(textSegments.size());
        for (TextSegment segment : textSegments) {
            texts.add(segment.text());
        }
        List<float[]> vectors = embedding().embed(io.cortexj.inference.EmbeddingRequest.texts(texts)).vectors();
        List<Embedding> embeddings = new ArrayList<Embedding>(vectors.size());
        for (float[] vector : vectors) {
            embeddings.add(Embedding.from(vector));
        }
        return Response.from(embeddings);
    }

    @Override
    public void close() {
        handle.close();
        if (ownsPlatform) {
            platform.close();
        }
    }

    EmbeddingCapability embedding() {
        return handle.capability(EmbeddingCapability.class);
    }
}
