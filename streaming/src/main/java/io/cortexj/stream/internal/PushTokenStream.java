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

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import io.cortexj.model.ToolCall;

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
    // audit 2026-10-07 (#146): the live queue used to be unbounded after
    // subscribe - a fast producer with a slow consumer (an SSE socket
    // stalled on a reader) grew it until OOM. Same typed-overflow contract
    // as the pre-subscribe buffer.
    public static final int LIVE_TOKEN_BUFFER_LIMIT = 4096;

    private final Object lock = new Object();
    private final AtomicReference<TokenConsumer> consumer = new AtomicReference<TokenConsumer>();
    private final AtomicBoolean subscribed = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean completed = new AtomicBoolean(false);
    private final AtomicBoolean failed = new AtomicBoolean(false);
    private final AtomicInteger emitted = new AtomicInteger(0);
    private final AtomicReference<Runnable> cancelAction = new AtomicReference<Runnable>();
    private final AtomicBoolean cancelActionRan = new AtomicBoolean(false);
    // drain-owner guard: at most one thread dispatches at a time, so user code
    // never runs under the monitor and never overlaps (GitHub #57)
    private final AtomicBoolean delivering = new AtomicBoolean(false);

    // replay state for tokens that fire before subscribe()
    private final Queue<Token> earlyTokens = new ArrayDeque<Token>();
    // tool-call batches published before subscribe() (same replay contract)
    private final Queue<List<ToolCall>> earlyToolCalls = new ArrayDeque<List<ToolCall>>();
    // live delivery queue (post-subscribe): early tokens are handed over in
    // FIFO order on subscribe, so replay-before-live ordering holds without
    // delivering under the lock
    private final Queue<Token> pending = new ArrayDeque<Token>();
    // tool-call batches queued for delivery after the tokens and before the
    // terminal (providers publish accumulated calls once, never interleaved)
    private final Queue<List<ToolCall>> pendingToolCalls = new ArrayDeque<List<ToolCall>>();
    // terminal fired but not yet delivered; at most one of the two is ever set
    private StreamCompletion bufferedCompletion;
    private CortexJException bufferedError;

    public void emit(String text, java.util.Map<String, String> metadata) {
        // audit 2026-10-07 (#146): the sequence number is assigned only for
        // accepted tokens - dropped post-terminal emits no longer punch
        // holes into the observable token indexes
        boolean deliverNow = false;
        boolean overflowed = false;
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
                earlyTokens.add(new Token(text, emitted.getAndIncrement(), metadata));
                return;
            }
            if (pending.size() >= LIVE_TOKEN_BUFFER_LIMIT) {
                failLocked(liveOverflowError(LIVE_TOKEN_BUFFER_LIMIT));
                overflowed = true;
            } else {
                pending.add(new Token(text, emitted.getAndIncrement(), metadata));
                deliverNow = true;
            }
        }
        if (overflowed) {
            // stop the producer: without backpressure the alternative is an
            // unbounded heap. Same path as an explicit cancel.
            close();
            drain();
            return;
        }
        if (deliverNow) {
            drain();
        }
    }

    /**
     * Queues a batch of structured tool calls for delivery. The batch is
     * delivered after the tokens published so far and before the terminal -
     * providers publish the accumulated calls once, never interleaved with
     * tokens. Ignored after the terminal fired (parity with post-terminal
     * token emits).
     */
    public void publishToolCalls(List<ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            return;
        }
        boolean deliverNow = false;
        boolean overflowed = false;
        synchronized (lock) {
            if (closed.get() || completed.get() || failed.get()) {
                return;
            }
            if (consumer.get() == null) {
                if (earlyToolCalls.size() >= EARLY_TOKEN_BUFFER_LIMIT) {
                    failLocked(earlyToolCallOverflowError(EARLY_TOKEN_BUFFER_LIMIT));
                    return;
                }
                earlyToolCalls.add(new ArrayList<ToolCall>(toolCalls));
                return;
            }
            if (pendingToolCalls.size() >= LIVE_TOKEN_BUFFER_LIMIT) {
                failLocked(liveOverflowError(LIVE_TOKEN_BUFFER_LIMIT));
                overflowed = true;
            } else {
                pendingToolCalls.add(new ArrayList<ToolCall>(toolCalls));
                deliverNow = true;
            }
        }
        if (overflowed) {
            close();
            drain();
            return;
        }
        if (deliverNow) {
            drain();
        }
    }

    private static CortexJException earlyToolCallOverflowError(int limit) {
        return new InferenceException(
                ErrorCode.INFERENCE_STREAM_FAILED,
                "token stream buffer overflow: more than " + limit
                        + " tool-call batches were published before a subscriber attached; "
                        + "the generation was failed",
                "Subscribe to the token stream before the producer starts, or "
                        + "raise PushTokenStream.EARLY_TOKEN_BUFFER_LIMIT; the "
                        + "stream never buffers without bound.");
    }

    public void complete(FinishReason finishReason, io.cortexj.inference.TokenUsage usage) {        boolean recorded = false;
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
    private static CortexJException liveOverflowError(int limit) {
        return new InferenceException(
                ErrorCode.INFERENCE_STREAM_FAILED,
                "token stream live buffer overflow: more than " + limit
                        + " tokens are queued behind a slow consumer; "
                        + "the generation was failed instead of growing the heap",
                "Consume tokens faster (or offload the consumer), subscribe "
                        + "earlier, or raise PushTokenStream.LIVE_TOKEN_BUFFER_LIMIT; "
                        + "the stream never buffers without bound.");
    }

    private static CortexJException consumerError(Throwable consumerFailure) {
        String detail = consumerFailure == null
                ? "null"
                : consumerFailure.getClass().getName()
                + (consumerFailure.getMessage() == null ? "" : ": " + consumerFailure.getMessage());
        return new InferenceException(
                ErrorCode.INFERENCE_STREAM_FAILED,
                "token stream consumer threw and the stream was failed: " + detail,
                "Do not throw from TokenConsumer callbacks; handle consumer errors inside onToken/onError/onComplete.",
                consumerFailure);
    }

    public void onCancel(Runnable action) {
        this.cancelAction.set(action);
        if (closed.get()) {
            runCancelActionOnce();
        }
    }

    private void runCancelActionOnce() {
        Runnable action = cancelAction.get();
        if (action != null && cancelActionRan.compareAndSet(false, true)) {
            action.run();
        }
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
            // under the monitor.
            // audit 2026-10-08: enforce the live invariant explicitly instead
            // of relying on EARLY_TOKEN_BUFFER_LIMIT staying below
            // LIVE_TOKEN_BUFFER_LIMIT - unreachable today, fail-closed if the
            // caps ever drift apart
            pending.addAll(earlyTokens);
            earlyTokens.clear();
            pendingToolCalls.addAll(earlyToolCalls);
            earlyToolCalls.clear();
            if (pending.size() > LIVE_TOKEN_BUFFER_LIMIT
                    || pendingToolCalls.size() > LIVE_TOKEN_BUFFER_LIMIT) {
                failLocked(liveOverflowError(LIVE_TOKEN_BUFFER_LIMIT));
            }
            consumer.set(tokenConsumer);
        }
        // replay + live delivery happens in drain(), outside the monitor
        // (GitHub #57): a blocking consumer cannot pin other threads' carriers
        drain();
        return subscription();
    }

    private void drain() {
        // audit 2026-10-08 (#153): iterative re-acquire instead of end
        // recursion - under a continuous feed the old tail call nested one
        // frame per re-check and risked StackOverflow on long streams
        if (!delivering.compareAndSet(false, true)) {
            // another thread owns dispatch; it will observe our buffered work
            return;
        }
        try {
            dispatchLoop();
        } finally {
            delivering.set(false);
        }
        while (hasWork()) {
            if (!delivering.compareAndSet(false, true)) {
                return;
            }
            try {
                dispatchLoop();
            } finally {
                delivering.set(false);
            }
        }
    }

    private void dispatchLoop() {
        TokenConsumer target = consumer.get();
        if (target == null) {
            return;
        }
        while (true) {
            Token token = null;
            List<ToolCall> toolCallBatch = null;
            StreamCompletion completion = null;
            CortexJException error = null;
            synchronized (lock) {
                if (!pending.isEmpty()) {
                    token = pending.poll();
                } else if (!pendingToolCalls.isEmpty()) {
                    toolCallBatch = pendingToolCalls.poll();
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
            // user code invoked OUTSIDE the monitor (GitHub #57).
            // audit 2026-10-08: a throwing consumer must not stall the
            // stream - fail it with a typed error instead of abandoning
            // the remaining tokens and the terminal signal
            if (token != null || toolCallBatch != null) {
                try {
                    if (token != null) {
                        target.onToken(token);
                    } else {
                        target.onToolCalls(toolCallBatch);
                    }
                } catch (Throwable consumerFailure) {
                    CortexJException typed = consumerError(consumerFailure);
                    synchronized (lock) {
                        pending.clear();
                        pendingToolCalls.clear();
                        bufferedCompletion = null;
                        failLocked(typed);
                        bufferedError = null;
                    }
                    close();
                    try {
                        target.onError(typed);
                    } catch (Throwable ignored) {
                        // the consumer is gone; the stream is failed, stop here
                    }
                    return;
                }
            } else if (error != null) {
                try {
                    target.onError(error);
                } catch (Throwable ignored) {
                    // terminal callback threw; do not re-enter onError
                }
            } else {
                try {
                    target.onComplete(completion);
                } catch (Throwable ignored) {
                    // terminal callback threw; do not call onError after onComplete
                }
            }
        }
    }

    private boolean hasWork() {
        synchronized (lock) {
            return consumer.get() != null
                    && (!pending.isEmpty() || !pendingToolCalls.isEmpty()
                        || bufferedError != null || bufferedCompletion != null);
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
            runCancelActionOnce();
        }
    }
}
