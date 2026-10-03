package io.cortexj.runtime.onnx;

import ai.onnxruntime.OrtEnvironment;
import io.cortexj.ErrorCode;
import io.cortexj.error.InferenceException;
import io.cortexj.inference.EmbeddingRequest;
import io.cortexj.inference.EmbeddingResponse;
import io.cortexj.inference.InferenceRequest;
import io.cortexj.inference.InferenceResponse;
import io.cortexj.model.token.HfTokenizer;
import io.cortexj.spi.runtime.ModelRuntime;
import io.cortexj.spi.runtime.RuntimeModel;
import io.cortexj.spi.runtime.RuntimeModelRequest;
import io.cortexj.spi.runtime.RuntimeOpenRequest;
import io.cortexj.spi.runtime.RuntimeProbeResult;
import io.cortexj.tck.RuntimeProviderContract;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("concurrency")
class OnnxInferenceConcurrencyTest {

    @Test
    void concurrentEmbedsAreNotGloballySerialized() throws Exception {
        final OverlapProbeProvider provider = new OverlapProbeProvider();
        final OnnxRuntimeProvider.OnnxModel model =
                provider.new OnnxModel(null, HfTokenizer.fallback(), "input_ids", null);
        final AtomicReference<Throwable> first = new AtomicReference<Throwable>();
        final AtomicReference<Throwable> second = new AtomicReference<Throwable>();
        final CountDownLatch start = new CountDownLatch(1);
        Thread a = embedThread(model, "alpha beta", start, first);
        Thread b = embedThread(model, "gamma delta", start, second);
        start.countDown();
        a.join(30_000L);
        b.join(30_000L);
        assertTrue(provider.peak.get() >= 2,
                "two embeds must be able to hold the inference region at once instead of "
                        + "serializing on one monitor (GitHub #67); peak concurrent inferences: "
                        + provider.peak.get());
    }

    @Test
    void closeWaitsForInFlightInferenceThenCompletesOnce() throws Exception {
        final CountDownLatch inferenceInside = new CountDownLatch(1);
        final CountDownLatch releaseInference = new CountDownLatch(1);
        final BlockingEnvironmentProvider provider =
                new BlockingEnvironmentProvider(inferenceInside, releaseInference);
        final OnnxRuntimeProvider.OnnxModel model =
                provider.new OnnxModel(null, HfTokenizer.fallback(), "input_ids", null);
        final CountDownLatch inferenceFinished = new CountDownLatch(1);
        Thread inference = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    model.embed(EmbeddingRequest.text("alpha"));
                } catch (Throwable expected) {
                } finally {
                    inferenceFinished.countDown();
                }
            }
        });
        inference.start();
        assertTrue(inferenceInside.await(10, TimeUnit.SECONDS),
                "the inference thread must enter the locked region");
        final CountDownLatch closeReturned = new CountDownLatch(1);
        Thread closer = new Thread(new Runnable() {
            @Override
            public void run() {
                model.close();
                closeReturned.countDown();
            }
        });
        closer.start();
        Thread.sleep(500L);
        assertEquals(1L, closeReturned.getCount(),
                "close() must wait for the in-flight inference instead of returning early");
        releaseInference.countDown();
        assertTrue(closeReturned.await(10, TimeUnit.SECONDS),
                "close() must complete once the in-flight inference finishes");
        assertTrue(inferenceFinished.await(10, TimeUnit.SECONDS));
        inference.join(10_000L);
        closer.join(10_000L);
        model.close();
        InferenceException typed = assertThrows(InferenceException.class,
                () -> model.embed(EmbeddingRequest.text("alpha")));
        assertEquals(ErrorCode.INFERENCE_FAILED, typed.code());
    }

    @Test
    void executeAsyncOffloadsToSuppliedExecutor() throws Exception {
        final OnnxRuntimeProvider provider = new OnnxRuntimeProvider();
        final RecordingExecutor executor = new RecordingExecutor();
        try {
            final OnnxRuntimeProvider.OnnxModel model =
                    provider.new OnnxModel(null, HfTokenizer.fallback(), "input_ids", executor);
            CompletableFuture<InferenceResponse> future =
                    (CompletableFuture<InferenceResponse>) model.executeAsync(
                            InferenceRequest.builder().input("alpha beta").build());
            try {
                future.get(30, TimeUnit.SECONDS);
            } catch (ExecutionException expected) {
            }
            assertNotNull(executor.ranOn.get(), "inference must run on the supplied executor");
            assertNotSame(Thread.currentThread(), executor.ranOn.get(),
                    "executeAsync must not run native inference on the caller thread (GitHub #67)");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void loadedModelCachesSessionInputNameOnce() throws Exception {
        RuntimeProbeResult probe = new OnnxRuntimeProvider().probe(RuntimeProviderContract.probeContext());
        Assumptions.assumeTrue(probe.isUsable(),
                "ONNX native runtime not available on this machine");
        Path modelPath = OnnxTestModels.castEmbeddingModel();
        OnnxRuntimeProvider provider = new OnnxRuntimeProvider();
        ModelRuntime runtime = provider.open(openRequest(modelPath, null));
        try {
            RuntimeModel loaded = runtime.load(loadRequest(modelPath));
            try {
                OnnxRuntimeProvider.OnnxModel model = (OnnxRuntimeProvider.OnnxModel) loaded;
                assertEquals("input_ids", model.inputName,
                        "the input name must be resolved once at load, not per inference (GitHub #67)");
                int[] ids = HfTokenizer.fallback().encode("alpha beta");
                EmbeddingResponse embedding = model.embed(EmbeddingRequest.text("alpha beta"));
                float[] expected = new float[ids.length];
                for (int i = 0; i < ids.length; i++) {
                    expected[i] = (float) ids[i];
                }
                assertArrayEquals(expected, embedding.singleVector(), 0.0f);
            } finally {
                loaded.close();
            }
        } finally {
            runtime.close();
            Files.deleteIfExists(modelPath);
        }
    }

    @Test
    void realSessionServesConcurrentEmbedsOnOneModel() throws Exception {
        RuntimeProbeResult probe = new OnnxRuntimeProvider().probe(RuntimeProviderContract.probeContext());
        Assumptions.assumeTrue(probe.isUsable(),
                "ONNX native runtime not available on this machine");
        final Path modelPath = OnnxTestModels.castEmbeddingModel();
        final OnnxRuntimeProvider provider = new OnnxRuntimeProvider();
        ModelRuntime runtime = provider.open(openRequest(modelPath, null));
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            RuntimeModel loaded = runtime.load(loadRequest(modelPath));
            try {
                final OnnxRuntimeProvider.OnnxModel model = (OnnxRuntimeProvider.OnnxModel) loaded;
                final int rounds = 10;
                final CountDownLatch start = new CountDownLatch(1);
                List<Future<Throwable>> results = new ArrayList<Future<Throwable>>();
                for (int worker = 0; worker < 4; worker++) {
                    final int workerId = worker;
                    results.add(pool.submit(new java.util.concurrent.Callable<Throwable>() {
                        @Override
                        public Throwable call() {
                            try {
                                start.await();
                                for (int round = 0; round < rounds; round++) {
                                    String text = "worker " + workerId + " round " + round + " tokens";
                                    EmbeddingResponse embedding = model.embed(EmbeddingRequest.text(text));
                                    int[] ids = HfTokenizer.fallback().encode(text);
                                    float[] vector = embedding.singleVector();
                                    if (vector.length != ids.length) {
                                        return new AssertionError("vector length mismatch for: " + text);
                                    }
                                    for (int i = 0; i < ids.length; i++) {
                                        if (vector[i] != (float) ids[i]) {
                                            return new AssertionError("vector content mismatch for: " + text);
                                        }
                                    }
                                }
                                return null;
                            } catch (Throwable failure) {
                                return failure;
                            }
                        }
                    }));
                }
                start.countDown();
                for (Future<Throwable> result : results) {
                    Throwable failure = result.get(60, TimeUnit.SECONDS);
                    if (failure != null) {
                        throw new AssertionError("concurrent embed on a shared session failed", failure);
                    }
                }
            } finally {
                loaded.close();
            }
        } finally {
            pool.shutdownNow();
            runtime.close();
            Files.deleteIfExists(modelPath);
        }
    }

    @Test
    void executeAsyncWithoutExecutorCompletesInline() throws Exception {
        RuntimeProbeResult probe = new OnnxRuntimeProvider().probe(RuntimeProviderContract.probeContext());
        Assumptions.assumeTrue(probe.isUsable(),
                "ONNX native runtime not available on this machine");
        Path modelPath = OnnxTestModels.castEmbeddingModel();
        OnnxRuntimeProvider provider = new OnnxRuntimeProvider();
        ModelRuntime runtime = provider.open(openRequest(modelPath, null));
        try {
            RuntimeModel loaded = runtime.load(loadRequest(modelPath));
            try {
                OnnxRuntimeProvider.OnnxModel model = (OnnxRuntimeProvider.OnnxModel) loaded;
                CompletableFuture<InferenceResponse> future =
                        (CompletableFuture<InferenceResponse>) model.executeAsync(
                                InferenceRequest.builder().input("alpha").build());
                assertTrue(future.isDone(),
                        "without a supplied executor the completed-future path runs inline");
                InferenceResponse response = future.get(5, TimeUnit.SECONDS);
                assertEquals(1, response.outputs().size());
            } finally {
                loaded.close();
            }
        } finally {
            runtime.close();
            Files.deleteIfExists(modelPath);
        }
    }

    private static Thread embedThread(final OnnxRuntimeProvider.OnnxModel model,
                                      final String text,
                                      final CountDownLatch start,
                                      final AtomicReference<Throwable> failure) {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    start.await();
                    model.embed(EmbeddingRequest.text(text));
                } catch (Throwable t) {
                    failure.set(t);
                }
            }
        });
        thread.start();
        return thread;
    }

    private static RuntimeOpenRequest openRequest(Path artifactPath, ExecutorService executor) {
        return new RuntimeOpenRequest(
                io.cortexj.model.ModelDescriptor.builder("tck/onnx").build(),
                null, artifactPath, io.cortexj.runtime.DeviceType.CPU,
                java.util.Collections.<String, Object>emptyMap(),
                java.util.Collections.<String, String>emptyMap(),
                RuntimeProviderContract.testEnvironment(), RuntimeProviderContract.testHardware(false),
                executor, null);
    }

    private static RuntimeModelRequest loadRequest(Path artifactPath) {
        return new RuntimeModelRequest(
                io.cortexj.model.ModelDescriptor.builder("tck/onnx").build(),
                null, artifactPath, io.cortexj.runtime.DeviceType.CPU,
                java.util.Collections.<String, Object>emptyMap());
    }

    private static final class OverlapProbeProvider extends OnnxRuntimeProvider {
        final AtomicInteger inside = new AtomicInteger();
        final AtomicInteger peak = new AtomicInteger();

        @Override
        OrtEnvironment environment() {
            int concurrent = inside.incrementAndGet();
            peak.accumulateAndGet(concurrent, new java.util.function.IntBinaryOperator() {
                @Override
                public int applyAsInt(int current, int update) {
                    return Math.max(current, update);
                }
            });
            try {
                Thread.sleep(300L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inside.decrementAndGet();
            }
            return super.environment();
        }
    }

    private static final class BlockingEnvironmentProvider extends OnnxRuntimeProvider {
        private final CountDownLatch entered;
        private final CountDownLatch release;

        BlockingEnvironmentProvider(CountDownLatch entered, CountDownLatch release) {
            this.entered = entered;
            this.release = release;
        }

        @Override
        OrtEnvironment environment() {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return super.environment();
        }
    }

    private static final class RecordingExecutor extends java.util.concurrent.AbstractExecutorService {
        private final java.util.concurrent.ExecutorService delegate =
                java.util.concurrent.Executors.newSingleThreadExecutor(
                        new java.util.concurrent.ThreadFactory() {
                            @Override
                            public Thread newThread(Runnable runnable) {
                                Thread thread = new Thread(runnable, "cortexj-test-recorder");
                                thread.setDaemon(true);
                                return thread;
                            }
                        });
        private final AtomicReference<Thread> ranOn = new AtomicReference<Thread>();

        @Override
        public void execute(final Runnable command) {
            delegate.execute(new Runnable() {
                @Override
                public void run() {
                    ranOn.set(Thread.currentThread());
                    command.run();
                }
            });
        }

        @Override
        public void shutdown() {
            delegate.shutdown();
        }

        @Override
        public List<Runnable> shutdownNow() {
            return delegate.shutdownNow();
        }

        @Override
        public boolean isShutdown() {
            return delegate.isShutdown();
        }

        @Override
        public boolean isTerminated() {
            return delegate.isTerminated();
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
    }
}
