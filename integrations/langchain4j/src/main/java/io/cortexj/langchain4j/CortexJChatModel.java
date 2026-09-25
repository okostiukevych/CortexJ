package io.cortexj.langchain4j;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import io.cortexj.CortexJ;
import io.cortexj.inference.FinishReason;
import io.cortexj.inference.GenerationRequest;
import io.cortexj.inference.GenerationResponse;
import io.cortexj.inference.TextGenerationCapability;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelReference;

public final class CortexJChatModel implements dev.langchain4j.model.chat.ChatModel, AutoCloseable {

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
    public dev.langchain4j.model.chat.response.ChatResponse chat(ChatRequest request) {
        GenerationResponse response = generator().generate(GenerationRequest.prompt(userText(request)));
        dev.langchain4j.model.output.TokenUsage usage = response.usage() != null
                && response.usage().isKnown()
                ? new dev.langchain4j.model.output.TokenUsage(
                        (int) response.usage().inputTokens(), (int) response.usage().outputTokens())
                : null;
        dev.langchain4j.model.chat.response.ChatResponse.Builder builder =
                dev.langchain4j.model.chat.response.ChatResponse.builder()
                        .aiMessage(dev.langchain4j.data.message.AiMessage.from(response.text()));
        if (usage != null) {
            builder.metadata(dev.langchain4j.model.chat.response.ChatResponseMetadata.builder()
                    .tokenUsage(usage)
                    .finishReason(finishReason(response.finishReason()))
                    .build());
        }
        return builder.build();
    }

    public String modelName() {
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

    static String userText(ChatRequest request) {
        String last = null;
        for (ChatMessage message : request.messages()) {
            if (message instanceof UserMessage) {
                last = ((UserMessage) message).singleText();
            }
        }
        return last == null ? "" : last;
    }

    static dev.langchain4j.model.output.FinishReason finishReason(FinishReason reason) {
        if (reason == null) {
            return null;
        }
        switch (reason) {
            case STOP:
                return dev.langchain4j.model.output.FinishReason.STOP;
            case LENGTH:
                return dev.langchain4j.model.output.FinishReason.LENGTH;
            case CONTENT_FILTER:
                return dev.langchain4j.model.output.FinishReason.CONTENT_FILTER;
            case TOOL_CALL:
                return dev.langchain4j.model.output.FinishReason.TOOL_EXECUTION;
            default:
                return dev.langchain4j.model.output.FinishReason.OTHER;
        }
    }
}
