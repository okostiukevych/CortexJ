package io.cortexj.stream;

import io.cortexj.error.CortexJException;
import io.cortexj.inference.FinishReason;

public final class TokenStreams {

    private TokenStreams() {
    }

    public static TokenStream single(final String text) {
        return single(text, java.util.Collections.<io.cortexj.model.ToolCall>emptyList());
    }

    /** Text-only in-memory stream that also carries proposed tool calls (nonStreaming providers). */
    public static TokenStream single(final String text, final java.util.List<io.cortexj.model.ToolCall> toolCalls) {
        return new TokenStream() {
            @Override
            public StreamSubscription subscribe(TokenConsumer consumer) {
                consumer.onToken(new Token(text, 0, null));
                if (toolCalls != null && !toolCalls.isEmpty()) {
                    consumer.onToolCalls(toolCalls);
                }
                consumer.onComplete(new StreamCompletion(FinishReason.STOP, null));
                return new StreamSubscription() {
                    @Override
                    public void cancel() {
                    }
                };
            }

            @Override
            public void close() {
            }
        };
    }

    public static TokenStream failed(final CortexJException error) {
        return new TokenStream() {
            @Override
            public StreamSubscription subscribe(TokenConsumer consumer) {
                consumer.onError(error);
                return new StreamSubscription() {
                    @Override
                    public void cancel() {
                    }
                };
            }

            @Override
            public void close() {
            }
        };
    }
}
