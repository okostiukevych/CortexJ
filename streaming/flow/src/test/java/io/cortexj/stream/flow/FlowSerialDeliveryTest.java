package io.cortexj.stream.flow;

import io.cortexj.stream.Token;
import io.cortexj.stream.internal.PushTokenStream;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Tag;

@Tag("concurrency")
class FlowSerialDeliveryTest {

    @Test
    void onNextIsSerialAndOrderedUnderConcurrentRequestAndEmit() throws Exception {
        final PushTokenStream stream = new PushTokenStream();
        final int totalTokens = 400;

        final List<Integer> deliveredIndexes = new CopyOnWriteArrayList<Integer>();
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger serialViolations = new AtomicInteger();
        final AtomicReference<Throwable> downstreamFailure = new AtomicReference<Throwable>();
        final CountDownLatch finished = new CountDownLatch(1);

        TokenFlowAdapter.publisher(stream).subscribe(new Flow.Subscriber<Token>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                this.subscription = subscription;
                // the subscriber thread pumps demand concurrently with the producer
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        int requested = 0;
                        while (requested < totalTokens && downstreamFailure.get() == null) {
                            subscription.request(1);
                            requested++;
                        }
                    }
                }).start();
            }

            @Override
            public void onNext(Token token) {
                if (inFlight.incrementAndGet() != 1) {
                    serialViolations.incrementAndGet();
                }
                try {
                    deliveredIndexes.add(token.index());
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    inFlight.decrementAndGet();
                }
            }

            @Override
            public void onError(Throwable throwable) {
                downstreamFailure.set(throwable);
                finished.countDown();
            }

            @Override
            public void onComplete() {
                finished.countDown();
            }
        });

        Thread producer = new Thread(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < totalTokens; i++) {
                    stream.emit("token-" + i, null);
                }
                stream.complete(io.cortexj.inference.FinishReason.STOP, null);
            }
        });
        producer.start();

        assertTrue(finished.await(30, TimeUnit.SECONDS), "the flow must complete");
        producer.join(10_000);
        assertEquals(null, downstreamFailure.get(), "no downstream error expected");
        assertEquals(0, serialViolations.get(),
                "Flow contract violation: concurrent onNext deliveries");
        assertEquals(totalTokens, deliveredIndexes.size(), "every token must be delivered");
        for (int i = 0; i < deliveredIndexes.size(); i++) {
            assertEquals(Integer.valueOf(i), deliveredIndexes.get(i),
                    "tokens must be delivered in emission order - got "
                            + deliveredIndexes.subList(0, Math.min(10, deliveredIndexes.size()))
                            + "...");
        }
    }

    @Test
    void requestOverflowSaturatesAtMaxValueInsteadOfWrappingNegative() throws Exception {
        final PushTokenStream stream = new PushTokenStream();
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicReference<Throwable> downstreamFailure = new AtomicReference<Throwable>();

        TokenFlowAdapter.publisher(stream).subscribe(new Flow.Subscriber<Token>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                this.subscription = subscription;
                this.subscription.request(Long.MAX_VALUE / 2);
                this.subscription.request(Long.MAX_VALUE / 2);
                this.subscription.request(5);
            }

            @Override
            public void onNext(Token token) {
            }

            @Override
            public void onError(Throwable throwable) {
                downstreamFailure.set(throwable);
                finished.countDown();
            }

            @Override
            public void onComplete() {
                finished.countDown();
            }
        });
        stream.emit("t1", null);
        stream.complete(io.cortexj.inference.FinishReason.STOP, null);

        assertTrue(finished.await(10, TimeUnit.SECONDS),
                "the flow must still deliver after demand saturation");
        assertEquals(null, downstreamFailure.get(),
                "demand overflow must saturate, not wrap negative and stall the flow");
    }
}
