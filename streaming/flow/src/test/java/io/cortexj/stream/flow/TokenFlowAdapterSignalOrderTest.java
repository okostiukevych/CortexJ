package io.cortexj.stream.flow;

import io.cortexj.error.InferenceException;
import io.cortexj.inference.FinishReason;
import io.cortexj.stream.Token;
import io.cortexj.stream.TokenStream;
import io.cortexj.stream.TokenStreams;
import io.cortexj.stream.flow.TokenFlowAdapter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Signal-order regression tests (GitHub #79): onSubscribe must be the FIRST
 * callback on the downstream (Flow rule 1.9), even for synchronous upstreams
 * (TokenStreams.single/failed, a replaying PushTokenStream). A non-positive
 * request must produce exactly ONE onError, then the bridge stops delivering.
 */
class TokenFlowAdapterSignalOrderTest {

    private static final class OrderRecorder implements Flow.Subscriber<Token> {

        final List<String> events = new ArrayList<String>();
        private final boolean requestNegativeOnSubscribe;
        private Flow.Subscription subscription;

        OrderRecorder(boolean requestNegativeOnSubscribe) {
            this.requestNegativeOnSubscribe = requestNegativeOnSubscribe;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            events.add("onSubscribe");
            this.subscription = subscription;
            if (requestNegativeOnSubscribe) {
                subscription.request(-1);
                subscription.request(-1);
            } else {
                subscription.request(1);
            }
        }

        @Override
        public void onNext(Token token) {
            events.add("onNext");
            if (!requestNegativeOnSubscribe) {
                subscription.request(1);
            }
        }

        @Override
        public void onComplete() {
            events.add("onComplete");
        }

        @Override
        public void onError(Throwable error) {
            events.add("onError");
        }
    }

    @Test
    void onSubscribeIsFirstCallbackForSynchronousSingleStream() {
        OrderRecorder recorder = new OrderRecorder(false);
        TokenFlowAdapter.publisher(TokenStreams.single("hi")).subscribe(recorder);
        assertEquals(Arrays.asList("onSubscribe", "onNext", "onComplete"), recorder.events,
                "onSubscribe must be the FIRST callback (Flow rule 1.9, GitHub #79)");
    }

    @Test
    void onSubscribeIsFirstCallbackForSynchronousFailedStream() {
        OrderRecorder recorder = new OrderRecorder(false);
        TokenFlowAdapter.publisher(TokenStreams.failed(
                new InferenceException("CORTEXJ-INFER-9001", "boom"))).subscribe(recorder);
        assertEquals(Arrays.asList("onSubscribe", "onError"), recorder.events,
                "onSubscribe must be the FIRST callback (Flow rule 1.9, GitHub #79)");
    }

    @Test
    void onSubscribeIsFirstCallbackForReplayingPushTokenStream() {
        io.cortexj.stream.internal.PushTokenStream stream =
                new io.cortexj.stream.internal.PushTokenStream();
        stream.emit("a", null);
        stream.emit("b", null);
        stream.emit("c", null);
        stream.complete(FinishReason.STOP, null);

        OrderRecorder recorder = new OrderRecorder(false);
        TokenFlowAdapter.publisher(stream).subscribe(recorder);
        assertEquals(Arrays.asList("onSubscribe", "onNext", "onNext", "onNext", "onComplete"),
                recorder.events,
                "replayed signals must not overtake onSubscribe (Flow rule 1.9, GitHub #79)");
    }

    @Test
    void requestNonPositiveInsideOnSubscribeYieldsExactlyOneOnError() {
        OrderRecorder recorder = new OrderRecorder(true);
        TokenFlowAdapter.publisher(TokenStreams.single("hi")).subscribe(recorder);
        assertEquals(Arrays.asList("onSubscribe", "onError"), recorder.events,
                "exactly ONE onError for the non-positive request (Flow rule 3.9, GitHub #79), "
                        + "no onNext/onComplete may follow");
    }

    @Test
    void requestNonPositiveAfterOnSubscribeYieldsExactlyOneOnErrorAndStopsDelivery() {
        io.cortexj.stream.internal.PushTokenStream stream =
                new io.cortexj.stream.internal.PushTokenStream();
        final List<String> events = new ArrayList<String>();
        final Flow.Subscription[] handle = new Flow.Subscription[1];

        TokenFlowAdapter.publisher(stream).subscribe(new Flow.Subscriber<Token>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                events.add("onSubscribe");
                handle[0] = subscription;
                subscription.request(1);
            }

            @Override
            public void onNext(Token token) {
                events.add("onNext");
            }

            @Override
            public void onComplete() {
                events.add("onComplete");
            }

            @Override
            public void onError(Throwable error) {
                events.add("onError");
            }
        });

        assertEquals(Arrays.asList("onSubscribe"), events, "no demand means no delivery yet");

        handle[0].request(-1);
        handle[0].request(-1);

        assertEquals(Arrays.asList("onSubscribe", "onError"), events,
                "exactly ONE onError for the non-positive request (Flow rule 3.9, GitHub #79)");

        // then stop: upstream signals after the failure are not delivered
        stream.emit("late", null);
        stream.complete(FinishReason.STOP, null);

        assertEquals(Arrays.asList("onSubscribe", "onError"), events,
                "no further signal may follow the non-positive request error");
    }
}
