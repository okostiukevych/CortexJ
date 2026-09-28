package io.cortexj.stream.internal;

import io.cortexj.error.CortexJException;
import io.cortexj.inference.FinishReason;
import io.cortexj.stream.StreamCompletion;
import io.cortexj.stream.StreamSubscription;
import io.cortexj.stream.Token;
import io.cortexj.stream.TokenConsumer;
import io.cortexj.stream.TokenStream;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Push-based token stream with replay-before-subscribe semantics (audit H-2):
 * tokens emitted and the terminal signal (completion or error) fired before a
 * subscriber attaches are buffered and replayed from subscribe(), so a fast
 * producer (an eagerly started SSE worker, an instant connection error) can no
 * longer deadlock a consumer that subscribes a moment later.
 *
 * <p>All deliveries happen under {@code lock}, which guarantees global order:
 * buffered tokens are replayed before any concurrently emitted token can be
 * delivered. Exactly-once terminal semantics are preserved via one-shot flags.</p>
 */
public final class PushTokenStream implements TokenStream {

    public static final int EARLY_TOKEN_BUFFER_LIMIT = 1024;

    private final Object lock = new Object();
    private final AtomicReference<TokenConsumer> consumer = new AtomicReference<TokenConsumer>();
    private final AtomicBoolean subscribed = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean completed = new AtomicBoolean(false);
    private final AtomicBoolean failed = new AtomicBoolean(false);
    private final AtomicInteger emitted = new AtomicInteger(0);
    private final AtomicReference<Runnable> cancelAction = new AtomicReference<Runnable>();

    // replay state for events that fire before subscribe()
    private final Queue<Token> earlyTokens = new ArrayDeque<Token>();
    private StreamCompletion earlyCompletion;
    private CortexJException earlyError;

    public void emit(String text, java.util.Map<String, String> metadata) {
        Token token = new Token(text, emitted.getAndIncrement(), metadata);
        synchronized (lock) {
            if (closed.get() || completed.get() || failed.get()) {
                return;
            }
            TokenConsumer target = consumer.get();
            if (target != null) {
                target.onToken(token);
                return;
            }
            if (earlyTokens.size() >= EARLY_TOKEN_BUFFER_LIMIT) {
                completeLocked(FinishReason.CONTENT_FILTER, null);
                return;
            }
            earlyTokens.add(token);
        }
    }

    public void complete(FinishReason finishReason, io.cortexj.inference.TokenUsage usage) {
        synchronized (lock) {
            completeLocked(finishReason, usage);
        }
    }

    private void completeLocked(FinishReason finishReason, io.cortexj.inference.TokenUsage usage) {
        if (completed.compareAndSet(false, true)) {
            failed.set(true);
            TokenConsumer target = consumer.get();
            if (target != null) {
                target.onComplete(new StreamCompletion(finishReason, usage));
            } else {
                earlyCompletion = new StreamCompletion(finishReason, usage);
            }
        }
    }

    public void fail(CortexJException error) {
        synchronized (lock) {
            if (failed.compareAndSet(false, true)) {
                completed.set(true);
                TokenConsumer target = consumer.get();
                if (target != null) {
                    target.onError(error);
                } else {
                    earlyError = error;
                }
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
        synchronized (lock) {
            consumer.set(tokenConsumer);
            // replay everything the producer fired before the subscriber attached;
            // holding the lock here preserves global order: buffered events are
            // replayed before any concurrently emitted token can be delivered
            while (!earlyTokens.isEmpty()) {
                tokenConsumer.onToken(earlyTokens.poll());
            }
            if (earlyCompletion != null) {
                tokenConsumer.onComplete(earlyCompletion);
                earlyCompletion = null;
            } else if (earlyError != null) {
                tokenConsumer.onError(earlyError);
                earlyError = null;
            }
        }
        return subscription();
    }

    private StreamSubscription subscription() {
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
