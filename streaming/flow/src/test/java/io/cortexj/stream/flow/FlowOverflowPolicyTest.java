package io.cortexj.stream.flow;

import io.cortexj.inference.FinishReason;
import io.cortexj.inference.TokenUsage;
import io.cortexj.stream.Token;
import io.cortexj.stream.internal.PushTokenStream;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlowOverflowPolicyTest {

    private static PushTokenStream pushStream(final int tokens, final long pauseMillis) {
        final PushTokenStream stream = new PushTokenStream();
        Thread producer = new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < tokens; i++) {
                    stream.emit("t" + i, Collections.<String, String>emptyMap());
                    if (pauseMillis > 0) {
                        try {
                            Thread.sleep(pauseMillis);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
                stream.complete(FinishReason.STOP, new TokenUsage(1L, tokens));
            }
        });
        producer.start();
        return stream;
    }

    @Test
    void zeroDemandSubscriberGetsExplicitErrorInsteadOfUnboundedBuffer() throws Exception {
        PushTokenStream stream = pushStream(2000, 0L);
        final CompletableFuture<Throwable> error = new CompletableFuture<Throwable>();
        final CompletableFuture<Void> done = new CompletableFuture<Void>();

        TokenFlowAdapter.publisher(stream).subscribe(new Flow.Subscriber<Token>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                // no demand is ever requested: the bounded buffer must stop the
                // generation with an explicit error instead of growing forever
            }

            @Override
            public void onNext(Token token) {
            }

            @Override
            public void onComplete() {
                done.complete(null);
            }

            @Override
            public void onError(Throwable failure) {
                error.complete(failure);
            }
        });

        Throwable received = error.get(10, TimeUnit.SECONDS);
        assertTrue(received.getMessage().contains("buffer overflow"),
                "the overflow policy must be an explicit error signal: " + received.getMessage());
        assertTrue(!done.isDone(), "no completion may follow the overflow error");
        producerJoin(stream, 1000);
    }

    @Test
    void overflowErrorsAfterDemandCoveredTokensWithCustomCapacity() throws Exception {
        PushTokenStream stream = pushStream(100, 0L);
        final List<String> received = Collections.synchronizedList(new ArrayList<String>());
        final CompletableFuture<Throwable> error = new CompletableFuture<Throwable>();
        final CompletableFuture<Void> done = new CompletableFuture<Void>();

        TokenFlowAdapter.publisher(stream, 16).subscribe(new Flow.Subscriber<Token>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                this.subscription = subscription;
                subscription.request(5);
            }

            @Override
            public void onNext(Token token) {
                received.add(token.text());
            }

            @Override
            public void onComplete() {
                done.complete(null);
            }

            @Override
            public void onError(Throwable failure) {
                error.complete(failure);
            }
        });

        Throwable failure = error.get(10, TimeUnit.SECONDS);
        assertEquals(5, received.size(),
                "only the demand-covered tokens may be delivered before the overflow error");
        assertTrue(failure.getMessage().contains("buffer overflow"),
                "the overflow must surface as the explicit error: " + failure.getMessage());
        assertTrue(!done.isDone(), "no completion may follow the overflow error");
        producerJoin(stream, 1000);
    }

    @Test
    void boundedBufferDoesNotAffectWellBehavedSubscribers() throws Exception {
        final int tokens = 300;
        PushTokenStream stream = pushStream(tokens, 1L);
        final List<String> received = Collections.synchronizedList(new ArrayList<String>());
        final CompletableFuture<Throwable> error = new CompletableFuture<Throwable>();
        final CompletableFuture<Void> done = new CompletableFuture<Void>();

        TokenFlowAdapter.publisher(stream, 256).subscribe(new Flow.Subscriber<Token>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                this.subscription = subscription;
                subscription.request(10);
            }

            @Override
            public void onNext(Token token) {
                received.add(token.text());
                subscription.request(10);
            }

            @Override
            public void onComplete() {
                done.complete(null);
            }

            @Override
            public void onError(Throwable failure) {
                error.complete(failure);
            }
        });

        done.get(30, TimeUnit.SECONDS);
        assertTrue(error.isDone() ? false : true, "no error for a subscriber keeping up with demand");
        assertEquals(tokens, received.size(), "every token must be delivered");
        for (int i = 0; i < tokens; i++) {
            assertEquals("t" + i, received.get(i), "tokens must arrive strictly in order");
        }
        producerJoin(stream, 1000);
    }

    @Test
    void nonPositiveCapacityIsRejected() {
        PushTokenStream stream = new PushTokenStream();
        try {
            TokenFlowAdapter.publisher(stream, 0);
            org.junit.jupiter.api.Assertions.fail("expected IllegalArgumentException for capacity 0");
        } catch (IllegalArgumentException expected) {
        }
        try {
            TokenFlowAdapter.publisher(stream, -5);
            org.junit.jupiter.api.Assertions.fail("expected IllegalArgumentException for negative capacity");
        } catch (IllegalArgumentException expected) {
        }
    }

    private static void producerJoin(PushTokenStream stream, long millis) throws InterruptedException {
        Thread.sleep(millis);
    }
}
