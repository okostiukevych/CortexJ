package io.cortexj.runtime.llamacpp;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import io.cortexj.inference.InferenceRequest;
import io.cortexj.inference.InferenceResponse;
import io.cortexj.model.ModelDescriptor;
import io.cortexj.spi.runtime.RuntimeModel;
import io.cortexj.spi.runtime.RuntimeModelRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlamaServerModelAsyncTest {

    private HttpServer server;
    private int port;
    private final AtomicInteger stalledRequests = new AtomicInteger();
    private CountDownLatch releaseStall = new CountDownLatch(1);

    @TempDir
    Path tempDir;

    @BeforeEach
    void startServer() throws IOException {
        stalledRequests.set(0);
        releaseStall = new CountDownLatch(1);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newFixedThreadPool(
                Math.max(8, 2 * Runtime.getRuntime().availableProcessors()) + 4));
        port = server.getAddress().getPort();
        server.createContext("/health", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            }
        });
        server.createContext("/v1/chat/completions", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                InputStream input = exchange.getRequestBody();
                byte[] buffer = new byte[4096];
                StringBuilder body = new StringBuilder();
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    body.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                }
                input.close();
                if (body.toString().contains("stall-please")) {
                    stalledRequests.incrementAndGet();
                    try {
                        releaseStall.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                byte[] payload = ("{\"choices\":[{\"message\":{\"role\":\"assistant\","
                        + "\"content\":\"managed hello\"}}],"
                        + "\"usage\":{\"prompt_tokens\":2,\"completion_tokens\":7}}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, payload.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(payload);
                }
            }
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        releaseStall.countDown();
        server.stop(0);
    }

    static final class RecordingExecutor extends AbstractExecutorService {

        final AtomicInteger executeCount = new AtomicInteger();
        final AtomicReference<Thread> ranOn = new AtomicReference<Thread>();
        private final ExecutorService delegate = Executors.newSingleThreadExecutor(
                io.cortexj.spi.platform.PlatformModule.daemonThreadFactory("cortexj-test-async"));

        @Override
        public void execute(Runnable command) {
            executeCount.incrementAndGet();
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

    private RuntimeModel loadModel(ExecutorService executor) throws IOException {
        LlamaCppProvider provider = new LlamaCppProvider(new LlamaServerLauncher() {
            @Override
            public LlamaServerEndpoint launch(Path artifactPath) {
                return new LlamaServerEndpoint() {
                    @Override
                    public String baseUrl() {
                        return "http://127.0.0.1:" + port;
                    }

                    @Override
                    public void stop() {
                    }
                };
            }
        });
        Path gguf = tempDir.resolve("model.gguf");
        Files.write(gguf, "gguf-bytes".getBytes(StandardCharsets.UTF_8));
        io.cortexj.spi.runtime.ModelRuntime runtime = provider.open(
                new io.cortexj.spi.runtime.RuntimeOpenRequest(
                        ModelDescriptor.builder("t/llama").build(),
                        null, gguf, io.cortexj.runtime.DeviceType.CPU,
                        java.util.Collections.<String, Object>emptyMap(),
                        java.util.Collections.<String, String>emptyMap(),
                        null, null, executor, null));
        return runtime.load(new RuntimeModelRequest(
                ModelDescriptor.builder("t/llama").build(),
                null, gguf, io.cortexj.runtime.DeviceType.CPU,
                java.util.Collections.<String, Object>emptyMap()));
    }

    private static java.util.Set<Long> threadIdsNamed(String prefix) {
        java.util.Set<Long> ids = new java.util.HashSet<Long>();
        for (java.util.Map.Entry<Thread, java.lang.StackTraceElement[]> entry
                : Thread.getAllStackTraces().entrySet()) {
            if (entry.getKey().isAlive() && entry.getKey().getName().startsWith(prefix)) {
                ids.add(Long.valueOf(entry.getKey().getId()));
            }
        }
        return ids;
    }

    @Test
    void executeAsyncRunsOnSuppliedExecutor() throws Exception {
        RecordingExecutor executor = new RecordingExecutor();
        RuntimeModel model = loadModel(executor);
        try {
            InferenceRequest request = InferenceRequest.builder().input("hi").build();
            InferenceResponse response = model.executeAsync(request).toCompletableFuture()
                    .get(15, TimeUnit.SECONDS);
            assertEquals("managed hello", response.singleOutput());
            assertEquals(1, executor.executeCount.get(),
                    "executeAsync must dispatch through the supplied executor");
            assertTrue(executor.ranOn.get() != null
                            && executor.ranOn.get().getName().startsWith("cortexj-test-async"),
                    "executeAsync must run on a supplied-executor thread");
            assertTrue(executor.ranOn.get() != Thread.currentThread(),
                    "executeAsync must not run on the caller thread");
        } finally {
            model.close();
            executor.shutdown();
        }
    }

    @Test
    void executeAsyncDoesNotBlockCallerOnSlowEndpoint() throws Exception {
        RuntimeModel model = loadModel(null);
        try {
            InferenceRequest request = InferenceRequest.builder().input("stall-please").build();
            long started = System.nanoTime();
            CompletableFuture<InferenceResponse> future =
                    model.executeAsync(request).toCompletableFuture();
            long scheduledMillis = (System.nanoTime() - started) / 1_000_000L;
            assertTrue(scheduledMillis < 2_000,
                    "executeAsync must return without blocking on the response (took "
                            + scheduledMillis + "ms)");
            assertTrue(!future.isDone(),
                    "the response must not be complete while the endpoint stalls");
            releaseStall.countDown();
            InferenceResponse response = future.get(15, TimeUnit.SECONDS);
            assertEquals("managed hello", response.singleOutput());
        } finally {
            releaseStall.countDown();
            model.close();
        }
    }

    @Test
    void executeAsyncWithoutExecutorStillCompletesViaOwnedFallback() throws Exception {
        RuntimeModel model = loadModel(null);
        try {
            InferenceRequest request = InferenceRequest.builder().input("hi").build();
            InferenceResponse response = model.executeAsync(request).toCompletableFuture()
                    .get(15, TimeUnit.SECONDS);
            assertEquals("managed hello", response.singleOutput());
        } finally {
            model.close();
        }
    }

    @Test
    void fallbackExecutorIsSizedLikePlatformDefault() throws Exception {
        final int expected = Math.max(8, 2 * Runtime.getRuntime().availableProcessors());
        ExecutorService pool = LlamaServerModel.fallbackIoExecutor();
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch allRunning = new CountDownLatch(expected);
        final java.util.Set<Long> workerIds =
                java.util.Collections.newSetFromMap(
                        new java.util.concurrent.ConcurrentHashMap<Long, Boolean>());
        final AtomicBoolean allDaemon = new AtomicBoolean(true);
        try {
            for (int i = 0; i < expected; i++) {
                pool.execute(new Runnable() {
                    @Override
                    public void run() {
                        workerIds.add(Long.valueOf(Thread.currentThread().getId()));
                        if (!Thread.currentThread().isDaemon()) {
                            allDaemon.set(false);
                        }
                        allRunning.countDown();
                        try {
                            release.await(30, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                });
            }
            assertTrue(allRunning.await(10, TimeUnit.SECONDS),
                    "the fallback executor must run " + expected + " tasks concurrently");
            assertEquals(expected, workerIds.size(),
                    "the fallback executor must be a fixed pool of max(8, 2*cores) threads, "
                            + "got: " + workerIds.size());
            assertTrue(allDaemon.get(), "fallback executor threads must be daemon");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void fallbackExecutorServesConcurrentStreamsOfOneModel() throws Exception {
        int expected = Math.max(8, 2 * Runtime.getRuntime().availableProcessors());
        java.util.Set<Long> preExisting = threadIdsNamed("cortexj-llama-sse");
        RuntimeModel model = loadModel(null);
        try {
            final java.util.List<CompletableFuture<InferenceResponse>> futures =
                    new java.util.ArrayList<CompletableFuture<InferenceResponse>>();
            for (int i = 0; i < expected; i++) {
                futures.add(model.executeAsync(
                        InferenceRequest.builder().input("stall-please").build())
                        .toCompletableFuture());
            }
            long deadline = System.currentTimeMillis() + 30_000;
            while (stalledRequests.get() < expected && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(expected, stalledRequests.get(),
                    "all " + expected + " requests must run concurrently on the "
                            + "fallback executor instead of serializing behind one worker");

            java.util.Set<Long> newWorkerIds = new java.util.HashSet<Long>();
            for (java.util.Map.Entry<Thread, java.lang.StackTraceElement[]> entry
                    : Thread.getAllStackTraces().entrySet()) {
                Thread thread = entry.getKey();
                if (thread.isAlive() && thread.getName().startsWith("cortexj-llama-sse-")
                        && !preExisting.contains(Long.valueOf(thread.getId()))) {
                    newWorkerIds.add(Long.valueOf(thread.getId()));
                    assertTrue(thread.isDaemon(), "fallback executor threads must be daemon");
                }
            }
            assertEquals(expected, newWorkerIds.size(),
                    "the fallback executor must expose max(8, 2*cores) worker threads");

            releaseStall.countDown();
            for (CompletableFuture<InferenceResponse> future : futures) {
                assertEquals("managed hello", future.get(15, TimeUnit.SECONDS).singleOutput());
            }
        } finally {
            releaseStall.countDown();
            model.close();
        }
    }
}
