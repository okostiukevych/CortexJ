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

public final class TokenFlowAdapter implements Flow.Publisher<Token> {

    private final TokenStream stream;

    private TokenFlowAdapter(TokenStream stream) {
        if (stream == null) {
            throw new IllegalArgumentException("token stream must not be null");
        }
        this.stream = stream;
    }

    public static TokenFlowAdapter publisher(TokenStream stream) {
        return new TokenFlowAdapter(stream);
    }

    @Override
    public void subscribe(Flow.Subscriber<? super Token> subscriber) {
        if (subscriber == null) {
            throw new IllegalArgumentException("subscriber must not be null");
        }
        new Bridge(stream, subscriber).start();
    }

    private static final class Bridge {

        private final TokenStream stream;
        private final Flow.Subscriber<? super Token> downstream;
        private final Queue<Token> buffered = new ArrayDeque<Token>();
        private final AtomicLong demand = new AtomicLong();
        private final AtomicBoolean completed = new AtomicBoolean(false);
        private final AtomicBoolean cancelled = new AtomicBoolean(false);
        // Flow contract: onNext/onComplete must be delivered serially, by at most
        // one thread at a time. The drain-owner guard serializes dispatch when the
        // publisher thread (offer/onComplete) and the subscriber thread (request)
        // race into drain() together (audit H-5).
        private final AtomicBoolean draining = new AtomicBoolean(false);
        private boolean completionDelivered;
        private final Object lock = new Object();

        Bridge(TokenStream stream, Flow.Subscriber<? super Token> downstream) {
            this.stream = stream;
            this.downstream = downstream;
        }

        void start() {
            StreamSubscription upstream = stream.subscribe(new TokenConsumer() {
                @Override
                public void onToken(Token token) {
                    offer(token);
                }

                @Override
                public void onComplete(io.cortexj.stream.StreamCompletion completion) {
                    synchronized (lock) {
                        completed.set(true);
                    }
                    drain();
                }

                @Override
                public void onError(CortexJException error) {
                    downstream.onError(error);
                    stream.close();
                }
            });
            downstream.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    if (n <= 0) {
                        downstream.onError(new IllegalArgumentException(
                                "non-positive subscription request: " + n));
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
                    upstream.cancel();
                    stream.close();
                }
            });
        }

        private void offer(Token token) {
            synchronized (lock) {
                buffered.add(token);
            }
            drain();
        }

        private boolean hasWork() {
            synchronized (lock) {
                if (demand.get() > 0 && !buffered.isEmpty()) {
                    return true;
                }
                return completed.get() && buffered.isEmpty() && !completionDelivered;
            }
        }

        private void drain() {
            if (!draining.compareAndSet(false, true)) {
                // another thread owns dispatch; it will observe our buffered work
                return;
            }
            try {
                while (true) {
                    Token token = null;
                    boolean complete = false;
                    synchronized (lock) {
                        if (demand.get() > 0 && !buffered.isEmpty()) {
                            token = buffered.poll();
                            demand.decrementAndGet();
                        } else if (completed.get() && buffered.isEmpty() && !completionDelivered) {
                            completionDelivered = true;
                            complete = true;
                        } else {
                            return;
                        }
                    }
                    if (complete) {
                        downstream.onComplete();
                        stream.close();
                        return;
                    }
                    if (cancelled.get()) {
                        return;
                    }
                    downstream.onNext(token);
                }
            } finally {
                draining.set(false);
                // missed-work re-check: work may have arrived between the last
                // empty check and the release; whoever added it may have returned
                // early because we held the drain guard
                if (hasWork()) {
                    drain();
                }
            }
        }
    }
}
