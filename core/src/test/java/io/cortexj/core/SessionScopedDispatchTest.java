package io.cortexj.core;

import io.cortexj.CortexJ;
import io.cortexj.inference.EmbeddingRequest;
import io.cortexj.inference.EmbeddingResponse;
import io.cortexj.inference.FinishReason;
import io.cortexj.inference.GenerationRequest;
import io.cortexj.inference.GenerationResponse;
import io.cortexj.inference.InferenceRequest;
import io.cortexj.inference.InferenceResponse;
import io.cortexj.inference.TextGenerationCapability;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelReference;
import io.cortexj.model.TaskCapability;
import io.cortexj.spi.runtime.ModelRuntime;
import io.cortexj.spi.runtime.RuntimeModel;
import io.cortexj.spi.runtime.RuntimeModelRequest;
import io.cortexj.spi.runtime.RuntimeOpenRequest;
import io.cortexj.stream.TokenStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SessionScopedDispatchTest {

    @TempDir
    Path workDir;

    static final class SlowCapabilityRuntimeProvider extends ModelHandleCloseRaceTest.CountingRuntimeProvider {

        final AtomicInteger interleaved = new AtomicInteger();

        @Override
        public ModelRuntime open(io.cortexj.spi.runtime.RuntimeOpenRequest request) {
            return new ModelRuntime() {
                @Override
                public io.cortexj.runtime.RuntimeId runtimeId() {
                    return SlowCapabilityRuntimeProvider.this.id();
                }

                @Override
                public RuntimeModel load(RuntimeModelRequest loadRequest) {
                    return new SlowCapabilityModel();
                }

                @Override
                public void close() {
                }
            };
        }

        final class SlowCapabilityModel implements RuntimeModel, TextGenerationCapability,
                io.cortexj.inference.EmbeddingCapability {

            private final AtomicInteger inCall = new AtomicInteger();

            private void enter() {
                if (inCall.incrementAndGet() > 1) {
                    interleaved.incrementAndGet();
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            private void leave() {
                inCall.decrementAndGet();
            }

            @Override
            public io.cortexj.model.ModelCapabilities capabilities() {
                return io.cortexj.model.ModelCapabilities.of(
                        Collections.singleton(TaskCapability.TEXT_GENERATION), true, true, 1);
            }

            @Override
            public InferenceResponse execute(InferenceRequest request) {
                enter();
                try {
                    return InferenceResponse.single("ok");
                } finally {
                    leave();
                }
            }

            @Override
            public CompletionStage<InferenceResponse> executeAsync(InferenceRequest request) {
                return CompletableFuture.completedFuture(execute(request));
            }

            @Override
            public GenerationResponse generate(GenerationRequest request) {
                enter();
                try {
                    return new GenerationResponse("ok", FinishReason.STOP, null, null);
                } finally {
                    leave();
                }
            }

            @Override
            public TokenStream stream(GenerationRequest request) {
                enter();
                try {
                    return io.cortexj.stream.TokenStreams.single("ok");
                } finally {
                    leave();
                }
            }

            @Override
            public EmbeddingResponse embed(EmbeddingRequest request) {
                enter();
                try {
                    return new EmbeddingResponse(Collections.singletonList(new float[]{1f}));
                } finally {
                    leave();
                }
            }

            @Override
            public void close() {
            }
        }
    }

    @Test
    void generateStreamAndEmbedInterleaveOnlyUnderTheSessionLock() throws Exception {
        SlowCapabilityRuntimeProvider provider = new SlowCapabilityRuntimeProvider();
        CortexJ platform = CortexJ.builder()
                .runtimeProvider(provider)
                .repositoryProvider(new EndToEndLoadTest.FakeRepository())
                .workingDirectory(workDir)
                .build();
        try {
            ModelHandle handle = platform.models().load(ModelReference.of("e2e/test-model"));
            final CountDownLatch start = new CountDownLatch(1);
            final RuntimeException[] failures = new RuntimeException[3];
            Thread generator = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await(10, TimeUnit.SECONDS);
                        handle.capability(TextGenerationCapability.class)
                                .generate(GenerationRequest.prompt("hi"));
                    } catch (RuntimeException e) {
                        failures[0] = e;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            Thread streamer = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await(10, TimeUnit.SECONDS);
                        handle.capability(TextGenerationCapability.class)
                                .stream(GenerationRequest.prompt("hi"))
                                .subscribe(new io.cortexj.stream.TokenConsumer() {
                                    @Override
                                    public void onToken(io.cortexj.stream.Token token) {
                                    }

                                    @Override
                                    public void onComplete(io.cortexj.stream.StreamCompletion completion) {
                                    }

                                    @Override
                                    public void onError(io.cortexj.error.CortexJException error) {
                                    }
                                });
                    } catch (RuntimeException e) {
                        failures[1] = e;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            Thread embedder = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await(10, TimeUnit.SECONDS);
                        handle.capability(io.cortexj.inference.EmbeddingCapability.class)
                                .embed(EmbeddingRequest.texts(Collections.singletonList("hi")));
                    } catch (RuntimeException e) {
                        failures[2] = e;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            generator.start();
            streamer.start();
            embedder.start();
            start.countDown();
            generator.join(30_000);
            streamer.join(30_000);
            embedder.join(30_000);
            for (int i = 0; i < failures.length; i++) {
                if (failures[i] != null) {
                    throw failures[i];
                }
            }
            assertEquals(0, provider.interleaved.get(),
                    "generate/stream/embed on a SESSION_SCOPED runtime must dispatch "
                            + "under the session lock - no concurrent model entry");
        } finally {
            platform.close();
        }
    }
}
