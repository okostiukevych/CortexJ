package io.cortexj.stream.flow.test;

import io.cortexj.inference.FinishReason;
import io.cortexj.inference.TokenUsage;
import io.cortexj.stream.Token;
import io.cortexj.stream.TokenStream;
import io.cortexj.stream.flow.TokenFlowAdapter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenFlowAdapterTest {

    private static TokenStream asyncStream(CountDownLatch gate) {
        io.cortexj.stream.internal.PushTokenStream stream =
                new io.cortexj.stream.internal.PushTokenStream();
        new Thread(() -> {
            try {
                gate.await();
            } catch (InterruptedException ignored) {
                return;
            }
            stream.emit("one", Map.of());
            stream.emit("two", Map.of());
            stream.emit("three", Map.of());
            stream.complete(FinishReason.STOP, new TokenUsage(1L, 3L));
        }).start();
        return stream;
    }

    @Test
    void flowSubscriberReceivesAllTokensRespectingDemand() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        TokenStream source = asyncStream(gate);
        List<String> received = new ArrayList<String>();
        CompletableFuture<Void> done = new CompletableFuture<Void>();

        TokenFlowAdapter.publisher(source).subscribe(new Flow.Subscriber<Token>() {
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                this.subscription = subscription;
                subscription.request(1);
            }

            @Override
            public void onNext(Token token) {
                received.add(token.text());
                subscription.request(1);
            }

            @Override
            public void onComplete() {
                done.complete(null);
            }

            @Override
            public void onError(Throwable error) {
                done.completeExceptionally(error);
            }
        });
        gate.countDown();
        done.get(10, TimeUnit.SECONDS);
        assertEquals(java.util.Arrays.asList("one", "two", "three"), received);
    }

    @Test
    void lateBulkRequestDrainsBufferAndCompletes() throws Exception {
        CountDownLatch gate = new CountDownLatch(1);
        TokenStream source = asyncStream(gate);
        final List<String> received = new ArrayList<String>();
        CompletableFuture<Void> done = new CompletableFuture<Void>();
        final Flow.Subscription[] handle = new Flow.Subscription[1];

        TokenFlowAdapter.publisher(source).subscribe(new Flow.Subscriber<Token>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                handle[0] = subscription;
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
            public void onError(Throwable error) {
                done.completeExceptionally(error);
            }
        });
        gate.countDown();
        Thread.sleep(200);
        assertTrue(received.isEmpty(), "no demand means no delivery");
        assertTrue(!done.isDone(), "completion must wait until the buffer is drained");

        handle[0].request(3);
        done.get(10, TimeUnit.SECONDS);
        assertEquals(java.util.Arrays.asList("one", "two", "three"), received);
    }

    @Test
    void nonPositiveRequestDeliversIllegalArgumentExceptionToOnError() throws Exception {
        io.cortexj.stream.internal.PushTokenStream source =
                new io.cortexj.stream.internal.PushTokenStream();
        CompletableFuture<Throwable> errorFuture = new CompletableFuture<>();

        TokenFlowAdapter.publisher(source).subscribe(new Flow.Subscriber<Token>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(0);
            }

            @Override
            public void onNext(Token item) {
            }

            @Override
            public void onError(Throwable throwable) {
                errorFuture.complete(throwable);
            }

            @Override
            public void onComplete() {
            }
        });

        Throwable err = errorFuture.get(5, TimeUnit.SECONDS);
        assertTrue(err instanceof IllegalArgumentException);
    }
}
