package io.cortexj.runtime.djl;

import io.cortexj.ErrorCode;
import io.cortexj.error.InferenceException;
import io.cortexj.inference.EmbeddingRequest;
import io.cortexj.inference.InferenceRequest;
import io.cortexj.inference.InferenceResponse;
import io.cortexj.model.token.HfTokenizer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("concurrency")
class DjlInferenceConcurrencyTest {

    @Test
    void concurrentEmbedsAreNotGloballySerialized() throws Exception {
        final CountDownLatch bothInside = new CountDownLatch(2);
        final StubModel fake = new StubModel(new java.util.function.Supplier<ai.djl.ndarray.NDManager>() {
            @Override
            public ai.djl.ndarray.NDManager get() {
                bothInside.countDown();
                try {
                    if (!bothInside.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "embeds were serialized: the second inference never entered the native region");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("both embeds held the native region concurrently");
            }
        });
        final DjlProvider provider = new DjlProvider();
        final DjlProvider.DjlModel model = provider.new DjlModel(fake, HfTokenizer.fallback(), null);
        final AtomicReference<Throwable> first = new AtomicReference<Throwable>();
        final AtomicReference<Throwable> second = new AtomicReference<Throwable>();
        final CountDownLatch start = new CountDownLatch(1);
        Thread a = embedThread(model, "alpha beta", start, first);
        Thread b = embedThread(model, "gamma delta", start, second);
        start.countDown();
        a.join(30_000L);
        b.join(30_000L);
        assertNotNull(first.get());
        assertNotNull(second.get());
        assertEquals("both embeds held the native region concurrently", causeMessage(first.get()));
        assertEquals("both embeds held the native region concurrently", causeMessage(second.get()));
    }

    @Test
    void closeWaitsForInFlightInferenceThenClosesOnce() throws Exception {
        final CountDownLatch inferenceInside = new CountDownLatch(1);
        final CountDownLatch releaseInference = new CountDownLatch(1);
        final StubModel fake = new StubModel(new java.util.function.Supplier<ai.djl.ndarray.NDManager>() {
            @Override
            public ai.djl.ndarray.NDManager get() {
                inferenceInside.countDown();
                try {
                    releaseInference.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("inference released");
            }
        });
        final DjlProvider provider = new DjlProvider();
        final DjlProvider.DjlModel model = provider.new DjlModel(fake, HfTokenizer.fallback(), null);
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
        assertEquals(1, fake.closeCount.get(),
                "the underlying DJL model must be closed exactly once");
        model.close();
        assertEquals(1, fake.closeCount.get(), "a second close() must be a no-op");
        InferenceException typed = assertThrows(InferenceException.class,
                () -> model.embed(EmbeddingRequest.text("alpha")));
        assertEquals(ErrorCode.INFERENCE_FAILED, typed.code());
    }

    @Test
    void executeAsyncOffloadsToSuppliedExecutor() throws Exception {
        final StubModel fake = new StubModel(new java.util.function.Supplier<ai.djl.ndarray.NDManager>() {
            @Override
            public ai.djl.ndarray.NDManager get() {
                throw new IllegalStateException("offload-marker");
            }
        });
        final DjlProvider provider = new DjlProvider();
        final RecordingExecutor executor = new RecordingExecutor();
        try {
            final DjlProvider.DjlModel model = provider.new DjlModel(fake, HfTokenizer.fallback(), executor);
            CompletableFuture<InferenceResponse> future =
                    (CompletableFuture<InferenceResponse>) model.executeAsync(
                            InferenceRequest.builder().input("alpha beta").build());
            try {
                future.get(30, TimeUnit.SECONDS);
            } catch (ExecutionException expected) {
                assertEquals("offload-marker", causeMessage(expected.getCause()));
            }
            assertNotNull(executor.ranOn.get(), "inference must run on the supplied executor");
            assertNotSame(Thread.currentThread(), executor.ranOn.get(),
                    "executeAsync must not run native inference on the caller thread (GitHub #67)");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void executeAsyncWithoutExecutorReturnsFailedFuture() throws Exception {
        final StubModel fake = new StubModel(new java.util.function.Supplier<ai.djl.ndarray.NDManager>() {
            @Override
            public ai.djl.ndarray.NDManager get() {
                throw new IllegalStateException("offload-marker");
            }
        });
        final DjlProvider provider = new DjlProvider();
        final DjlProvider.DjlModel model = provider.new DjlModel(fake, HfTokenizer.fallback(), null);
        // an async contract reports failure through the future, not a synchronous throw
        java.util.concurrent.CompletableFuture<?> future = model
                .executeAsync(InferenceRequest.builder().input("alpha beta").build()).toCompletableFuture();
        ExecutionException failure = assertThrows(ExecutionException.class, () -> future.get());
        assertEquals("offload-marker", causeMessage(failure.getCause()));
    }

    @Test
    void embedAfterCloseFailsTyped() {
        final StubModel fake = new StubModel(new java.util.function.Supplier<ai.djl.ndarray.NDManager>() {
            @Override
            public ai.djl.ndarray.NDManager get() {
                throw new IllegalStateException("never reached");
            }
        });
        final DjlProvider provider = new DjlProvider();
        final DjlProvider.DjlModel model = provider.new DjlModel(fake, HfTokenizer.fallback(), null);
        model.close();
        InferenceException typed = assertThrows(InferenceException.class,
                () -> model.embed(EmbeddingRequest.text("alpha")));
        assertEquals(ErrorCode.INFERENCE_FAILED, typed.code());
        assertEquals("model is closed", typed.getMessage());
        assertEquals(0, fake.getNDManagerCount.get(),
                "a closed model must never touch the native model");
    }

    private static Thread embedThread(final DjlProvider.DjlModel model,
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

    private static String causeMessage(Throwable failure) {
        Throwable cause = failure.getCause();
        return cause == null ? failure.getMessage() : cause.getMessage();
    }

    private static final class StubModel implements ai.djl.Model {
        private final java.util.function.Supplier<ai.djl.ndarray.NDManager> managerGate;
        final AtomicInteger closeCount = new AtomicInteger();
        final AtomicInteger getNDManagerCount = new AtomicInteger();

        StubModel(java.util.function.Supplier<ai.djl.ndarray.NDManager> managerGate) {
            this.managerGate = managerGate;
        }

        @Override
        public ai.djl.ndarray.NDManager getNDManager() {
            getNDManagerCount.incrementAndGet();
            return managerGate.get();
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }

        @Override
        public void load(java.nio.file.Path path, String name, java.util.Map<String, ?> options) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void load(java.io.InputStream stream, java.util.Map<String, ?> options) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void save(java.nio.file.Path path, String name) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public java.nio.file.Path getModelPath() {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public ai.djl.nn.Block getBlock() {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void setBlock(ai.djl.nn.Block block) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public String getName() {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public java.util.Map<String, String> getProperties() {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public String getProperty(String key) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void setProperty(String key, String value) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public ai.djl.training.Trainer newTrainer(ai.djl.training.TrainingConfig config) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public <I, O> ai.djl.inference.Predictor<I, O> newPredictor(
                ai.djl.translate.Translator<I, O> translator, ai.djl.Device device) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public ai.djl.util.PairList<String, ai.djl.ndarray.types.Shape> describeInput() {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public ai.djl.util.PairList<String, ai.djl.ndarray.types.Shape> describeOutput() {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public String[] getArtifactNames() {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public <T> T getArtifact(String name, java.util.function.Function<java.io.InputStream, T> function) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public java.net.URL getArtifact(String name) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public java.io.InputStream getArtifactAsStream(String name) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public void setDataType(ai.djl.ndarray.types.DataType type) {
            throw new UnsupportedOperationException("stub");
        }

        @Override
        public ai.djl.ndarray.types.DataType getDataType() {
            throw new UnsupportedOperationException("stub");
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
