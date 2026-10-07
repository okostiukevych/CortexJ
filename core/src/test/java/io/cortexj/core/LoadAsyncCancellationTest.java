package io.cortexj.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.cortexj.CortexJ;
import io.cortexj.ErrorCode;
import io.cortexj.error.ModelLoadException;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelArtifact;
import io.cortexj.model.ModelHandle;
import io.cortexj.model.ModelReference;
import io.cortexj.planner.ExecutionPlan;
import io.cortexj.planner.ExecutionRequest;
import io.cortexj.runtime.DeviceType;
import io.cortexj.runtime.RuntimeDescriptor;
import io.cortexj.runtime.RuntimeId;
import io.cortexj.spi.repository.ResolvedArtifact;
import io.cortexj.spi.runtime.ModelRuntime;
import io.cortexj.spi.runtime.RuntimeEvaluationRequest;
import io.cortexj.spi.runtime.RuntimeModel;
import io.cortexj.spi.runtime.RuntimeModelRequest;
import io.cortexj.spi.runtime.RuntimeOpenRequest;
import io.cortexj.spi.runtime.RuntimeProbeContext;
import io.cortexj.spi.runtime.RuntimeProbeResult;
import io.cortexj.spi.runtime.RuntimeProvider;
import io.cortexj.spi.runtime.RuntimeSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.Tag;

@Tag("concurrency")
class LoadAsyncCancellationTest {

    private static final int PAYLOAD_SIZE = 200;

    private HttpServer server;
    private int port;

    @TempDir
    Path tempDir;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(2));
        port = server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    /** Serves the payload in 10-byte chunks with 50ms pauses: a slow download. */
    private void serveSlowly(final byte[] payload) {
        server.createContext("/models/model.bin", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                java.io.InputStream input = exchange.getRequestBody();
                byte[] buffer = new byte[4096];
                while (input.read(buffer) >= 0) {
                }
                input.close();
                exchange.getResponseHeaders().add("Connection", "close");
                exchange.sendResponseHeaders(200, payload.length);
                OutputStream out = exchange.getResponseBody();
                for (int offset = 0; offset < payload.length; offset += 10) {
                    int end = Math.min(offset + 10, payload.length);
                    out.write(java.util.Arrays.copyOfRange(payload, offset, end));
                    out.flush();
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                out.close();
            }
        });
    }

    @Test
    void loadTriggeredDownloadCanBeCancelledPromptlyAndPartialIsKept() throws Exception {
        byte[] payload = new byte[PAYLOAD_SIZE];
        java.util.Arrays.fill(payload, (byte) 'd');
        serveSlowly(payload);

        final ResolvedArtifact artifact = new ResolvedArtifact() {
            @Override
            public ModelArtifact artifact() {
                return new ModelArtifact.Abstract("model.bin", ArtifactFormat.GGUF,
                        PAYLOAD_SIZE, null, null) {
                };
            }

            @Override
            public URI location() {
                return URI.create("http://127.0.0.1:" + port + "/models/model.bin");
            }

            @Override
            public Map<String, String> downloadHeaders() {
                return Collections.singletonMap("Connection", "close");
            }
        };
        io.cortexj.spi.repository.ModelRepositoryProvider repository = new io.cortexj.spi.repository.ModelRepositoryProvider() {
            @Override
            public io.cortexj.spi.repository.RepositoryId id() {
                return io.cortexj.spi.repository.RepositoryId.of("async-repo");
            }

            @Override
            public boolean supports(ModelReference reference) {
                return true;
            }

            @Override
            public io.cortexj.spi.repository.ResolvedModel resolve(ModelReference reference,
                                                                  io.cortexj.spi.repository.ResolutionContext context) {
                io.cortexj.model.ModelDescriptor descriptor = io.cortexj.model.ModelDescriptor
                        .builder("test/async-model")
                        .revision("rev1")
                        .task(io.cortexj.model.TaskCapability.TEXT_GENERATION)
                        .artifact(artifact.artifact())
                        .build();
                return new io.cortexj.spi.repository.ResolvedModel(descriptor, "rev1",
                        id(), Collections.singletonList(artifact));
            }
        };

            DefaultCortexJ platform = (DefaultCortexJ) CortexJ.builder()
                    .runtimeProvider(new ModelHandleCloseRaceTest.CountingRuntimeProvider())
                    .repositoryProvider(repository)
                    .workingDirectory(tempDir.resolve("work"))
                    .build();
        try {
            Future<ModelHandle> future = platform.models().loadAsync(ModelReference.of("test/async-model"));

            // wait until the download is in-flight with partial progress
            Path partial = platform.cache().root().resolve("downloads-partial")
                    .resolve(io.cortexj.core.download.DownloadManager.partialFileName(
                            "async-repo", "rev1", "model.bin"));
            long deadline = System.currentTimeMillis() + 30_000L;
            while (!Files.isRegularFile(partial) || Files.size(partial) == 0) {
                if (System.currentTimeMillis() > deadline) {
                    throw new AssertionError("the load never produced partial download progress");
                }
                Thread.sleep(10);
            }
            // let the copy settle into its blocked read, then cancel
            Thread.sleep(50);

            long cancelStarted = System.nanoTime();
            assertTrue(future.cancel(true), "cancel() on a running load must succeed");
            long cancelMillis = (System.nanoTime() - cancelStarted) / 1_000_000L;

            assertTrue(future.isCancelled());
            try {
                future.get(10, TimeUnit.SECONDS);
                throw new AssertionError("get() on a cancelled load must throw");
            } catch (CancellationException expected) {
            }

            // prompt: the copy stops and the loading task finishes quickly. The
            // chunk already read (past the cancel-flag check) when cancel() lands
            // may still be written - the copy settles within one 50ms chunk
            // interval, then the partial must stop growing entirely
            Thread.sleep(300);
            long settled = Files.size(partial);
            Thread.sleep(500);
            assertEquals(settled, Files.size(partial),
                    "the download must stop promptly after cancel() - no further progress beyond the in-flight chunk");
            assertTrue(cancelMillis < 5_000L,
                    "cancel() must return promptly (took " + cancelMillis + "ms)");
            assertTrue(Files.isRegularFile(partial),
                    "the resume partial must be kept per existing cancellation semantics");
        } finally {
            platform.close();
        }
    }

    static final class GatedRuntimeLoadProvider implements RuntimeProvider {

        private final RuntimeId id;
        final CountDownLatch loadEntered = new CountDownLatch(1);
        final CountDownLatch releaseLoad = new CountDownLatch(1);
        final AtomicInteger openCount = new AtomicInteger();
        final AtomicInteger runtimeCloseCount = new AtomicInteger();
        final List<ModelHandleCloseRaceTest.CountingRuntimeModel> models =
                new ArrayList<ModelHandleCloseRaceTest.CountingRuntimeModel>();
        private final boolean failAfterRelease;

        GatedRuntimeLoadProvider(String runtimeId, boolean failAfterRelease) {
            this.id = RuntimeId.of(runtimeId);
            this.failAfterRelease = failAfterRelease;
        }

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
                    .task(io.cortexj.model.TaskCapability.TEXT_GENERATION)
                    .maturity(RuntimeDescriptor.Maturity.STABLE)
                    .build();
        }

        @Override
        public RuntimeProbeResult probe(RuntimeProbeContext context) {
            return RuntimeProbeResult.available("gated runtime ready");
        }

        @Override
        public RuntimeSupport evaluate(RuntimeEvaluationRequest request) {
            return RuntimeSupport.full(DeviceType.CPU);
        }

        @Override
        public ModelRuntime open(final RuntimeOpenRequest request) {
            openCount.incrementAndGet();
            return new ModelRuntime() {
                @Override
                public RuntimeId runtimeId() {
                    return id;
                }

                @Override
                public RuntimeModel load(RuntimeModelRequest loadRequest) {
                    loadEntered.countDown();
                    try {
                        releaseLoad.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    if (failAfterRelease) {
                        throw new ModelLoadException(ErrorCode.LOAD_FAILED,
                                "simulated runtime load failure after cancel");
                    }
                    ModelHandleCloseRaceTest.CountingRuntimeModel model =
                            new ModelHandleCloseRaceTest.CountingRuntimeModel();
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

    @Test
    void cancelDuringRuntimeLoadLeavesNoRegisteredHandle() throws Exception {
        GatedRuntimeLoadProvider provider = new GatedRuntimeLoadProvider("gated-load-runtime", false);
        DefaultCortexJ platform = (DefaultCortexJ) CortexJ.builder()
                .runtimeProvider(provider)
                .repositoryProvider(new EndToEndLoadTest.FakeRepository())
                .workingDirectory(tempDir.resolve("work"))
                .build();
        try {
            Future<ModelHandle> future = platform.models().loadAsync(ModelReference.of("e2e/test-model"));
            assertTrue(provider.loadEntered.await(10, TimeUnit.SECONDS),
                    "the async load must reach the runtime load phase");
            assertTrue(future.cancel(true), "cancel() on a running load must succeed");

            provider.releaseLoad.countDown();
            DefaultModels models = (DefaultModels) platform.models();
            long deadline = System.currentTimeMillis() + 10_000L;
            while (models.openHandleCount() > 0) {
                if (System.currentTimeMillis() > deadline) {
                    throw new AssertionError(
                            "a handle remained registered after the load was cancelled during runtime.load()");
                }
                Thread.sleep(10);
            }
            while (provider.runtimeCloseCount.get() == 0) {
                if (System.currentTimeMillis() > deadline) {
                    throw new AssertionError(
                            "the runtime opened for the cancelled load must be closed");
                }
                Thread.sleep(10);
            }

            assertTrue(future.isCancelled());
            try {
                future.get(10, TimeUnit.SECONDS);
                throw new AssertionError("get() on a cancelled load must throw");
            } catch (CancellationException expected) {
            }
            assertEquals(1, provider.runtimeCloseCount.get(),
                    "the runtime opened for the cancelled load must be closed");
            assertEquals(1, provider.models.get(0).closeCount.get(),
                    "the runtime model created for the cancelled load must be closed");
        } finally {
            platform.close();
        }
    }

    @Test
    void cancelDuringRuntimeLoadAbortsRemainingFallbackCandidates() throws Exception {
        GatedRuntimeLoadProvider firstCandidate = new GatedRuntimeLoadProvider("aaa-cancel-runtime", true);
        GatedRuntimeLoadProvider secondCandidate = new GatedRuntimeLoadProvider("zzz-cancel-runtime", false);
        DefaultCortexJ platform = (DefaultCortexJ) CortexJ.builder()
                .runtimeProvider(firstCandidate)
                .runtimeProvider(secondCandidate)
                .repositoryProvider(new EndToEndLoadTest.FakeRepository())
                .workingDirectory(tempDir.resolve("work"))
                .build();
        try {
            ExecutionPlan plan = platform.planner()
                    .plan(ExecutionRequest.forModel(ModelReference.of("e2e/test-model")));
            String selectedRuntimeId = plan.selected().runtimeId();
            GatedRuntimeLoadProvider selected =
                    firstCandidate.id().value().equals(selectedRuntimeId) ? firstCandidate : secondCandidate;
            GatedRuntimeLoadProvider other = selected == firstCandidate ? secondCandidate : firstCandidate;

            Future<ModelHandle> future = platform.models().loadAsync(ModelReference.of("e2e/test-model"));
            assertTrue(selected.loadEntered.await(10, TimeUnit.SECONDS),
                    "the async load must reach the selected runtime load phase");
            assertTrue(future.cancel(true), "cancel() on a running load must succeed");

            selected.releaseLoad.countDown();
            DefaultModels models = (DefaultModels) platform.models();
            long deadline = System.currentTimeMillis() + 10_000L;
            while (models.openHandleCount() > 0) {
                if (System.currentTimeMillis() > deadline) {
                    throw new AssertionError(
                            "a handle remained registered after the load was cancelled during runtime.load()");
                }
                Thread.sleep(10);
            }
            while (selected.runtimeCloseCount.get() == 0) {
                if (System.currentTimeMillis() > deadline) {
                    throw new AssertionError(
                            "the runtime opened for the cancelled load must be closed on the failure path");
                }
                Thread.sleep(10);
            }

            assertTrue(future.isCancelled());
            assertEquals(0, other.openCount.get(),
                    "cancellation must stop the load from trying the remaining fallback runtimes");
            assertEquals(1, selected.runtimeCloseCount.get(),
                    "the runtime opened for the cancelled load must be closed on the failure path");
        } finally {
            platform.close();
        }
    }

    @Test
    void downloadPoolSaturationIsLoudTypedRejection() throws Exception {
        // audit 2026-10-07 (#146): loadAsync runs on the dedicated load pool
        // now - saturate THAT pool (download pool saturation is covered by
        // the downloadInsensitiveToLoadSaturation test below)
        String oldWidth = System.getProperty("cortexj.load.width");
        String oldQueue = System.getProperty("cortexj.load.queue");
        System.setProperty("cortexj.load.width", "1");
        System.setProperty("cortexj.load.queue", "1");
        GatedRuntimeLoadProvider provider = new GatedRuntimeLoadProvider("gated-saturate-runtime", false);
        DefaultCortexJ platform = null;
        try {
            platform = (DefaultCortexJ) CortexJ.builder()
                    .runtimeProvider(provider)
                    .repositoryProvider(new EndToEndLoadTest.FakeRepository())
                    .workingDirectory(tempDir.resolve("saturate-work"))
                    .build();
            Future<ModelHandle> running = platform.models().loadAsync(ModelReference.of("e2e/test-model"));
            assertTrue(provider.loadEntered.await(10, TimeUnit.SECONDS),
                    "the first load must occupy the single download thread");
            Future<ModelHandle> queued = platform.models().loadAsync(ModelReference.of("e2e/test-model"));
            Future<ModelHandle> rejected = platform.models().loadAsync(ModelReference.of("e2e/test-model"));
            try {
                rejected.get(5, TimeUnit.SECONDS);
                throw new AssertionError("a saturated download pool must not accept the third load");
            } catch (java.util.concurrent.ExecutionException expected) {
                assertTrue(expected.getCause() instanceof ModelLoadException,
                        "rejection must surface as a typed ModelLoadException, got: " + expected.getCause());
                assertTrue(expected.getCause().getMessage().contains("too many concurrent loads"),
                        "rejection must explain the saturation, got: " + expected.getCause().getMessage());
            }
            assertTrue(!queued.isCancelled(), "the queued load must survive saturation");
            provider.releaseLoad.countDown();
            running.get(10, TimeUnit.SECONDS);
            queued.get(10, TimeUnit.SECONDS);
        } finally {
            if (platform != null) {
                provider.releaseLoad.countDown();
                platform.close();
            }
            if (oldWidth == null) {
                System.clearProperty("cortexj.load.width");
            } else {
                System.setProperty("cortexj.load.width", oldWidth);
            }
            if (oldQueue == null) {
                System.clearProperty("cortexj.load.queue");
            } else {
                System.setProperty("cortexj.load.queue", oldQueue);
            }
        }
    }

    @Test
    void downloadPoolSaturationDoesNotBlockLoads() throws Exception {
        // audit 2026-10-07 (#146): the split contract - a saturated download
        // pool must not starve loadAsync (and vice versa). Here the download
        // pool is narrowed to 1x1 while the load pool stays wide: loads must
        // still go through even though downloads would queue.
        String oldWidth = System.getProperty("cortexj.download.width");
        String oldQueue = System.getProperty("cortexj.download.queue");
        System.setProperty("cortexj.download.width", "1");
        System.setProperty("cortexj.download.queue", "1");
        GatedRuntimeLoadProvider provider = new GatedRuntimeLoadProvider("gated-split-runtime", false);
        DefaultCortexJ platform = null;
        try {
            platform = (DefaultCortexJ) CortexJ.builder()
                    .runtimeProvider(provider)
                    .repositoryProvider(new EndToEndLoadTest.FakeRepository())
                    .workingDirectory(tempDir.resolve("split-work"))
                    .build();
            Future<ModelHandle> first = platform.models().loadAsync(ModelReference.of("e2e/test-model"));
            assertTrue(provider.loadEntered.await(10, TimeUnit.SECONDS),
                    "the first load must start on the wide load pool");
            Future<ModelHandle> second = platform.models().loadAsync(ModelReference.of("e2e/test-model"));
            provider.releaseLoad.countDown();
            first.get(10, TimeUnit.SECONDS).close();
            second.get(10, TimeUnit.SECONDS).close();
        } finally {
            if (platform != null) {
                provider.releaseLoad.countDown();
                platform.close();
            }
            if (oldWidth == null) {
                System.clearProperty("cortexj.download.width");
            } else {
                System.setProperty("cortexj.download.width", oldWidth);
            }
            if (oldQueue == null) {
                System.clearProperty("cortexj.download.queue");
            } else {
                System.setProperty("cortexj.download.queue", oldQueue);
            }
        }
    }
}
