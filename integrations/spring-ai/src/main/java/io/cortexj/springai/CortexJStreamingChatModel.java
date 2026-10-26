package io.cortexj.springai;

import io.cortexj.CortexJ;
import io.cortexj.inference.TextGenerationCapability;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelReference;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

/**
 * Streaming-only Spring AI model over a loaded CortexJ model. A thin delegate to
 * the same bridge {@link CortexJChatModel#stream(Prompt)} uses - kept for callers
 * that only instantiate a streaming model (e.g. a service exposed purely as
 * {@code StreamingChatModel}).
 */
public final class CortexJStreamingChatModel implements StreamingChatModel, AutoCloseable {

    private final CortexJ platform;
    private final ModelHandle handle;
    private final boolean ownsPlatform;

    public CortexJStreamingChatModel(String modelReference) {
        this(CortexJ.builder().build(), modelReference, true);
    }

    public CortexJStreamingChatModel(CortexJ platform, String modelReference) {
        this(platform, modelReference, false);
    }

    private CortexJStreamingChatModel(CortexJ platform, String modelReference, boolean ownsPlatform) {
        try {
            SpringAiSupport.requireChatAdapterSupport();
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
    public Flux<ChatResponse> stream(Prompt prompt) {
        if (CortexJToolLoop.hasToolCallbacks(prompt)) {
            // honest limit: with tools the loop runs inline (Spring semantics for
            // internal tool execution) and a single final chunk is streamed
            return Flux.just(CortexJToolLoop.run(generator(), modelId(), prompt));
        }
        return CortexJStreamBridge.stream(generator(), modelId(), prompt);
    }

    public String getModelName() {
        return modelId();
    }

    @Override
    public void close() {
        handle.close();
        if (ownsPlatform) {
            platform.close();
        }
    }

    TextGenerationCapability generator() {
        return handle.capability(TextGenerationCapability.class);
    }

    private String modelId() {
        return handle.descriptor().canonicalId();
    }
}
