package io.cortexj.springai;

import io.cortexj.CortexJ;
import io.cortexj.inference.GenerationRequest;
import io.cortexj.inference.GenerationResponse;
import io.cortexj.inference.TextGenerationCapability;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelReference;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.List;

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
        this.platform = platform;
        this.ownsPlatform = ownsPlatform;
        this.handle = platform.models().load(ModelReference.of(modelReference));
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        GenerationResponse response = generator().generate(GenerationRequest.prompt(prompt.getContents()));
        return new ChatResponse(List.of(new Generation(new AssistantMessage(response.text()))));
    }

    public String getModelName() {
        return handle.descriptor().canonicalId();
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
}
