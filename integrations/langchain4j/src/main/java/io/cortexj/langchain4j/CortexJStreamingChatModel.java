package io.cortexj.langchain4j;

import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import io.cortexj.CortexJ;
import io.cortexj.error.CortexJException;
import io.cortexj.inference.GenerationRequest;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelReference;
import io.cortexj.stream.StreamCompletion;
import io.cortexj.stream.StreamSubscription;
import io.cortexj.stream.TokenConsumer;
import io.cortexj.stream.TokenStream;

public final class CortexJStreamingChatModel implements dev.langchain4j.model.chat.StreamingChatModel, AutoCloseable {

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
        this.platform = platform;
        this.ownsPlatform = ownsPlatform;
        this.handle = platform.models().load(ModelReference.of(modelReference));
    }

    @Override
    public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
        TokenStream stream = generator().stream(GenerationRequest.prompt(CortexJChatModel.userText(request)));
        StringBuilder collected = new StringBuilder();
        stream.subscribe(new TokenConsumer() {
            @Override
            public void onToken(io.cortexj.stream.Token token) {
                synchronized (collected) {
                    collected.append(token.text());
                }
                handler.onPartialResponse(token.text());
            }

            @Override
            public void onComplete(StreamCompletion completion) {
                String text;
                synchronized (collected) {
                    text = collected.toString();
                }
                ChatResponse response = ChatResponse.builder()
                        .aiMessage(dev.langchain4j.data.message.AiMessage.from(text))
                        .build();
                try {
                    stream.close();
                } finally {
                    handler.onCompleteResponse(response);
                }
            }

            @Override
            public void onError(CortexJException error) {
                try {
                    stream.close();
                } finally {
                    handler.onError(error);
                }
            }
        });
    }

    @Override
    public void close() {
        handle.close();
        if (ownsPlatform) {
            platform.close();
        }
    }

    io.cortexj.inference.TextGenerationCapability generator() {
        return handle.capability(io.cortexj.inference.TextGenerationCapability.class);
    }
}
