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
            private final java.util.concurrent.atomic.AtomicBoolean subscribed =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            private final java.util.concurrent.atomic.AtomicBoolean closed =
                    new java.util.concurrent.atomic.AtomicBoolean(false);

            @Override
            public StreamSubscription subscribe(TokenConsumer consumer) {
                if (consumer == null) {
                    throw new IllegalArgumentException("Token consumer must not be null");
                }
                if (!subscribed.compareAndSet(false, true)) {
                    throw new IllegalStateException("Token stream supports a single subscription");
                }
                final java.util.concurrent.atomic.AtomicBoolean cancelled =
                        new java.util.concurrent.atomic.AtomicBoolean(closed.get());
                if (!cancelled.get()) {
                    consumer.onToken(new Token(text, 0, null));
                    if (toolCalls != null && !toolCalls.isEmpty()) {
                        consumer.onToolCalls(toolCalls);
                    }
                    consumer.onComplete(new StreamCompletion(FinishReason.STOP, null));
                }
                return new StreamSubscription() {
                    @Override
                    public void cancel() {
                        cancelled.set(true);
                        closed.set(true);
                    }

                    @Override
                    public boolean isCancelled() {
                        return cancelled.get() || closed.get();
                    }
                };
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };
    }

    public static TokenStream failed(final CortexJException error) {
        return new TokenStream() {
            private final java.util.concurrent.atomic.AtomicBoolean subscribed =
                    new java.util.concurrent.atomic.AtomicBoolean(false);
            private final java.util.concurrent.atomic.AtomicBoolean closed =
                    new java.util.concurrent.atomic.AtomicBoolean(false);

            @Override
            public StreamSubscription subscribe(TokenConsumer consumer) {
                if (consumer == null) {
                    throw new IllegalArgumentException("Token consumer must not be null");
                }
                if (!subscribed.compareAndSet(false, true)) {
                    throw new IllegalStateException("Token stream supports a single subscription");
                }
                final java.util.concurrent.atomic.AtomicBoolean cancelled =
                        new java.util.concurrent.atomic.AtomicBoolean(closed.get());
                if (!cancelled.get()) {
                    consumer.onError(error);
                }
                return new StreamSubscription() {
                    @Override
                    public void cancel() {
                        cancelled.set(true);
                        closed.set(true);
                    }

                    @Override
                    public boolean isCancelled() {
                        return cancelled.get() || closed.get();
                    }
                };
            }

            @Override
            public void close() {
                closed.set(true);
            }
        };
    }
}
