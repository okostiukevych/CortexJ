package io.cortexj.springai;

import io.cortexj.CortexJ;
import io.cortexj.error.CortexJException;
import io.cortexj.inference.GenerationRequest;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelReference;
import io.cortexj.stream.StreamSubscription;
import io.cortexj.stream.TokenConsumer;
import io.cortexj.stream.TokenStream;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.StreamingChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

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
        this.platform = platform;
        this.ownsPlatform = ownsPlatform;
        this.handle = platform.models().load(ModelReference.of(modelReference));
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.<ChatResponse>create(sink -> {
            TokenStream stream = generator().stream(GenerationRequest.prompt(prompt.getContents()));
            AtomicReference<StreamSubscription> subscription = new AtomicReference<StreamSubscription>();
            sink.onCancel(() -> {
                StreamSubscription active = subscription.get();
                if (active != null) {
                    active.cancel();
                }
                stream.close();
            });
            subscription.set(stream.subscribe(new TokenConsumer() {
                @Override
                public void onToken(io.cortexj.stream.Token token) {
                    if (!sink.isCancelled()) {
                        sink.next(new ChatResponse(List.of(new Generation(new AssistantMessage(token.text())))));
                    }
                }

                @Override
                public void onComplete(io.cortexj.stream.StreamCompletion completion) {
                    sink.complete();
                    stream.close();
                }

                @Override
                public void onError(CortexJException error) {
                    sink.error(error);
                    stream.close();
                }
            }));
        }, FluxSink.OverflowStrategy.BUFFER);
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
