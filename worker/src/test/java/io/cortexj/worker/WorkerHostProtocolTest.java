package io.cortexj.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.cortexj.error.CortexJException;
import io.cortexj.worker.teststub.EchoStubProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * In-process protocol tests: the worker serve() loop is driven through pipes,
 * so every request/response and stream event is asserted at the wire level.
 */
class WorkerHostProtocolTest {

    private static final long POLL_MILLIS = 10_000L;

    private PrintWriter requests;
    private BlockingQueue<String> responses;
    private Thread serveThread;
    private volatile IOException serveFailure;
    private Path artifact;

    @BeforeEach
    void startWorker() throws IOException {
        artifact = Files.createTempFile("worker-stub-model", ".onnx");
        Files.write(artifact, "stub".getBytes(StandardCharsets.UTF_8));
        final PipedOutputStream requestPipe = new PipedOutputStream();
        PipedInputStream requestStream = new PipedInputStream(requestPipe, 64 * 1024);
        final CollectingSink sink = new CollectingSink();
        requests = new PrintWriter(requestPipe);
        serveThread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    new WorkerHost(new EchoStubProvider(), sink).serve(new BufferedReader(
                            new InputStreamReader(requestStream, StandardCharsets.UTF_8)));
                } catch (IOException e) {
                    serveFailure = e;
                }
            }
        }, "worker-serve-test");
        serveThread.setDaemon(true);
        serveThread.start();
        responses = sink.lines;
    }

    @AfterEach
    void stopWorker() throws Exception {
        requests.close();
        serveThread.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(serveThread.isAlive(), "serve() must return after stdin EOF");
        assertEquals(null, serveFailure, "serve() must not fail on EOF");
        Files.deleteIfExists(artifact);
    }

    private static ObjectNode op(long id, String name) {
        ObjectNode node = WorkerCodec.object();
        node.put("id", id);
        node.put("op", name);
        return node;
    }

    /** Sends a raw line and returns the next line the worker emits. */
    private String sendRaw(String json) {
        requests.println(json);
        requests.flush();
        return nextLine();
    }

    /**
     * Sends a request and waits for the RESPONSE with the same id, skipping
     * (and recording) any stream events that interleave ahead of it.
     */
    private JsonNode request(ObjectNode payload) {
        long id = payload.path("id").asLong();
        requests.println(payload.toString());
        requests.flush();
        long deadline = System.currentTimeMillis() + POLL_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            JsonNode line = parse(nextLine());
            if (line.has("event")) {
                continue;
            }
            assertEquals(id, line.path("id").asLong(), "response id must echo the request id");
            return line;
        }
        throw new AssertionError("no response with id " + id + " within " + POLL_MILLIS + " ms");
    }

    private String nextLine() {
        try {
            String line = responses.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
            assertNotNull(line, "worker did not answer within " + POLL_MILLIS + " ms");
            return line;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while waiting for worker response");
        }
    }

    private JsonNode parse(String line) {
        try {
            return WorkerCodec.mapper().readTree(line);
        } catch (IOException e) {
            throw new AssertionError("worker emitted a non-JSON line: " + line, e);
        }
    }

    @Test
    void probeReturnsDescriptorAndAvailability() {
        JsonNode reply = request(op(11, "probe"));
        assertTrue(reply.path("ok").asBoolean());
        assertTrue(reply.path("available").asBoolean());
        assertEquals("AVAILABLE", reply.path("health").asText());
        assertEquals("worker-stub", reply.path("descriptor").path("runtimeId").asText());
        assertTrue(reply.path("descriptor").path("formats").toString().contains("ONNX"));
        assertTrue(reply.path("message").asText().contains("stub"));
    }

    @Test
    @org.junit.jupiter.api.Tag("concurrency")
    void controlOpsAreAnsweredWhileABlockingOpRuns() throws Exception {
        // audit #96: cancelStream/shutdown must never queue behind a long op
        assertTrue(request(openPayload(31)).path("runtimeOpened").asBoolean());
        String token = request(loadPayload(32)).path("modelToken").asText();

        ObjectNode slowExecute = op(33, "execute");
        slowExecute.put("modelToken", token);
        slowExecute.set("request", WorkerCodec.inferenceRequest(
                io.cortexj.inference.InferenceRequest.builder()
                        .input("hello").parameter("slow", Boolean.TRUE).build()));
        requests.println(slowExecute.toString());
        requests.flush();

        // the slow execute blocks a worker op-pool thread for 60s; the control
        // op sent right after must be answered within seconds on the reader
        // thread, not after the blocking op completes
        ObjectNode cancel = op(34, "cancelStream");
        cancel.put("streamId", 999L);
        requests.println(cancel.toString());
        requests.flush();

        long deadline = System.currentTimeMillis() + 3_000;
        boolean answered = false;
        while (System.currentTimeMillis() < deadline) {
            String line = responses.poll(200, TimeUnit.MILLISECONDS);
            if (line == null) {
                continue;
            }
            JsonNode node = parse(line);
            if (node.has("event")) {
                continue;
            }
            assertEquals(34L, node.path("id").asLong(),
                    "the cancelStream reply must not be preceded by the still-blocked execute");
            assertTrue(node.path("ok").asBoolean(),
                    "cancelStream for an unknown stream must be answered ok (idempotent)");
            answered = true;
            break;
        }
        assertTrue(answered,
                "audit #96: the control op must be answered while the blocking "
                        + "execute is still running (it would previously queue "
                        + "behind it for the full 60s)");
        assertEquals(null, responses.poll(300, TimeUnit.MILLISECONDS),
                "the slow execute must still be blocked at this point");
    }

    @Test
    void providerLookupFindsRegisteredRuntimeId() {
        assertEquals("worker-stub", WorkerHost.findProvider("Worker-Stub").id().value());
        assertThrows(CortexJException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                WorkerHost.findProvider("no-such-runtime");
            }
        }, "an unregistered runtime id must fail with a typed error");
    }

    @Test
    void openLoadExecuteCloseModelRoundTripEchoesIds() {
        assertTrue(request(openPayload(21)).path("runtimeOpened").asBoolean());
        JsonNode load = request(loadPayload(22));
        assertTrue(load.path("ok").asBoolean());
        String token = load.path("modelToken").asText();
        assertFalse(token.isEmpty(), "load must return a model token");
        assertTrue(load.path("textGeneration").asBoolean());
        assertTrue(load.path("embedding").asBoolean());
        assertTrue(load.path("capabilities").path("streamingSupported").asBoolean());

        ObjectNode execute = op(23, "execute");
        execute.put("modelToken", token);
        execute.set("request", WorkerCodec.inferenceRequest(
                io.cortexj.inference.InferenceRequest.builder().input("hello").input("world").build()));
        JsonNode executed = request(execute);
        assertTrue(executed.path("ok").asBoolean());
        assertEquals("stub:hello", executed.path("response").path("outputs").get(0).asText());
        assertEquals("stub:world", executed.path("response").path("outputs").get(1).asText());
        assertTrue(executed.path("response").path("metadata").path("stub").asBoolean());

        ObjectNode closeModel = op(24, "closeModel");
        closeModel.put("modelToken", token);
        assertTrue(request(closeModel).path("ok").asBoolean());

        ObjectNode afterClose = op(25, "execute");
        afterClose.put("modelToken", token);
        afterClose.set("request", WorkerCodec.inferenceRequest(
                io.cortexj.inference.InferenceRequest.builder().input("hello").build()));
        JsonNode failed = request(afterClose);
        assertFalse(failed.path("ok").asBoolean());
        assertTrue(failed.path("error").path("code").asText().startsWith("CORTEXJ-LOAD"));
    }

    @Test
    void generateAndEmbedRoundTrip() {
        assertTrue(request(openPayload(30)).path("runtimeOpened").asBoolean());
        String token = request(loadPayload(31)).path("modelToken").asText();

        ObjectNode generate = op(32, "generate");
        generate.put("modelToken", token);
        generate.set("request", WorkerCodec.generationRequest(
                io.cortexj.inference.GenerationRequest.prompt("hi")));
        JsonNode generated = request(generate);
        assertTrue(generated.path("ok").asBoolean());
        assertEquals("stub-generated:hi", generated.path("response").path("text").asText());
        assertEquals("STOP", generated.path("response").path("finishReason").asText());
        assertEquals(2, generated.path("response").path("usage").path("inputTokens").asLong());

        ObjectNode embed = op(33, "embed");
        embed.put("modelToken", token);
        embed.set("request", WorkerCodec.embeddingRequest(
                io.cortexj.inference.EmbeddingRequest.texts(Arrays.asList("a", "bb"))));
        JsonNode embedded = request(embed);
        assertTrue(embedded.path("ok").asBoolean());
        assertEquals(2, embedded.path("response").path("vectors").size());
        assertEquals(1.0, embedded.path("response").path("vectors").get(1).get(1).asDouble(), 0.0001);
    }

    @Test
    void streamEmitsOrderedTokenEventsThenComplete() {
        assertTrue(request(openPayload(40)).path("runtimeOpened").asBoolean());
        String token = request(loadPayload(41)).path("modelToken").asText();

        ObjectNode stream = op(42, "stream");
        stream.put("modelToken", token);
        stream.set("request", WorkerCodec.generationRequest(
                io.cortexj.inference.GenerationRequest.prompt("hi")));
        requests.println(stream.toString());
        requests.flush();

        // token events interleave with (and may precede) the ok response
        List<String> texts = new ArrayList<String>();
        String completion = null;
        boolean sawOk = false;
        int guard = 0;
        while ((completion == null || !sawOk) && guard < 50) {
            JsonNode line = parse(nextLine());
            if ("token".equals(line.path("event").asText())) {
                assertEquals(42, line.path("id").asLong());
                texts.add(line.path("text").asText());
            } else if ("complete".equals(line.path("event").asText())) {
                assertEquals(42, line.path("id").asLong());
                completion = line.path("finishReason").asText();
                assertEquals(3, line.path("usage").path("outputTokens").asLong());
            } else {
                assertEquals(42, line.path("id").asLong());
                assertTrue(line.path("ok").asBoolean());
                sawOk = true;
            }
            guard++;
        }
        assertTrue(sawOk, "stream op must be acknowledged");
        assertEquals(Arrays.asList("t0", "t1", "t2"), texts);
        assertEquals("STOP", completion);
    }

    @Test
    void cancelStreamIsIdempotentAndStopsEvents() throws InterruptedException {
        assertTrue(request(openPayload(50)).path("runtimeOpened").asBoolean());
        String token = request(loadPayload(51)).path("modelToken").asText();

        ObjectNode stream = op(52, "stream");
        stream.put("modelToken", token);
        ObjectNode request52 = WorkerCodec.generationRequest(
                io.cortexj.inference.GenerationRequest.prompt("hi"));
        request52.put("maxTokens", 50);
        stream.set("request", request52);
        assertTrue(request(stream).path("ok").asBoolean());

        boolean sawToken = false;
        while (!sawToken) {
            JsonNode line = parse(nextLine());
            sawToken = "token".equals(line.path("event").asText());
        }

        ObjectNode cancel = op(53, "cancelStream");
        cancel.put("streamId", 52);
        assertTrue(request(cancel).path("ok").asBoolean());
        ObjectNode cancelAgain = op(54, "cancelStream");
        cancelAgain.put("streamId", 52);
        assertTrue(request(cancelAgain).path("ok").asBoolean());

        assertNullEventWindow(400L);
    }

    @Test
    void malformedLineYieldsTypedErrorWithSameIdAndLoopSurvives() {
        // unparseable line: the id is unrecoverable, so the error carries id 0
        JsonNode malformed = parse(sendRaw("{this is not json"));
        assertEquals(0, malformed.path("id").asLong());
        assertFalse(malformed.path("ok").asBoolean());
        assertTrue(malformed.path("error").path("code").asText().startsWith("CORTEXJ-"));

        // parseable object with a missing op: the id is echoed on the error
        JsonNode missingOp = parse(sendRaw("{\"id\":61}"));
        assertEquals(61, missingOp.path("id").asLong());
        assertFalse(missingOp.path("ok").asBoolean());

        // the loop survives both
        assertTrue(request(op(62, "probe")).path("ok").asBoolean());
    }

    @Test
    void unknownOpYieldsTypedError() {
        JsonNode reply = request(op(70, "warp"));
        assertFalse(reply.path("ok").asBoolean());
        assertTrue(reply.path("error").path("message").asText().contains("warp"));
    }

    @Test
    void loadWithoutOpenYieldsTypedError() {
        ObjectNode load = op(80, "load");
        load.set("descriptor", WorkerCodec.modelDescriptor(
                io.cortexj.model.ModelDescriptor.builder("x/model").build()));
        load.put("artifactPath", "whatever");
        JsonNode reply = request(load);
        assertFalse(reply.path("ok").asBoolean());
        assertTrue(reply.path("error").path("code").asText().startsWith("CORTEXJ-RUNTIME"));
    }

    @Test
    void openTwiceYieldsTypedErrorAndCloseReopens() {
        assertTrue(request(openPayload(90)).path("runtimeOpened").asBoolean());
        ObjectNode second = op(91, "open");
        second.set("descriptor", WorkerCodec.modelDescriptor(
                io.cortexj.model.ModelDescriptor.builder("x/model").build()));
        assertFalse(request(second).path("ok").asBoolean());

        JsonNode closed = request(op(92, "close"));
        assertTrue(closed.path("ok").asBoolean());
        assertTrue(closed.path("runtimeClosed").asBoolean());

        ObjectNode reopened = op(93, "open");
        reopened.set("descriptor", WorkerCodec.modelDescriptor(
                io.cortexj.model.ModelDescriptor.builder("x/model").build()));
        assertTrue(request(reopened).path("runtimeOpened").asBoolean());
    }

    @Test
    void shutdownRespondsOkAndEndsLoop() throws Exception {
        JsonNode reply = request(op(95, "shutdown"));
        assertTrue(reply.path("ok").asBoolean());
        serveThread.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(serveThread.isAlive(), "shutdown must end the serve loop");
    }

    @Test
    void cancelStreamInvokesSubscriptionCancelOnSubscribedStream() throws Exception {
        final java.util.concurrent.atomic.AtomicBoolean subscriptionCancelled =
                new java.util.concurrent.atomic.AtomicBoolean(false);
        io.cortexj.spi.runtime.RuntimeProvider customProvider = new EchoStubProvider() {
            @Override
            public io.cortexj.spi.runtime.ModelRuntime open(io.cortexj.spi.runtime.RuntimeOpenRequest req) {
                final io.cortexj.spi.runtime.ModelRuntime delegate = super.open(req);
                return new io.cortexj.spi.runtime.ModelRuntime() {
                    @Override
                    public io.cortexj.runtime.RuntimeId runtimeId() {
                        return delegate.runtimeId();
                    }

                    @Override
                    public io.cortexj.spi.runtime.RuntimeModel load(io.cortexj.spi.runtime.RuntimeModelRequest loadReq) {
                        final io.cortexj.spi.runtime.RuntimeModel baseModel = delegate.load(loadReq);
                        return new SubCancellingModel(baseModel, subscriptionCancelled);
                    }

                    @Override
                    public void close() {
                        delegate.close();
                    }
                };
            }
        };
        PipedOutputStream customPipe = new PipedOutputStream();
        PipedInputStream customIn = new PipedInputStream(customPipe, 64 * 1024);
        CollectingSink customSink = new CollectingSink();
        final WorkerHost host = new WorkerHost(customProvider, customSink);
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    host.serve(new BufferedReader(new InputStreamReader(customIn, StandardCharsets.UTF_8)));
                } catch (IOException ignored) {
                }
            }
        });
        t.setDaemon(true);
        t.start();
        PrintWriter customWriter = new PrintWriter(customPipe);
        try {
            customWriter.println(openPayload(201).toString());
            customWriter.flush();
            assertTrue(parse(customSink.lines.poll(POLL_MILLIS, TimeUnit.MILLISECONDS)).path("ok").asBoolean());

            customWriter.println(loadPayload(202).toString());
            customWriter.flush();
            String token = parse(customSink.lines.poll(POLL_MILLIS, TimeUnit.MILLISECONDS)).path("modelToken").asText();

            ObjectNode streamOp = op(203, "stream");
            streamOp.put("modelToken", token);
            streamOp.set("request", WorkerCodec.generationRequest(
                    io.cortexj.inference.GenerationRequest.prompt("hi")));
            customWriter.println(streamOp.toString());
            customWriter.flush();
            assertTrue(parse(customSink.lines.poll(POLL_MILLIS, TimeUnit.MILLISECONDS)).path("ok").asBoolean());

            ObjectNode cancelOp = op(204, "cancelStream");
            cancelOp.put("streamId", 203L);
            customWriter.println(cancelOp.toString());
            customWriter.flush();
            assertTrue(parse(customSink.lines.poll(POLL_MILLIS, TimeUnit.MILLISECONDS)).path("ok").asBoolean());
            assertTrue(subscriptionCancelled.get(),
                    "cancelStream must invoke StreamSubscription.cancel() on the active subscription");
        } finally {
            customWriter.close();
            t.join(5_000L);
        }
    }

    private static final class SubCancellingModel
            implements io.cortexj.spi.runtime.RuntimeModel, io.cortexj.inference.TextGenerationCapability {
        private final io.cortexj.spi.runtime.RuntimeModel base;
        private final java.util.concurrent.atomic.AtomicBoolean subscriptionCancelled;

        SubCancellingModel(io.cortexj.spi.runtime.RuntimeModel base,
                           java.util.concurrent.atomic.AtomicBoolean subscriptionCancelled) {
            this.base = base;
            this.subscriptionCancelled = subscriptionCancelled;
        }

        @Override
        public io.cortexj.model.ModelCapabilities capabilities() {
            return base.capabilities();
        }

        @Override
        public io.cortexj.inference.InferenceResponse execute(io.cortexj.inference.InferenceRequest request) {
            return base.execute(request);
        }

        @Override
        public io.cortexj.inference.GenerationResponse generate(io.cortexj.inference.GenerationRequest request) {
            return ((io.cortexj.inference.TextGenerationCapability) base).generate(request);
        }

        @Override
        public io.cortexj.stream.TokenStream stream(io.cortexj.inference.GenerationRequest request) {
            return new io.cortexj.stream.TokenStream() {
                @Override
                public io.cortexj.stream.StreamSubscription subscribe(io.cortexj.stream.TokenConsumer consumer) {
                    return new io.cortexj.stream.StreamSubscription() {
                        @Override
                        public void cancel() {
                            subscriptionCancelled.set(true);
                        }

                        @Override
                        public boolean isCancelled() {
                            return subscriptionCancelled.get();
                        }
                    };
                }

                @Override
                public void close() {
                    // intentionally a no-op: cancellation lives on the subscription
                }
            };
        }

        @Override
        public java.util.concurrent.CompletionStage<io.cortexj.inference.InferenceResponse> executeAsync(
                io.cortexj.inference.InferenceRequest request) {
            return base.executeAsync(request);
        }

        @Override
        public void close() {
            base.close();
        }
    }

    private ObjectNode openPayload(long id) {
        ObjectNode open = op(id, "open");
        open.set("descriptor", WorkerCodec.modelDescriptor(
                io.cortexj.model.ModelDescriptor.builder("test/model").build()));
        open.put("artifactPath", artifact.toAbsolutePath().toString());
        open.put("device", "CPU");
        open.set("options", WorkerCodec.object());
        return open;
    }

    private ObjectNode loadPayload(long id) {
        ObjectNode load = op(id, "load");
        load.set("descriptor", WorkerCodec.modelDescriptor(
                io.cortexj.model.ModelDescriptor.builder("test/model").build()));
        load.put("artifactPath", artifact.toAbsolutePath().toString());
        load.put("device", "CPU");
        load.set("options", WorkerCodec.object());
        return load;
    }

    private void assertNullEventWindow(long millis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + millis;
        while (System.currentTimeMillis() < deadline) {
            String line = responses.poll(50, TimeUnit.MILLISECONDS);
            if (line == null) {
                continue;
            }
            JsonNode node = parse(line);
            assertFalse("token".equals(node.path("event").asText())
                            || "complete".equals(node.path("event").asText())
                            || "error".equals(node.path("event").asText()),
                    "no stream events may follow cancelStream, got: " + line);
        }
    }

    private static final class CollectingSink implements WorkerHost.PrintSink {
        final BlockingQueue<String> lines = new LinkedBlockingQueue<String>();

        @Override
        public void println(String line) {
            lines.add(line);
        }

        @Override
        public boolean checkError() {
            return false;
        }
    }
}
