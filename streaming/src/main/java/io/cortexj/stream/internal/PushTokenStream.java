package io.cortexj.stream.internal;

import io.cortexj.error.CortexJException;
import io.cortexj.inference.FinishReason;
import io.cortexj.stream.StreamCompletion;
import io.cortexj.stream.StreamSubscription;
import io.cortexj.stream.Token;
import io.cortexj.stream.TokenConsumer;
import io.cortexj.stream.TokenStream;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class PushTokenStream implements TokenStream {

    private final Object lock = new Object();
    private final AtomicReference<TokenConsumer> consumer = new AtomicReference<TokenConsumer>();
    private final AtomicBoolean subscribed = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean completed = new AtomicBoolean(false);
    private final AtomicBoolean failed = new AtomicBoolean(false);
    private final AtomicInteger emitted = new AtomicInteger(0);
    private final AtomicReference<Runnable> cancelAction = new AtomicReference<Runnable>();

    public void emit(String text, java.util.Map<String, String> metadata) {
        Token token = new Token(text, emitted.getAndIncrement(), metadata);
        TokenConsumer target = consumer.get();
        if (target != null && !closed.get() && !completed.get() && !failed.get()) {
            target.onToken(token);
        }
    }

    public void complete(FinishReason finishReason, io.cortexj.inference.TokenUsage usage) {
        if (completed.compareAndSet(false, true)) {
            TokenConsumer target = consumer.get();
            if (target != null) {
                target.onComplete(new StreamCompletion(finishReason, usage));
            }
        }
    }

    public void fail(CortexJException error) {
        if (failed.compareAndSet(false, true)) {
            TokenConsumer target = consumer.get();
            if (target != null) {
                target.onError(error);
            }
        }
    }

    public void onCancel(Runnable action) {
        this.cancelAction.set(action);
    }

    @Override
    public StreamSubscription subscribe(TokenConsumer tokenConsumer) {
        if (tokenConsumer == null) {
            throw new IllegalArgumentException("Token consumer must not be null");
        }
        if (!subscribed.compareAndSet(false, true)) {
            throw new IllegalStateException("Token stream supports a single subscription");
        }
        consumer.set(tokenConsumer);
        final PushTokenStream self = this;
        return new StreamSubscription() {
            private final AtomicBoolean cancelled = new AtomicBoolean(false);

            @Override
            public void cancel() {
                if (cancelled.compareAndSet(false, true)) {
                    self.close();
                    Runnable action = self.cancelAction.get();
                    if (action != null) {
                        action.run();
                    }
                }
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }
        };
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            Runnable action = cancelAction.get();
            if (action != null) {
                action.run();
            }
        }
    }
}
