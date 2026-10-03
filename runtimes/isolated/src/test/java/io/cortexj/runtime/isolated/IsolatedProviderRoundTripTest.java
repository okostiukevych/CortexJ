package io.cortexj.runtime.isolated;

import io.cortexj.error.CortexJException;
import io.cortexj.error.InferenceException;
import io.cortexj.inference.EmbeddingRequest;
import io.cortexj.inference.EmbeddingResponse;
import io.cortexj.inference.GenerationRequest;
import io.cortexj.inference.GenerationResponse;
import io.cortexj.inference.InferenceRequest;
import io.cortexj.inference.InferenceResponse;
import io.cortexj.inference.TextGenerationCapability;
import io.cortexj.model.ModelDescriptor;
import io.cortexj.runtime.DeviceType;
import io.cortexj.runtime.RuntimeDescriptor;
import io.cortexj.runtime.ThreadSafety;
import io.cortexj.spi.runtime.ModelRuntime;
import io.cortexj.spi.runtime.RuntimeModel;
import io.cortexj.spi.runtime.RuntimeModelRequest;
import io.cortexj.spi.runtime.RuntimeOpenRequest;
import io.cortexj.spi.runtime.RuntimeProbeContext;
import io.cortexj.spi.runtime.RuntimeProbeResult;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end round trip against a REAL child JVM: the stub runtime is
 * registered in the test services file, {@code new IsolatedRuntimeProvider("stub")}
 * spawns io.cortexj.worker.WorkerHost via java.class.path and every request
 * crosses the process boundary.
 */
class IsolatedProviderRoundTripTest {

    private static IsolatedRuntimeProvider provider;
    private static Path artifact;

    @BeforeAll
    static void setUp() throws Exception {
        provider = new IsolatedRuntimeProvider("stub");
        artifact = Files.createTempFile("isolated-stub-model", ".onnx");
        Files.write(artifact, "stub".getBytes(StandardCharsets.UTF_8));
    }

    @AfterAll
    static void tearDown() {
        provider.close();
    }

    @Test
    void probeSpawnsChildAndReportsAvailable() {
        RuntimeProbeResult result = provider.probe(probeContext());
        assertNotNull(result.message(), result.message());
        assertTrue(result.isUsable(), "probe must succeed against a real child: " + result.message());
        assertTrue(result.message().contains("stub"), "child probe message must surface: " + result.message());
        assertNotNull(provider.currentChannelForTesting(), "probe must leave a live channel");
        assertTrue(provider.currentChannelForTesting().processForTesting().isAlive());
    }

    @Test
    void descriptorIsTranslatedFromTheChild() {
        RuntimeDescriptor descriptor = provider.descriptor();
        assertEquals("worker-stub", descriptor.id().value());
        assertEquals(ThreadSafety.STATELESS, descriptor.threadSafety());
        assertTrue(descriptor.formats().contains(io.cortexj.model.ArtifactFormat.ONNX));
        assertTrue(descriptor.tasks().contains(io.cortexj.model.TaskCapability.TEXT_GENERATION));
        assertTrue(descriptor.tasks().contains(io.cortexj.model.TaskCapability.EMBEDDING));
        assertTrue(descriptor.isStreamingSupported());
        assertEquals(RuntimeDescriptor.Maturity.STABLE, descriptor.maturity());
        assertEquals("stub", descriptor.metadata().get("worker.target"));
        assertEquals("isolated", descriptor.metadata().get("worker.mode"));
        assertEquals(provider.id(), descriptor.id(), "descriptor id must equal provider id");
    }

    @Test
    void openLoadExecuteGenerateEmbedClose() {
        ModelRuntime runtime = provider.open(openRequest());
        try {
            assertEquals(provider.id(), runtime.runtimeId());
            RuntimeModel model = runtime.load(loadRequest(artifact));

            assertEquals(4, model.capabilities().maxConcurrentRequests());
            assertTrue(model instanceof TextGenerationCapability,
                    "the proxy must advertise the child's text-generation capability");
            assertTrue(model instanceof io.cortexj.inference.EmbeddingCapability,
                    "the proxy must advertise the child's embedding capability");

            InferenceResponse executed = model.execute(InferenceRequest.builder().input("hello").build());
            assertEquals("stub:hello", executed.singleOutput());
            assertEquals(Boolean.TRUE, executed.metadata().get("stub"));

            // request multiplexing: a second execute over the same channel
            InferenceResponse again = model.execute(InferenceRequest.builder().input("again").build());
            assertEquals("stub:again", again.singleOutput());

            GenerationResponse generated = ((TextGenerationCapability) model)
                    .generate(GenerationRequest.prompt("hi"));
            assertEquals("stub-generated:hi", generated.text());
            assertEquals(io.cortexj.inference.FinishReason.STOP, generated.finishReason());
            assertTrue(generated.usage().isKnown());

            EmbeddingResponse embedded = ((io.cortexj.inference.EmbeddingCapability) model)
                    .embed(EmbeddingRequest.texts(java.util.Collections.singletonList("abcd")));
            assertEquals(1, embedded.vectors().size());
            assertEquals(2, embedded.singleVector().length);
            assertEquals(1.0f, embedded.singleVector()[0], 0.0001f);

            InferenceResponse async = model.executeAsync(InferenceRequest.builder().input("async").build())
                    .toCompletableFuture()
                    .join();
            assertEquals("stub:async", async.singleOutput());

            model.close();
            assertThrows(InferenceException.class,
                    () -> model.execute(InferenceRequest.builder().input("closed").build()),
                    "a closed model handle must fail typed");
        } finally {
            runtime.close();
        }
    }

    @Test
    void loadWithBogusPathFailsTypedAndRuntimeStaysUsable() {
        ModelRuntime runtime = provider.open(openRequest());
        try {
            Path bogus = artifact.resolveSibling("definitely-not-a-model-" + System.nanoTime());
            CortexJException failure = assertThrows(CortexJException.class,
                    () -> runtime.load(loadRequest(bogus)),
                    "a bogus artifact path must surface the child's typed load error");
            assertEquals(io.cortexj.ErrorCode.LOAD_FAILED, failure.code(),
                    "the child's error code must survive the process boundary");
        } finally {
            runtime.close();
        }
        // the runtime session is reusable after the failed load
        ModelRuntime second = provider.open(openRequest());
        try {
            RuntimeModel model = second.load(loadRequest(artifact));
            assertEquals("stub:ok", model.execute(InferenceRequest.builder().input("ok").build()).singleOutput());
        } finally {
            second.close();
        }
    }

    @Test
    void doubleOpenWithoutCloseFailsLoudly() {
        ModelRuntime first = provider.open(openRequest());
        try {
            // v1 hosts a single session per worker: a concurrent second open
            // must fail typed instead of silently breaking the first session
            assertThrows(CortexJException.class, () -> provider.open(openRequest()));
        } finally {
            first.close();
        }
        // after closing, open works again
        ModelRuntime reopened = provider.open(openRequest());
        reopened.close();
    }

    static RuntimeProbeContext probeContext() {
        return new RuntimeProbeContext(
                io.cortexj.tck.RuntimeProviderContract.testEnvironment(),
                io.cortexj.tck.RuntimeProviderContract.testHardware(false),
                false, java.util.Collections.<String, String>emptyMap());
    }

    static RuntimeOpenRequest openRequest() {
        return new RuntimeOpenRequest(
                ModelDescriptor.builder("test/stub-model").build(),
                io.cortexj.tck.RuntimeProviderContract.artifactNodeFor(io.cortexj.model.ArtifactFormat.ONNX),
                artifact,
                DeviceType.CPU,
                java.util.Collections.<String, Object>emptyMap(),
                java.util.Collections.<String, String>emptyMap(),
                null, null, null, null);
    }

    static RuntimeModelRequest loadRequest(Path path) {
        return new RuntimeModelRequest(
                ModelDescriptor.builder("test/stub-model").build(),
                io.cortexj.tck.RuntimeProviderContract.artifactNodeFor(io.cortexj.model.ArtifactFormat.ONNX),
                path,
                DeviceType.CPU,
                java.util.Collections.<String, Object>emptyMap());
    }
}
