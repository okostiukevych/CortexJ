package io.cortexj.springai;

import io.cortexj.CortexJ;
import io.cortexj.inference.EmbeddingCapability;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelReference;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * Spring AI 2.x {@link EmbeddingModel} over the CortexJ embedding capability.
 *
 * <p>Honesty: model selection and vector {@code dimensions} from
 * {@link EmbeddingOptions} are not applied here - CortexJ's planner decides which
 * artifact and runtime executes the request (Spring options carry no planner
 * semantics). Only the instruction texts are mapped.</p>
 */
public final class CortexJEmbeddingModel implements EmbeddingModel, AutoCloseable {

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
        try {
            this.platform = platform;
            this.ownsPlatform = ownsPlatform;
            this.handle = platform.models().load(ModelReference.of(modelReference));
        } catch (Throwable t) {
            if (ownsPlatform && platform != null) {
                try {
                    platform.close();
                } catch (Throwable suppressed) {
                    t.addSuppressed(suppressed);
                }
            }
            throw t;
        }
    }

    @Override
    public EmbeddingResponse call(EmbeddingRequest request) {
        List<String> instructions = request.getInstructions() == null
                ? List.<String>of()
                : request.getInstructions();
        io.cortexj.inference.EmbeddingResponse response = embedding()
                .embed(io.cortexj.inference.EmbeddingRequest.texts(instructions));
        List<float[]> vectors = response.vectors();
        List<Embedding> embeddings = new ArrayList<Embedding>(vectors.size());
        for (int i = 0; i < vectors.size(); i++) {
            embeddings.add(new Embedding(vectors.get(i), i));
        }
        return new EmbeddingResponse(embeddings);
    }

    @Override
    public float[] embed(Document document) {
        return embed(orEmpty(document == null ? null : document.getText()));
    }

    @Override
    public float[] embed(String text) {
        return embedding().embed(io.cortexj.inference.EmbeddingRequest.text(orEmpty(text))).singleVector();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        return embedding().embed(io.cortexj.inference.EmbeddingRequest.texts(
                texts == null ? List.<String>of() : texts)).vectors();
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

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
