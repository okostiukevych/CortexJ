package io.cortexj.core;

import io.cortexj.CortexJ;
import io.cortexj.error.InferenceException;
import io.cortexj.inference.InferenceRequest;
import io.cortexj.inference.InferenceResponse;
import io.cortexj.inference.TextGenerationCapability;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelLifecycleState;
import io.cortexj.model.ModelReference;
import io.cortexj.model.TaskCapability;
import io.cortexj.spi.runtime.ModelRuntime;
import io.cortexj.spi.runtime.RuntimeModel;
import io.cortexj.spi.runtime.RuntimeModelRequest;
import io.cortexj.spi.runtime.RuntimeOpenRequest;
import io.cortexj.spi.runtime.RuntimeProbeContext;
import io.cortexj.spi.runtime.RuntimeProbeResult;
import io.cortexj.spi.runtime.RuntimeSupport;
import io.cortexj.runtime.RuntimeId;
import io.cortexj.spi.runtime.RuntimeProvider;
import io.cortexj.spi.runtime.RuntimeEvaluationRequest;
import io.cortexj.runtime.DeviceType;
import io.cortexj.runtime.RuntimeDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Tag;

@Tag("concurrency")
class ModelHandleClosedDispatchTest {

    @TempDir
    Path workDir;

    static final class SlowSessionScopedProvider implements RuntimeProvider {

        final RuntimeId id = RuntimeId.of("slow-session-scoped");
        final List<RuntimeModel> models = new CopyOnWriteArrayList<RuntimeModel>();
        final AtomicInteger executeCalls = new AtomicInteger();
        final AtomicInteger modelCloseCalls = new AtomicInteger();

        @Override
        public RuntimeId id() {
            return id;
        }

        @Override
        public RuntimeDescriptor descriptor() {
            return RuntimeDescriptor.builder(id)
                    .version("1.0")
                    .format(ArtifactFormat.GGUF)
                    .device(DeviceType.CPU)
                    .task(TaskCapability.TEXT_GENERATION)
                    .threadSafety(io.cortexj.runtime.ThreadSafety.SESSION_SCOPED)
                    .maturity(RuntimeDescriptor.Maturity.STABLE)
                    .build();
        }

        @Override
        public RuntimeProbeResult probe(RuntimeProbeContext context) {
            return RuntimeProbeResult.available("slow session-scoped runtime ready");
        }

        @Override
        public RuntimeSupport evaluate(RuntimeEvaluationRequest request) {
            return RuntimeSupport.full(DeviceType.CPU);
        }

        @Override
        public ModelRuntime open(RuntimeOpenRequest request) {
            return new ModelRuntime() {
                @Override
                public RuntimeId runtimeId() {
                    return SlowSessionScopedProvider.this.id;
                }

                @Override
                public RuntimeModel load(RuntimeModelRequest loadRequest) {
                    RuntimeModel model = new SlowModel();
                    models.add(model);
                    return model;
                }

                @Override
                public void close() {
                }
            };
        }

        class SlowModel implements RuntimeModel, TextGenerationCapability {

            volatile CountDownLatch holdExecute;
            volatile CountDownLatch executeEntered;

            @Override
            public io.cortexj.model.ModelCapabilities capabilities() {
                return io.cortexj.model.ModelCapabilities.of(java.util.Collections.singleton(
                        TaskCapability.TEXT_GENERATION), false, false, 1);
            }

            @Override
            public InferenceResponse execute(InferenceRequest request) {
                executeCalls.incrementAndGet();
                if (executeEntered != null) {
                    executeEntered.countDown();
                }
                if (holdExecute != null) {
                    try {
                        holdExecute.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return new InferenceResponse(java.util.Collections.singletonList("ok"), null);
            }

            @Override
            public java.util.concurrent.CompletionStage<InferenceResponse> executeAsync(
                    InferenceRequest request) {
                return java.util.concurrent.CompletableFuture.completedFuture(execute(request));
            }

            @Override
            public io.cortexj.inference.GenerationResponse generate(
                    io.cortexj.inference.GenerationRequest request) {
                return new io.cortexj.inference.GenerationResponse("ok",
                        io.cortexj.inference.FinishReason.STOP,
                        new io.cortexj.inference.TokenUsage(1L, 1L), null);
            }

            @Override
            public io.cortexj.stream.TokenStream stream(
                    io.cortexj.inference.GenerationRequest request) {
                return new io.cortexj.stream.internal.PushTokenStream();
            }

            @Override
            public void close() {
                modelCloseCalls.incrementAndGet();
            }
        }
    }

    private CortexJ platform(SlowSessionScopedProvider provider) {
        return CortexJ.builder()
                .runtimeProvider(provider)
                .repositoryProvider(new EndToEndLoadTest.FakeRepository())
                .workingDirectory(workDir)
                .build();
    }

    @Test
    void dispatchOnClosedHandleFailsFastWithTypedException() throws Exception {
        SlowSessionScopedProvider provider = new SlowSessionScopedProvider();
        CortexJ platform = platform(provider);
        try {
            ModelHandle handle = platform.models().load(ModelReference.of("e2e/test-model"));
            handle.close();
            assertEquals(ModelLifecycleState.CLOSED, handle.state());

            InferenceRequest request = InferenceRequest.builder().input("probe").build();
            InferenceException executeRejected = assertThrows(InferenceException.class,
                    () -> ((DefaultModelHandle) handle).execute(request));
            assertEquals("CORTEXJ-INFER-9001", executeRejected.code());
            assertThrows(InferenceException.class,
                    () -> handle.capability(TextGenerationCapability.class));
            assertThrows(InferenceException.class,
                    () -> handle.capabilityIfSupported(TextGenerationCapability.class));
            assertThrows(InferenceException.class,
                    () -> handle.capabilities());
            assertThrows(InferenceException.class,
                    () -> handle.supports(TaskCapability.TEXT_GENERATION));
            assertThrows(InferenceException.class,
                    () -> ((DefaultModelHandle) handle).executeAsync(request));
            assertEquals(0, provider.executeCalls.get(),
                    "no runtime work may be performed on a closed handle");
        } finally {
            platform.close();
        }
    }

    @Test
    void closeWaitsForInFlightExecuteInsteadOfTearingItDown() throws Exception {
        SlowSessionScopedProvider provider = new SlowSessionScopedProvider();
        CortexJ platform = platform(provider);
        try {
            ModelHandle handle = platform.models().load(ModelReference.of("e2e/test-model"));
            SlowSessionScopedProvider.SlowModel model =
                    (SlowSessionScopedProvider.SlowModel) provider.models.get(0);
            model.executeEntered = new CountDownLatch(1);
            model.holdExecute = new CountDownLatch(1);

            final java.util.concurrent.CompletableFuture<InferenceResponse> inFlight =
                    new java.util.concurrent.CompletableFuture<InferenceResponse>();
            Thread executor = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        inFlight.complete(((DefaultModelHandle) handle).execute(
                                InferenceRequest.builder().input("probe").build()));
                    } catch (RuntimeException failure) {
                        inFlight.completeExceptionally(failure);
                    }
                }
            });
            executor.start();
            assertTrue(model.executeEntered.await(10, TimeUnit.SECONDS),
                    "in-flight execute must have started");

            final java.util.concurrent.CompletableFuture<Void> closed =
                    new java.util.concurrent.CompletableFuture<Void>();
            Thread closer = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        handle.close();
                        closed.complete(null);
                    } catch (RuntimeException failure) {
                        closed.completeExceptionally(failure);
                    }
                }
            });
            closer.start();
            Thread.sleep(200);
            assertFalse(closed.isDone(),
                    "close() must block while a SESSION_SCOPED execute holds the session lock");
            assertEquals(1, provider.executeCalls.get());

            model.holdExecute.countDown();
            closed.get(10, TimeUnit.SECONDS);
            executor.join(10_000);
            assertTrue(inFlight.isDone() && !inFlight.isCompletedExceptionally(),
                    "the in-flight execute must complete normally, not crash");
            assertEquals("ok", inFlight.get().outputs().get(0));
            assertEquals(ModelLifecycleState.CLOSED, handle.state());
            assertEquals(1, provider.modelCloseCalls.get());
        } finally {
            platform.close();
        }
    }

    @Test
    void executeQueuedBehindCloseIsRejectedNotExecuted() throws Exception {
        SlowSessionScopedProvider provider = new SlowSessionScopedProvider();
        CortexJ platform = platform(provider);
        try {
            ModelHandle handle = platform.models().load(ModelReference.of("e2e/test-model"));
            SlowSessionScopedProvider.SlowModel model =
                    (SlowSessionScopedProvider.SlowModel) provider.models.get(0);
            model.executeEntered = new CountDownLatch(1);
            model.holdExecute = new CountDownLatch(1);

            Thread first = new Thread(new Runnable() {
                @Override
                public void run() {
                    ((DefaultModelHandle) handle).execute(InferenceRequest.builder().input("first").build());
                }
            });
            first.start();
            assertTrue(model.executeEntered.await(10, TimeUnit.SECONDS));

            Thread closer = new Thread(new Runnable() {
                @Override
                public void run() {
                    handle.close();
                }
            });
            closer.start();
            Thread.sleep(150);

            InferenceException rejected = assertThrows(InferenceException.class,
                    () -> ((DefaultModelHandle) handle).execute(InferenceRequest.builder().input("queued").build()),
                    "an execute queued behind close() must be rejected with a typed error");
            assertEquals("CORTEXJ-INFER-9001", rejected.code());

            model.holdExecute.countDown();
            closer.join(10_000);
            first.join(10_000);
            assertEquals(1, provider.executeCalls.get(),
                    "the queued execute must never reach the runtime");
            assertEquals(1, provider.modelCloseCalls.get());
        } finally {
            platform.close();
        }
    }
}
