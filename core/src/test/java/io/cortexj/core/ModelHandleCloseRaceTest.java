package io.cortexj.core;

import io.cortexj.CortexJ;
import io.cortexj.inference.FinishReason;
import io.cortexj.inference.GenerationRequest;
import io.cortexj.inference.GenerationResponse;
import io.cortexj.inference.InferenceRequest;
import io.cortexj.inference.InferenceResponse;
import io.cortexj.inference.TextGenerationCapability;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelCapabilities;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelLifecycleState;
import io.cortexj.model.ModelReference;
import io.cortexj.model.TaskCapability;
import io.cortexj.runtime.DeviceType;
import io.cortexj.runtime.RuntimeDescriptor;
import io.cortexj.runtime.RuntimeId;
import io.cortexj.spi.runtime.ModelRuntime;
import io.cortexj.spi.runtime.RuntimeEvaluationRequest;
import io.cortexj.spi.runtime.RuntimeModel;
import io.cortexj.spi.runtime.RuntimeModelRequest;
import io.cortexj.spi.runtime.RuntimeOpenRequest;
import io.cortexj.spi.runtime.RuntimeProbeContext;
import io.cortexj.spi.runtime.RuntimeProbeResult;
import io.cortexj.spi.runtime.RuntimeProvider;
import io.cortexj.spi.runtime.RuntimeSupport;
import io.cortexj.stream.TokenStream;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Tag;

@Tag("concurrency")
class ModelHandleCloseRaceTest {

    static final class CountingRuntimeModel implements RuntimeModel, TextGenerationCapability {

        final AtomicInteger closeCount = new AtomicInteger();

        @Override
        public ModelCapabilities capabilities() {
            return ModelCapabilities.of(Collections.singleton(TaskCapability.TEXT_GENERATION), true, false, 1);
        }

        @Override
        public InferenceResponse execute(InferenceRequest request) {
            return InferenceResponse.single("ok: " + request.singleInput());
        }

        @Override
        public CompletionStage<InferenceResponse> executeAsync(InferenceRequest request) {
            return CompletableFuture.completedFuture(execute(request));
        }

        @Override
        public GenerationResponse generate(GenerationRequest request) {
            return new GenerationResponse("ok: " + request.prompt(), FinishReason.STOP,
                    new io.cortexj.inference.TokenUsage(1, 1), null);
        }

        @Override
        public TokenStream stream(GenerationRequest request) {
            return io.cortexj.stream.TokenStreams.single("ok: " + request.prompt());
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }
    }

    static class CountingRuntimeProvider implements RuntimeProvider {

        final RuntimeId id = RuntimeId.of("close-race-runtime");
        // a close-race test can have a gated load and a queued load inside
        // runtime.load() at the same time; a plain ArrayList corrupts its size
        // counter then (JDK 11 ubuntu lane: ArrayIndexOutOfBoundsException)
        final List<CountingRuntimeModel> models =
                Collections.synchronizedList(new ArrayList<CountingRuntimeModel>());
        final AtomicInteger runtimeCloseCount = new AtomicInteger();

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
                    .maturity(RuntimeDescriptor.Maturity.STABLE)
                    .build();
        }

        @Override
        public RuntimeProbeResult probe(RuntimeProbeContext context) {
            return RuntimeProbeResult.available("counting runtime ready");
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
                    return id;
                }

                @Override
                public RuntimeModel load(RuntimeModelRequest loadRequest) {
                    CountingRuntimeModel model = new CountingRuntimeModel();
                    models.add(model);
                    return model;
                }

                @Override
                public void close() {
                    runtimeCloseCount.incrementAndGet();
                }
            };
        }
    }

    private CortexJ platform(CountingRuntimeProvider provider) {
        return CortexJ.builder()
                .runtimeProvider(provider)
                .repositoryProvider(new EndToEndLoadTest.FakeRepository())
                .build();
    }

    @Test
    void concurrentCloseClosesModelAndRuntimeExactlyOnce() throws Exception {
        CountingRuntimeProvider provider = new CountingRuntimeProvider();
        CortexJ platform = platform(provider);
        try {
            int iterations = 200;
            for (int i = 0; i < iterations; i++) {
                final ModelHandle handle = platform.models().load(ModelReference.of("e2e/test-model"));
                final CyclicBarrier barrier = new CyclicBarrier(2);
                final CountDownLatch done = new CountDownLatch(2);
                final RuntimeException[] failures = new RuntimeException[2];
                for (int t = 0; t < 2; t++) {
                    final int slot = t;
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                barrier.await(10, TimeUnit.SECONDS);
                                handle.close();
                            } catch (RuntimeException failure) {
                                failures[slot] = failure;
                            } catch (Exception failure) {
                                failures[slot] = new RuntimeException(failure);
                            } finally {
                                done.countDown();
                            }
                        }
                    }).start();
                }
                assertTrue(done.await(10, TimeUnit.SECONDS), "close workers did not finish");
                for (RuntimeException failure : failures) {
                    if (failure != null) {
                        throw failure;
                    }
                }
                assertEquals(ModelLifecycleState.CLOSED, handle.state());
                assertEquals(1, provider.models.get(provider.models.size() - 1).closeCount.get(),
                        "model.close() must run exactly once per handle");
            }
            assertEquals(iterations, provider.runtimeCloseCount.get(),
                    "runtime.close() must run exactly once per handle");
        } finally {
            platform.close();
        }
    }

    @Test
    void sequentialDoubleCloseIsIdempotent() {
        CountingRuntimeProvider provider = new CountingRuntimeProvider();
        CortexJ platform = platform(provider);
        try {
            ModelHandle handle = platform.models().load(ModelReference.of("e2e/test-model"));
            handle.close();
            handle.close();
            handle.close();
            assertEquals(ModelLifecycleState.CLOSED, handle.state());
            assertEquals(1, provider.models.get(0).closeCount.get());
            assertEquals(1, provider.runtimeCloseCount.get());
        } finally {
            platform.close();
        }
    }
}
