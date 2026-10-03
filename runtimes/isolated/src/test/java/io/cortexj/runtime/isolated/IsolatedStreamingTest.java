package io.cortexj.runtime.isolated;

import io.cortexj.error.CortexJException;
import io.cortexj.inference.GenerationRequest;
import io.cortexj.inference.InferenceRequest;
import io.cortexj.inference.TextGenerationCapability;
import io.cortexj.spi.runtime.ModelRuntime;
import io.cortexj.spi.runtime.RuntimeModel;
import io.cortexj.stream.StreamCompletion;
import io.cortexj.stream.StreamSubscription;
import io.cortexj.stream.TokenStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Streaming through the process boundary: protocol token events are pumped
 * into the parent-side PushTokenStream consumer in order; cancelling the
 * subscription stops the pump, tells the worker to abort the upstream
 * stream, and leaves the child alive and usable.
 */
class IsolatedStreamingTest {

    private static IsolatedRuntimeProvider provider;
    private static Path artifact;
    private static ModelRuntime runtime;

    @BeforeAll
    static void setUp() throws Exception {
        provider = new IsolatedRuntimeProvider("stub");
        artifact = Files.createTempFile("isolated-stream-model", ".onnx");
        Files.write(artifact, "stub".getBytes(StandardCharsets.UTF_8));
        runtime = provider.open(IsolatedProviderRoundTripTest.openRequest());
    }

    @AfterAll
    static void tearDown() {
        runtime.close();
        provider.close();
    }

    @Test
    void tokensArriveInOrderThroughTheParentSideStream() throws Exception {
        RuntimeModel model = runtime.load(IsolatedProviderRoundTripTest.loadRequest(artifact));
        assertTrue(model instanceof TextGenerationCapability);
        TextGenerationCapability generator = (TextGenerationCapability) model;

        TokenStream stream = generator.stream(GenerationRequest.prompt("hi"));
        final List<String> tokens = new CopyOnWriteArrayList<String>();
        final CountDownLatch completed = new CountDownLatch(1);
        final AtomicBoolean errored = new AtomicBoolean(false);
        final java.util.concurrent.atomic.AtomicReference<StreamCompletion> completion =
                new java.util.concurrent.atomic.AtomicReference<StreamCompletion>();
        stream.subscribe(new io.cortexj.stream.TokenConsumer() {
            @Override
            public void onToken(io.cortexj.stream.Token token) {
                tokens.add(token.text());
            }

            @Override
            public void onComplete(StreamCompletion finished) {
                completion.set(finished);
                completed.countDown();
            }

            @Override
            public void onError(CortexJException error) {
                errored.set(true);
                completed.countDown();
            }
        });

        assertTrue(completed.await(20, TimeUnit.SECONDS), "the stream must complete");
        assertFalse(errored.get(), "the stream must not error");
        assertEquals(java.util.Arrays.asList("t0", "t1", "t2"), tokens,
                "tokens must arrive in order across the process boundary");
        assertEquals(io.cortexj.inference.FinishReason.STOP, completion.get().finishReason());
        assertTrue(completion.get().usage().isKnown());
        assertEquals(3, completion.get().usage().outputTokens());
    }

    @Test
    void cancelStopsThePumpAndKeepsTheChildUsable() throws Exception {
        RuntimeModel model = runtime.load(IsolatedProviderRoundTripTest.loadRequest(artifact));
        TextGenerationCapability generator = (TextGenerationCapability) model;

        // 50 tokens x 100 ms: long enough to cancel mid-stream
        TokenStream stream = generator.stream(GenerationRequest.builder()
                .prompt("hi").maxTokens(50).build());
        final List<String> tokens = new CopyOnWriteArrayList<String>();
        final CountDownLatch firstToken = new CountDownLatch(1);
        final AtomicBoolean completed = new AtomicBoolean(false);
        final AtomicBoolean errored = new AtomicBoolean(false);
        StreamSubscription subscription = stream.subscribe(new io.cortexj.stream.TokenConsumer() {
            @Override
            public void onToken(io.cortexj.stream.Token token) {
                tokens.add(token.text());
                firstToken.countDown();
            }

            @Override
            public void onComplete(StreamCompletion finished) {
                completed.set(true);
            }

            @Override
            public void onError(CortexJException error) {
                errored.set(true);
            }
        });

        assertTrue(firstToken.await(20, TimeUnit.SECONDS), "the first token must arrive");
        subscription.cancel();

        int atCancel = tokens.size();
        Thread.sleep(700L); // the emitter pace is 100 ms/token: ~7 missed tokens if cancel were ignored
        assertEquals(atCancel, tokens.size(), "no tokens may arrive after cancel");
        assertFalse(completed.get(), "a cancelled stream must not deliver a completion");
        assertFalse(errored.get(), "a cancelled stream must not deliver an error");

        // the child is still alive and the model still executes
        assertEquals("stub:after-cancel",
                model.execute(InferenceRequest.builder().input("after-cancel").build()).singleOutput());
        subscription.cancel(); // second cancel is a no-op

        // a subsequent stream still works on the same child
        TokenStream next = generator.stream(GenerationRequest.prompt("again"));
        final CountDownLatch nextDone = new CountDownLatch(1);
        final List<String> nextTokens = new CopyOnWriteArrayList<String>();
        next.subscribe(new io.cortexj.stream.TokenConsumer() {
            @Override
            public void onToken(io.cortexj.stream.Token token) {
                nextTokens.add(token.text());
            }

            @Override
            public void onComplete(StreamCompletion finished) {
                nextDone.countDown();
            }

            @Override
            public void onError(CortexJException error) {
                nextDone.countDown();
            }
        });
        assertTrue(nextDone.await(20, TimeUnit.SECONDS), "streams must keep working after a cancel");
        assertEquals(java.util.Arrays.asList("t0", "t1", "t2"), nextTokens);
    }
}
