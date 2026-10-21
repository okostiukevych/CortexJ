package io.cortexj.springai;

import io.cortexj.CortexJ;
import io.cortexj.inference.GenerationResponse;
import io.cortexj.inference.TextGenerationCapability;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelReference;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Spring AI 2.x {@link ChatModel} backed by a loaded CortexJ model:
 * {@link #call(Prompt)} executes a blocking generation, {@link #stream(Prompt)}
 * streams tokens through a {@code Flux}. Both map Spring AI messages
 * (system/user/assistant/tool) and {@link org.springframework.ai.chat.prompt.ChatOptions}
 * into the CortexJ generation request, and surface token usage and finish reason
 * in the response metadata.
 *
 * <p>Tool calling is NOT executed by the runtime: the adapter never emits tool-call
 * requests, so Spring AI's {@code ToolCallingAdvisor} has nothing to act on. Tool-capable
 * usage requires the runtime API to expose tool calls first.</p>
 */
public final class CortexJChatModel implements ChatModel, AutoCloseable {

    private final CortexJ platform;
    private final ModelHandle handle;
    private final boolean ownsPlatform;

    public CortexJChatModel(String modelReference) {
        this(CortexJ.builder().build(), modelReference, true);
    }

    public CortexJChatModel(CortexJ platform, String modelReference) {
        this(platform, modelReference, false);
    }

    private CortexJChatModel(CortexJ platform, String modelReference, boolean ownsPlatform) {
        SpringAiSupport.requireChatAdapterSupport();
        this.platform = platform;
        this.ownsPlatform = ownsPlatform;
        this.handle = platform.models().load(ModelReference.of(modelReference));
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        if (CortexJToolLoop.hasToolCallbacks(prompt)) {
            return CortexJToolLoop.run(generator(), modelId(), prompt);
        }
        GenerationResponse response = generator().generate(CortexJMapper.generationRequest(prompt));
        return CortexJMapper.chatResponse(response, modelId());
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

    @Override
    public ChatOptions getOptions() {
        // the planner resolved the artifact already: expose its canonical id,
        // remaining defaults stay empty
        return ChatOptions.builder().model(modelId()).build();
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
