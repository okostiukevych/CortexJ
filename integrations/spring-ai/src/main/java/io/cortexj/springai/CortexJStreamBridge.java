package io.cortexj.springai;

import io.cortexj.error.CortexJException;
import io.cortexj.inference.FinishReason;
import io.cortexj.inference.GenerationRequest;
import io.cortexj.inference.TextGenerationCapability;
import io.cortexj.inference.TokenUsage;
import io.cortexj.stream.StreamCompletion;
import io.cortexj.stream.StreamSubscription;
import io.cortexj.stream.Token;
import io.cortexj.stream.TokenConsumer;
import io.cortexj.stream.TokenStream;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Bridges a CortexJ {@link TokenStream} into a {@code Flux<ChatResponse>}.
 *
 * <p>Streaming contract, aligned with how Spring model providers stream:</p>
 * <ul>
 *   <li>one chunk per token (empty token payloads are filtered out);</li>
 *   <li>a final chunk with {@code finishReason} generation metadata and the
 *       accumulated token {@code usage} (empty text), matching the
 *       usage-on-last-chunk convention of OpenAI-style streaming;</li>
 *   <li>cancelling the Flux cancels the underlying stream subscription and
 *       closes the stream.</li>
 * </ul>
 *
 * <p>Overflow strategy is {@link FluxSink.OverflowStrategy#BUFFER}: the buffer
 * holds tokens until the consumer keeps up - dropping values (LATEST/DROP)
 * would silently corrupt generated text for slow consumers. Memory is bounded
 * by consumer speed, not by generation length in the happy path.</p>
 */
final class CortexJStreamBridge {

    private CortexJStreamBridge() {
    }

    static Flux<ChatResponse> stream(TextGenerationCapability generator, String modelId, Prompt prompt) {
        GenerationRequest request = CortexJMapper.generationRequest(prompt);
        return Flux.<ChatResponse>create(sink -> {
            TokenStream stream = generator.stream(request);
            AtomicReference<StreamSubscription> subscription = new AtomicReference<StreamSubscription>();
            AtomicBoolean terminal = new AtomicBoolean(false);
            sink.onCancel(() -> appendShutdown(stream, subscription.get()));
            subscription.set(stream.subscribe(new TokenConsumer() {
                @Override
                public void onToken(Token token) {
                    if (terminal.get() || sink.isCancelled()) {
                        return;
                    }
                    String text = token.text();
                    if (text != null && !text.isEmpty()) {
                        sink.next(new ChatResponse(java.util.List.<Generation>of(new Generation(new AssistantMessage(text)))));
                    }
                }

                @Override
                public void onComplete(StreamCompletion completion) {
                    if (terminal.compareAndSet(false, true)) {
                        if (!sink.isCancelled()) {
                            FinishReason finishReason = completion.finishReason();
                            TokenUsage usage = completion.usage();
                            sink.next(new ChatResponse(
                                    java.util.List.of(CortexJMapper.generation("", finishReason)),
                                    CortexJMapper.metadata(modelId, usage, null)));
                        }
                        sink.complete();
                        appendShutdown(stream, subscription.get());
                    }
                }

                @Override
                public void onError(CortexJException error) {
                    if (terminal.compareAndSet(false, true)) {
                        sink.error(error);
                        appendShutdown(stream, subscription.get());
                    }
                }
            }));
        }, FluxSink.OverflowStrategy.BUFFER);
    }

    private static void appendShutdown(TokenStream stream, StreamSubscription subscription) {
        if (subscription != null) {
            subscription.cancel();
        }
        stream.close();
    }
}
