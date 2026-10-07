package io.cortexj.stream.flow;

import io.cortexj.error.InferenceException;
import io.cortexj.stream.Token;
import io.cortexj.stream.internal.PushTokenStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("concurrency")
class FlowUpstreamErrorSerializationTest {

    @Test
    void upstreamErrorNeverRunsConcurrentlyWithInFlightOnNext() throws Exception {
        final PushTokenStream stream = new PushTokenStream();
        final CountDownLatch onNextEntered = new CountDownLatch(1);
        final CountDownLatch releaseOnNext = new CountDownLatch(1);
        final AtomicInteger inFlight = new AtomicInteger();
        final AtomicInteger serialViolations = new AtomicInteger();
        final AtomicReference<Throwable> downstreamFailure = new AtomicReference<Throwable>();
        final CountDownLatch finished = new CountDownLatch(1);

        TokenFlowAdapter.publisher(stream).subscribe(new Flow.Subscriber<Token>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                this.subscription = subscription;
                this.subscription.request(1);
            }

            @Override
            public void onNext(Token token) {
                if (inFlight.incrementAndGet() != 1) {
                    serialViolations.incrementAndGet();
                }
                try {
                    onNextEntered.countDown();
                    releaseOnNext.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    inFlight.decrementAndGet();
                }
            }

            @Override
            public void onError(Throwable throwable) {
                if (inFlight.incrementAndGet() != 1) {
                    serialViolations.incrementAndGet();
                }
                try {
                    downstreamFailure.set(throwable);
                } finally {
                    inFlight.decrementAndGet();
                    finished.countDown();
                }
            }

            @Override
            public void onComplete() {
                finished.countDown();
            }
        });

        Thread emitter = new Thread(new Runnable() {
            @Override
            public void run() {
                // the emitter thread becomes the drainer and blocks inside
                // onNext, holding the drain guard
                stream.emit("token-0", null);
            }
        });
        emitter.start();
        assertTrue(onNextEntered.await(10, TimeUnit.SECONDS),
                "the drainer must be blocked inside onNext holding the drain guard");

        // audit #93: the upstream failure arrives on the producer thread while
        // the subscriber is still inside onNext - the adapter must queue the
        // error behind the drain guard, not enter onError concurrently
        stream.fail(new InferenceException(io.cortexj.ErrorCode.INFERENCE_STREAM_FAILED,
                "simulated upstream failure"));
        Thread.sleep(200);

        releaseOnNext.countDown();
        assertTrue(finished.await(10, TimeUnit.SECONDS),
                "the upstream error must be delivered once onNext finishes");
        emitter.join(10_000);
        assertEquals(0, serialViolations.get(),
                "audit #93: upstream onError must never run concurrently with onNext "
                        + "(Flow rule 1.3 serial-signal violation)");
        assertNotNull(downstreamFailure.get(), "the subscriber must receive the upstream failure");
        assertTrue(downstreamFailure.get() instanceof InferenceException,
                "the original typed error must be preserved, got: " + downstreamFailure.get());
    }

    @Test
    void bufferedTokensDrainBeforeTheUpstreamErrorArrives() throws Exception {
        final PushTokenStream stream = new PushTokenStream();
        final java.util.List<String> delivered = new java.util.concurrent.CopyOnWriteArrayList<String>();
        final CountDownLatch finished = new CountDownLatch(1);
        final AtomicReference<Throwable> downstreamFailure = new AtomicReference<Throwable>();

        TokenFlowAdapter.publisher(stream).subscribe(new Flow.Subscriber<Token>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                this.subscription = subscription;
                this.subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(Token token) {
                delivered.add(token.text());
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

        stream.emit("a", null);
        stream.emit("b", null);
        stream.fail(new InferenceException(io.cortexj.ErrorCode.INFERENCE_STREAM_FAILED,
                "simulated upstream failure"));

        assertTrue(finished.await(10, TimeUnit.SECONDS), "the error terminal must arrive");
        assertNotNull(downstreamFailure.get(), "the failure must be delivered");
        assertEquals(java.util.Arrays.asList("a", "b"), delivered,
                "tokens already buffered must be delivered before the upstream error");
    }
}
