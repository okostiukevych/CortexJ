package io.cortexj.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.cortexj.CortexJ;
import io.cortexj.error.ModelLoadException;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.Checksum;
import io.cortexj.model.ModelArtifact;
import io.cortexj.model.ModelDescriptor;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelLifecycleState;
import io.cortexj.model.ModelReference;
import io.cortexj.model.TaskCapability;
import io.cortexj.spi.repository.ModelRepositoryProvider;
import io.cortexj.spi.repository.RepositoryId;
import io.cortexj.spi.repository.ResolvedArtifact;
import io.cortexj.spi.repository.ResolvedModel;
import io.cortexj.spi.repository.ResolutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Tag;

@Tag("concurrency")
class ConcurrentLoadDedupTest {

    @TempDir
    Path workDir;

    private static byte[] payload() {
        byte[] bytes = new byte[64 * 1024];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i * 31 + 7);
        }
        return bytes;
    }

    static final class PathRecordingRuntimeProvider extends ModelHandleCloseRaceTest.CountingRuntimeProvider {
        final List<Path> loadedPaths = new CopyOnWriteArrayList<Path>();
        final AtomicInteger downloadStartedEvents = new AtomicInteger();
        final io.cortexj.observability.PlatformEventListener listener =
                new io.cortexj.observability.PlatformEventListener() {
                    @Override
                    public void onEvent(io.cortexj.observability.PlatformEvent event) {
                        if (event.type() == io.cortexj.observability.PlatformEvent.Type.DOWNLOAD_STARTED) {
                            downloadStartedEvents.incrementAndGet();
                        }
                    }
                };

        @Override
        public io.cortexj.spi.runtime.ModelRuntime open(final io.cortexj.spi.runtime.RuntimeOpenRequest request) {
            return new io.cortexj.spi.runtime.ModelRuntime() {
                @Override
                public io.cortexj.runtime.RuntimeId runtimeId() {
                    return PathRecordingRuntimeProvider.this.id();
                }

                @Override
                public io.cortexj.spi.runtime.RuntimeModel load(io.cortexj.spi.runtime.RuntimeModelRequest loadRequest) {
                    loadedPaths.add(loadRequest.artifactPath());
                    return new ModelHandleCloseRaceTest.CountingRuntimeModel();
                }

                @Override
                public void close() {
                }
            };
        }
    }

    @Test
    void concurrentColdLoadDownloadsOnceAndBothHandlesAreValid() throws Exception {
        final byte[] payload = payload();
        final AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/models/dedup-model.gguf", new com.sun.net.httpserver.HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                hits.incrementAndGet();
                exchange.getResponseHeaders().add("Connection", "close");
                exchange.sendResponseHeaders(200, payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                    out.flush();
                    try {
                        Thread.sleep(150);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                }
                exchange.close();
            }
        });
        server.start();
        try {
            final ModelArtifact artifact = new ModelArtifact.Abstract(
                    "dedup-model.gguf", ArtifactFormat.GGUF, payload.length, null, null) {
            };
            final URI location = URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                    + "/models/dedup-model.gguf");
            final ResolvedModel resolved = testResolvedModel(artifact, location);
            ModelRepositoryProvider repository = new ModelRepositoryProvider() {
                @Override
                public RepositoryId id() {
                    return RepositoryId.of("dedup-repo");
                }

                @Override
                public boolean supports(ModelReference reference) {
                    return true;
                }

                @Override
                public ResolvedModel resolve(ModelReference reference, ResolutionContext context) {
                    return resolved;
                }
            };

            final PathRecordingRuntimeProvider provider = new PathRecordingRuntimeProvider();
            final CortexJ platform = CortexJ.builder()
                    .runtimeProvider(provider)
                    .repositoryProvider(repository)
                    .eventListener(provider.listener)
                    .workingDirectory(workDir)
                    .build();
            try {
                final CyclicBarrier barrier = new CyclicBarrier(2);
                final CountDownLatch done = new CountDownLatch(2);
                final ModelHandle[] handles = new ModelHandle[2];
                final RuntimeException[] failures = new RuntimeException[2];
                for (int t = 0; t < 2; t++) {
                    final int slot = t;
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                barrier.await(20, TimeUnit.SECONDS);
                                handles[slot] = platform.models().load(ModelReference.of("dedup/model"));
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
                assertTrue(done.await(60, TimeUnit.SECONDS), "load workers did not finish");
                for (RuntimeException failure : failures) {
                    if (failure != null) {
                        throw failure;
                    }
                }

                assertTrue(hits.get() >= 1, "the artifact must have been fetched over HTTP at least once");
                assertEquals(1, provider.downloadStartedEvents.get(),
                        "concurrent cold load of one artifact must produce exactly one download");
                assertEquals(2, provider.loadedPaths.size(), "both callers must receive a handle");
                assertEquals(provider.loadedPaths.get(0), provider.loadedPaths.get(1),
                        "both callers must share the single downloaded artifact");
                for (int t = 0; t < 2; t++) {
                    assertEquals(ModelLifecycleState.READY, handles[t].state());
                    assertArrayEquals(payload, Files.readAllBytes(provider.loadedPaths.get(t)),
                            "artifact bytes must be intact for caller " + t);
                    handles[t].close();
                }
            } finally {
                platform.close();
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void sequentialReloadUsesCacheWithoutNewDownload() throws Exception {
        final byte[] payload = payload();
        final AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.createContext("/models/dedup-model.gguf", new com.sun.net.httpserver.HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                hits.incrementAndGet();
                exchange.getResponseHeaders().add("Connection", "close");
                exchange.sendResponseHeaders(200, payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
                exchange.close();
            }
        });
        server.start();
        try {
            final ModelArtifact artifact = new ModelArtifact.Abstract(
                    "dedup-model.gguf", ArtifactFormat.GGUF, payload.length, null, null) {
            };
            final ResolvedModel resolved = testResolvedModel(artifact,
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                            + "/models/dedup-model.gguf"));
            ModelRepositoryProvider repository = new ModelRepositoryProvider() {
                @Override
                public RepositoryId id() {
                    return RepositoryId.of("dedup-repo");
                }

                @Override
                public boolean supports(ModelReference reference) {
                    return true;
                }

                @Override
                public ResolvedModel resolve(ModelReference reference, ResolutionContext context) {
                    return resolved;
                }
            };
            PathRecordingRuntimeProvider provider = new PathRecordingRuntimeProvider();
            CortexJ platform = CortexJ.builder()
                    .runtimeProvider(provider)
                    .repositoryProvider(repository)
                    .eventListener(provider.listener)
                    .workingDirectory(workDir)
                    .build();
            try {
                ModelHandle first = platform.models().load(ModelReference.of("dedup/model"));
                first.close();
                ModelHandle second = platform.models().load(ModelReference.of("dedup/model"));
                second.close();
                assertTrue(hits.get() >= 1, "the first load must have contacted the HTTP server");
                assertEquals(1, provider.downloadStartedEvents.get(),
                        "second load must be served from the cache without a new download");
            } finally {
                platform.close();
            }
        } finally {
            server.stop(0);
        }
    }

    private static ResolvedModel testResolvedModel(final ModelArtifact artifact, final URI location) {
        ModelDescriptor descriptor = ModelDescriptor.builder("dedup/model")
                .revision("rev1")
                .architecture("llama")
                .task(TaskCapability.TEXT_GENERATION)
                .artifact(artifact)
                .build();
        List<ResolvedArtifact> artifacts = new ArrayList<ResolvedArtifact>();
        artifacts.add(new ResolvedArtifact() {
            @Override
            public ModelArtifact artifact() {
                return artifact;
            }

            @Override
            public URI location() {
                return location;
            }

            @Override
            public java.util.Map<String, String> downloadHeaders() {
                return Collections.singletonMap("Connection", "close");
            }
        });
        return new ResolvedModel(descriptor, "rev1", RepositoryId.of("dedup-repo"), artifacts);
    }

    @Test
    void errorInOncePerKeyClaimFailsWaitersPromptly() throws Exception {
        DefaultModels models = new DefaultModels(null);
        final CountDownLatch workEntered = new CountDownLatch(1);
        final CountDownLatch releaseWork = new CountDownLatch(1);
        final java.util.function.Supplier<Path> failingWork = new java.util.function.Supplier<Path>() {
            @Override
            public Path get() {
                workEntered.countDown();
                try {
                    releaseWork.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                throw new OutOfMemoryError("simulated heap exhaustion in the claim work");
            }
        };
        final CountDownLatch claimantDone = new CountDownLatch(1);
        Thread claimant = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    models.oncePerKey("error-injection-key", failingWork);
                } catch (Throwable ignored) {
                } finally {
                    claimantDone.countDown();
                }
            }
        });
        claimant.start();
        assertTrue(workEntered.await(10, TimeUnit.SECONDS), "claimant must enter the work");

        final Path[] waiterResult = new Path[1];
        final Throwable[] waiterFailure = new Throwable[1];
        final CountDownLatch waiterDone = new CountDownLatch(1);
        Thread waiter = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    waiterResult[0] = models.oncePerKey("error-injection-key",
                            new java.util.function.Supplier<Path>() {
                                @Override
                                public Path get() {
                                    return Paths.get("unused");
                                }
                            });
                } catch (Throwable failure) {
                    waiterFailure[0] = failure;
                } finally {
                    waiterDone.countDown();
                }
            }
        });
        waiter.start();
        Thread.sleep(100);
        releaseWork.countDown();
        assertTrue(waiterDone.await(30, TimeUnit.SECONDS),
                "an Error in the claim work must not leave waiters blocked forever");
        assertTrue(claimantDone.await(10, TimeUnit.SECONDS));
        claimant.join(10_000);
        waiter.join(10_000);

        assertTrue(waiterResult[0] == null, "the waiter must not receive a result");
        assertTrue(waiterFailure[0] instanceof ModelLoadException,
                "an Error in the claim work must surface to waiters as a typed load failure, got: "
                        + waiterFailure[0]);
        assertTrue(waiterFailure[0].getCause() instanceof OutOfMemoryError,
                "the original Error cause must be preserved");
    }

    @Test
    void corruptedCacheEntryIsReDownloadedTransparently() throws Exception {
        final byte[] payload = payload();
        final AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(2));
        server.createContext("/models/dedup-model.gguf", new com.sun.net.httpserver.HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                hits.incrementAndGet();
                exchange.getResponseHeaders().add("Connection", "close");
                exchange.sendResponseHeaders(200, payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
                exchange.close();
            }
        });
        server.start();
        try {
            final ModelArtifact artifact = new ModelArtifact.Abstract(
                    "dedup-model.gguf", ArtifactFormat.GGUF, payload.length,
                    Checksum.sha256(io.cortexj.cache.Checksums.sha256Hex(payload)), null) {
            };
            final ResolvedModel resolved = testResolvedModel(artifact,
                    URI.create("http://127.0.0.1:" + server.getAddress().getPort()
                            + "/models/dedup-model.gguf"));
            ModelRepositoryProvider repository = new ModelRepositoryProvider() {
                @Override
                public RepositoryId id() {
                    return RepositoryId.of("dedup-repo");
                }

                @Override
                public boolean supports(ModelReference reference) {
                    return true;
                }

                @Override
                public ResolvedModel resolve(ModelReference reference, ResolutionContext context) {
                    return resolved;
                }
            };
            PathRecordingRuntimeProvider provider = new PathRecordingRuntimeProvider();
            DefaultCortexJ platform = (DefaultCortexJ) CortexJ.builder()
                    .runtimeProvider(provider)
                    .repositoryProvider(repository)
                    .eventListener(provider.listener)
                    .workingDirectory(workDir)
                    .build();
            try {
                ModelHandle first = platform.models().load(ModelReference.of("dedup/model"));
                first.close();
                assertEquals(1, provider.downloadStartedEvents.get(),
                        "the first load must download the artifact");

                Path cached = platform.cache().artifactPath("dedup-repo", "rev1", "dedup-model.gguf");
                assertTrue(Files.isRegularFile(cached), "the artifact must be cached after the first load");
                Files.write(cached, "corrupted".getBytes(StandardCharsets.UTF_8));

                ModelHandle second = platform.models().load(ModelReference.of("dedup/model"));
                assertEquals(ModelLifecycleState.READY, second.state(),
                        "a corrupted cache entry must be treated as a miss, not a load failure");
                second.close();
                assertEquals(2, provider.downloadStartedEvents.get(),
                        "a corrupted cache entry must trigger a transparent re-download");
                assertTrue(hits.get() >= 2, "the re-download must contact the HTTP server again");
                assertArrayEquals(payload, Files.readAllBytes(provider.loadedPaths.get(1)),
                        "the re-downloaded artifact bytes must be intact");
            } finally {
                platform.close();
            }
        } finally {
            server.stop(0);
        }
    }
}
