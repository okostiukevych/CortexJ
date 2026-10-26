package io.cortexj.stream.flow;

import io.cortexj.error.CortexJException;
import io.cortexj.stream.StreamSubscription;
import io.cortexj.stream.Token;
import io.cortexj.stream.TokenConsumer;
import io.cortexj.stream.TokenStream;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

public final class TokenFlowAdapter implements Flow.Publisher<Token> {

    public static final int DEFAULT_BUFFER_CAPACITY = 1024;

    private final TokenStream stream;
    private final int bufferCapacity;

    private TokenFlowAdapter(TokenStream stream, int bufferCapacity) {
        if (stream == null) {
            throw new IllegalArgumentException("token stream must not be null");
        }
        if (bufferCapacity <= 0) {
            throw new IllegalArgumentException("buffer capacity must be positive: " + bufferCapacity);
        }
        this.stream = stream;
        this.bufferCapacity = bufferCapacity;
    }

    public static TokenFlowAdapter publisher(TokenStream stream) {
        return new TokenFlowAdapter(stream, DEFAULT_BUFFER_CAPACITY);
    }

    /**
     * Explicit overflow policy (audit M-13): the adapter never buffers without
     * bound. At most {@code bufferCapacity} tokens are held while the
     * subscriber's demand lags; beyond that the generation is cancelled and an
     * explicit {@code onError} signal is delivered (dropping tokens silently
     * would corrupt a text generation, so the loud policy is the default).
     */
    public static TokenFlowAdapter publisher(TokenStream stream, int bufferCapacity) {
        return new TokenFlowAdapter(stream, bufferCapacity);
    }

    @Override
    public void subscribe(Flow.Subscriber<? super Token> subscriber) {
        if (subscriber == null) {
            throw new IllegalArgumentException("subscriber must not be null");
        }
        new Bridge(stream, subscriber, bufferCapacity).start();
    }

    private static final class Bridge {

        private final TokenStream stream;
        private final Flow.Subscriber<? super Token> downstream;
        private final int bufferCapacity;
        private final Queue<Token> buffered = new ArrayDeque<Token>();
        private final AtomicLong demand = new AtomicLong();
        private final AtomicBoolean completed = new AtomicBoolean(false);
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        private final AtomicBoolean overflowed = new AtomicBoolean(false);
        // Flow rule 3.9 (GitHub #79): a non-positive request must produce
        // exactly ONE onError, then the bridge stops delivering signals
        private final AtomicBoolean requestFailureSignalled = new AtomicBoolean(false);
        private Throwable requestError;
        private boolean errorDelivered;
        // audit #93: an upstream failure is buffered and delivered through the
        // drain guard, never directly from the producer thread - otherwise it
        // would race a concurrent onNext into the subscriber (Flow rule 1.3)
        private io.cortexj.error.CortexJException upstreamError;
        // Flow contract: onNext/onComplete must be delivered serially, by at most
        // one thread at a time. The drain-owner guard serializes dispatch when the
        // publisher thread (offer/onComplete) and the subscriber thread (request)
        // race into drain() together (audit H-5).
        private final AtomicBoolean draining = new AtomicBoolean(false);
        private boolean completionDelivered;
        private final Object lock = new Object();
        // upstream subscription handle; set only after the downstream has
        // received onSubscribe (GitHub #79), so a cancel() or request() issued
        // from inside onSubscribe finds it null and is handled gracefully
        private final AtomicReference<StreamSubscription> upstream =
                new AtomicReference<StreamSubscription>();

        Bridge(TokenStream stream, Flow.Subscriber<? super Token> downstream, int bufferCapacity) {
            this.stream = stream;
            this.downstream = downstream;
            this.bufferCapacity = bufferCapacity;
        }

        void start() {
            // Flow rule 1.9 (GitHub #79): onSubscribe must be the FIRST signal
            // on the downstream. The subscription is created and issued before
            // the upstream consumer is attached, so a synchronous upstream
            // (TokenStreams.single/failed, a replaying PushTokenStream) can no
            // longer deliver tokens or a terminal ahead of it.
            downstream.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    if (n <= 0) {
                        rejectNonPositiveRequest(n);
                        return;
                    }
                    synchronized (lock) {
                        long current = demand.get();
                        // Flow rule 3.17: saturate at Long.MAX_VALUE instead of overflowing
                        demand.set(current >= Long.MAX_VALUE - n ? Long.MAX_VALUE : current + n);
                    }
                    drain();
                }

                @Override
                public void cancel() {
                    cancelled.set(true);
                    StreamSubscription up = upstream.get();
                    if (up != null) {
                        up.cancel();
                    }
                    stream.close();
                }
            });

            if (cancelled.get()) {
                // the subscriber cancelled (or violated the request rule) inside
                // onSubscribe: do not attach the upstream consumer
                stream.close();
                return;
            }

            StreamSubscription sub = stream.subscribe(new TokenConsumer() {
                @Override
                public void onToken(Token token) {
                    if (cancelled.get()) {
                        return;
                    }
                    offer(token);
                }

                @Override
                public void onComplete(io.cortexj.stream.StreamCompletion completion) {
                    if (cancelled.get()) {
                        return;
                    }
                    synchronized (lock) {
                        completed.set(true);
                    }
                    drain();
                }

                @Override
                public void onError(CortexJException error) {
                    if (cancelled.get()) {
                        return;
                    }
                    synchronized (lock) {
                        if (upstreamError == null) {
                            upstreamError = error == null
                                    ? new io.cortexj.error.InferenceException(
                                            io.cortexj.ErrorCode.INFERENCE_STREAM_FAILED,
                                            "token stream failed without an error")
                                    : error;
                        }
                    }
                    stream.close();
                    drain();
                }
            });
            upstream.set(sub);
            if (cancelled.get()) {
                sub.cancel();
                stream.close();
            }
        }

        private void rejectNonPositiveRequest(long n) {
            if (cancelled.get()) {
                return;
            }
            if (requestFailureSignalled.compareAndSet(false, true)) {
                synchronized (lock) {
                    requestError = new IllegalArgumentException(
                            "non-positive subscription request: " + n);
                }
                cancelled.set(true);
                StreamSubscription up = upstream.get();
                if (up != null) {
                    up.cancel();
                }
                stream.close();
                drain();
            }
        }

        private void offer(Token token) {
            synchronized (lock) {
                if (!overflowed.get() && buffered.size() >= bufferCapacity) {
                    overflowed.set(true);
                }
                if (!overflowed.get()) {
                    buffered.add(token);
                }
            }
            drain();
        }

        private boolean hasWork() {
            synchronized (lock) {
                if (requestError != null && !errorDelivered) {
                    return true;
                }
                if (cancelled.get()) {
                    return false;
                }
                if (overflowed.get() && !errorDelivered) {
                    return true;
                }
                if (upstreamError != null && !errorDelivered) {
                    return true;
                }
                if (demand.get() > 0 && !buffered.isEmpty()) {
                    return true;
                }
                return completed.get() && buffered.isEmpty() && !completionDelivered;
            }
        }

        private void drain() {
            // audit 2026-10-08 (#153): iterative re-acquire, same tail-call
            // StackOverflow rationale as PushTokenStream.drain()
            if (!draining.compareAndSet(false, true)) {
                // another thread owns dispatch; it will observe our buffered work
                return;
            }
            try {
                dispatchLoop();
            } finally {
                draining.set(false);
            }
            while (hasWork()) {
                if (!draining.compareAndSet(false, true)) {
                    return;
                }
                try {
                    dispatchLoop();
                } finally {
                    draining.set(false);
                }
            }
        }

        private void dispatchLoop() {
            while (true) {
                Token token = null;
                boolean complete = false;
                Throwable reqErrorToDeliver = null;
                io.cortexj.error.CortexJException errorToDeliver = null;
                synchronized (lock) {
                    if (requestError != null && !errorDelivered) {
                        errorDelivered = true;
                        reqErrorToDeliver = requestError;
                    } else if (cancelled.get()) {
                        return;
                    } else if (demand.get() > 0 && !buffered.isEmpty()) {
                        token = buffered.poll();
                        demand.decrementAndGet();
                    } else if (overflowed.get() && !errorDelivered) {
                        errorDelivered = true;
                        errorToDeliver = new io.cortexj.error.InferenceException(
                                io.cortexj.ErrorCode.INFERENCE_STREAM_FAILED,
                                "token stream buffer overflow: the subscriber lagged more than "
                                        + bufferCapacity + " tokens behind the producer; "
                                        + "the generation was cancelled",
                                "Request demand proactively or consume tokens faster; the "
                                        + "adapter never buffers without bound.");
                    } else if (upstreamError != null && !errorDelivered) {
                        errorDelivered = true;
                        errorToDeliver = upstreamError;
                    } else if (completed.get() && buffered.isEmpty() && !completionDelivered) {
                        completionDelivered = true;
                        complete = true;
                    } else {
                        return;
                    }
                }
                if (reqErrorToDeliver != null) {
                    downstream.onError(reqErrorToDeliver);
                    return;
                }
                if (errorToDeliver != null) {
                        // explicit overflow/upstream-failure policy: the generation
                        // is cancelled and the subscriber gets a loud typed error
                        stream.close();
                        if (cancelled.get()) {
                            return;
                        }
                        downstream.onError(errorToDeliver);
                        return;
                    }
                    if (complete) {
                        if (cancelled.get()) {
                            return;
                        }
                        downstream.onComplete();
                        stream.close();
                        return;
                    }
                    if (cancelled.get()) {
                        return;
                    }
                    downstream.onNext(token);
                }
            }
        }
    }