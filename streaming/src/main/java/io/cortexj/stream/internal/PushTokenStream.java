package io.cortexj.stream.internal;

import io.cortexj.ErrorCode;
import io.cortexj.error.CortexJException;
import io.cortexj.error.InferenceException;
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
 * <p>Lock discipline (GitHub #57): {@code lock} guards state mutation only.
 * User callbacks are never invoked while holding the monitor - deliveries are
 * serialized by a drain-owner guard and dispatched outside it, so a consumer
 * chain that blocks inside {@code onToken} (socket writes in an SSE handler)
 * cannot pin the carrier of another producer or subscriber thread (virtual
 * threads, Java 21+). Global order is preserved: all events enter a single
 * FIFO queue, buffered tokens are replayed ahead of any concurrently emitted
 * token (replay-before-live), and the terminal is delivered exactly once via
 * one-shot flags. The cancel path stays CAS-based and lock-free.</p>
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
    // drain-owner guard: at most one thread dispatches at a time, so user code
    // never runs under the monitor and never overlaps (GitHub #57)
    private final AtomicBoolean delivering = new AtomicBoolean(false);

    // replay state for tokens that fire before subscribe()
    private final Queue<Token> earlyTokens = new ArrayDeque<Token>();
    // live delivery queue (post-subscribe): early tokens are handed over in
    // FIFO order on subscribe, so replay-before-live ordering holds without
    // delivering under the lock
    private final Queue<Token> pending = new ArrayDeque<Token>();
    // terminal fired but not yet delivered; at most one of the two is ever set
    private StreamCompletion bufferedCompletion;
    private CortexJException bufferedError;

    public void emit(String text, java.util.Map<String, String> metadata) {
        Token token = new Token(text, emitted.getAndIncrement(), metadata);
        boolean deliverNow = false;
        synchronized (lock) {
            if (closed.get() || completed.get() || failed.get()) {
                return;
            }
            if (consumer.get() == null) {
                if (earlyTokens.size() >= EARLY_TOKEN_BUFFER_LIMIT) {
                    // typed overflow failure (parity with TokenFlowAdapter's
                    // explicit overflow policy, audit M-13): never masquerade
                    // the overflow as a FinishReason completion
                    failLocked(overflowError(EARLY_TOKEN_BUFFER_LIMIT));
                    return;
                }
                earlyTokens.add(token);
                return;
            }
            pending.add(token);
            deliverNow = true;
        }
        if (deliverNow) {
            drain();
        }
    }

    public void complete(FinishReason finishReason, io.cortexj.inference.TokenUsage usage) {
        boolean recorded = false;
        synchronized (lock) {
            recorded = completeLocked(finishReason, usage);
        }
        if (recorded && consumer.get() != null) {
            drain();
        }
    }

    private boolean completeLocked(FinishReason finishReason, io.cortexj.inference.TokenUsage usage) {
        if (!completed.compareAndSet(false, true)) {
            return false;
        }
        failed.set(true);
        bufferedCompletion = new StreamCompletion(finishReason, usage);
        return true;
    }

    public void fail(CortexJException error) {
        boolean recorded = false;
        synchronized (lock) {
            recorded = failLocked(error);
        }
        if (recorded && consumer.get() != null) {
            drain();
        }
    }

    private boolean failLocked(CortexJException error) {
        if (!failed.compareAndSet(false, true)) {
            return false;
        }
        completed.set(true);
        bufferedError = error;
        return true;
    }

    private static CortexJException overflowError(int limit) {
        return new InferenceException(
                ErrorCode.INFERENCE_STREAM_FAILED,
                "token stream buffer overflow: more than " + limit
                        + " tokens were emitted before a subscriber attached; "
                        + "the generation was failed",
                "Subscribe to the token stream before the producer starts, or "
                        + "raise PushTokenStream.EARLY_TOKEN_BUFFER_LIMIT; the "
                        + "stream never buffers without bound.");
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
            // hand the buffered tokens to the delivery queue in FIFO order;
            // they were enqueued before any post-subscribe emit can enqueue,
            // so replay-before-live ordering holds without invoking user code
            // under the monitor
            pending.addAll(earlyTokens);
            earlyTokens.clear();
            consumer.set(tokenConsumer);
        }
        // replay + live delivery happens in drain(), outside the monitor
        // (GitHub #57): a blocking consumer cannot pin other threads' carriers
        drain();
        return subscription();
    }

    private void drain() {
        if (!delivering.compareAndSet(false, true)) {
            // another thread owns dispatch; it will observe our buffered work
            return;
        }
        try {
            TokenConsumer target = consumer.get();
            if (target == null) {
                return;
            }
            while (true) {
                Token token = null;
                StreamCompletion completion = null;
                CortexJException error = null;
                synchronized (lock) {
                    if (!pending.isEmpty()) {
                        token = pending.poll();
                    } else if (bufferedError != null) {
                        error = bufferedError;
                        bufferedError = null;
                    } else if (bufferedCompletion != null) {
                        completion = bufferedCompletion;
                        bufferedCompletion = null;
                    } else {
                        return;
                    }
                }
                // user code invoked OUTSIDE the monitor (GitHub #57)
                if (token != null) {
                    target.onToken(token);
                } else if (error != null) {
                    target.onError(error);
                } else {
                    target.onComplete(completion);
                }
            }
        } finally {
            delivering.set(false);
            // missed-work re-check: work may have arrived between the last
            // empty check and the release; whoever added it may have returned
            // early because we held the drain guard
            if (hasWork()) {
                drain();
            }
        }
    }

    private boolean hasWork() {
        synchronized (lock) {
            return consumer.get() != null
                    && (!pending.isEmpty() || bufferedError != null || bufferedCompletion != null);
        }
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
