package io.cortexj.runtime.llamacpp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SharedEndpointRegistryTest {

    @TempDir
    Path workDir;

    static final class CountingEndpoint implements LlamaServerEndpoint {

        final String baseUrl;
        final AtomicInteger stopCount = new AtomicInteger();

        CountingEndpoint(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        @Override
        public String baseUrl() {
            return baseUrl;
        }

        @Override
        public void stop() {
            stopCount.incrementAndGet();
        }
    }

    static final class CountingLauncher implements LlamaServerLauncher {

        final AtomicInteger launchCount = new AtomicInteger();
        final CopyOnWriteArrayList<CountingEndpoint> launched = new CopyOnWriteArrayList<CountingEndpoint>();
        final long launchDelayMillis;

        CountingLauncher() {
            this(0);
        }

        CountingLauncher(long launchDelayMillis) {
            this.launchDelayMillis = launchDelayMillis;
        }

        @Override
        public LlamaServerEndpoint launch(Path artifactPath) throws IOException {
            launchCount.incrementAndGet();
            if (launchDelayMillis > 0) {
                try {
                    Thread.sleep(launchDelayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            CountingEndpoint endpoint = new CountingEndpoint(
                    "http://127.0.0.1:" + (9000 + launchCount.get()));
            launched.add(endpoint);
            return endpoint;
        }
    }

    private Path artifact(String name) throws IOException {
        Path file = workDir.resolve(name);
        java.nio.file.Files.write(file, "gguf".getBytes());
        return file;
    }

    @Test
    void twoAcquiresOfSameArtifactShareOneEndpoint() throws Exception {
        CountingLauncher launcher = new CountingLauncher();
        SharedEndpointRegistry registry = new SharedEndpointRegistry();
        Path model = artifact("model.gguf");
        String key = model.toAbsolutePath().toString();

        SharedEndpointRegistry.SharedEndpoint first = registry.acquire(key, launcher, model);
        SharedEndpointRegistry.SharedEndpoint second = registry.acquire(key, launcher, model);

        assertEquals(1, launcher.launchCount.get(),
                "two loads of the same model must launch exactly one llama-server process");
        assertEquals(first.baseUrl(), second.baseUrl());

        first.release();
        assertEquals(0, secondBasStops(launcher),
                "closing one handle must not stop the shared process");

        second.release();
        assertEquals(1, secondBasStops(launcher),
                "closing the last handle must stop the shared process exactly once");
        assertEquals(0, registry.liveEndpointCount());
    }

    private static int secondBasStops(CountingLauncher launcher) {
        int total = 0;
        for (CountingEndpoint endpoint : launcher.launched) {
            total += endpoint.stopCount.get();
        }
        return total;
    }

    @Test
    void differentArtifactsGetIsolatedProcesses() throws Exception {
        CountingLauncher launcher = new CountingLauncher();
        SharedEndpointRegistry registry = new SharedEndpointRegistry();

        SharedEndpointRegistry.SharedEndpoint first =
                registry.acquire(artifact("a.gguf").toAbsolutePath().toString(), launcher,
                        artifact("a.gguf"));
        SharedEndpointRegistry.SharedEndpoint second =
                registry.acquire(artifact("b.gguf").toAbsolutePath().toString(), launcher,
                        artifact("b.gguf"));

        assertEquals(2, launcher.launchCount.get(),
                "different artifacts must get isolated llama-server processes");
        first.release();
        second.release();
        assertEquals(2, secondBasStops(launcher));
        assertEquals(0, registry.liveEndpointCount());
    }

    @Test
    void concurrentAcquiresLaunchExactlyOnce() throws Exception {
        CountingLauncher launcher = new CountingLauncher(100);
        SharedEndpointRegistry registry = new SharedEndpointRegistry();
        Path model = artifact("shared.gguf");
        String key = model.toAbsolutePath().toString();
        int threads = 8;
        final CyclicBarrier barrier = new CyclicBarrier(threads);
        final CountDownLatch done = new CountDownLatch(threads);
        final java.util.List<String> baseUrls = Collections.synchronizedList(
                new java.util.ArrayList<String>());
        final java.util.List<SharedEndpointRegistry.SharedEndpoint> acquired =
                Collections.synchronizedList(
                        new java.util.ArrayList<SharedEndpointRegistry.SharedEndpoint>());
        final java.util.List<Exception> failures = Collections.synchronizedList(
                new java.util.ArrayList<Exception>());

        for (int i = 0; i < threads; i++) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        barrier.await(10, TimeUnit.SECONDS);
                        SharedEndpointRegistry.SharedEndpoint endpoint =
                                registry.acquire(key, launcher, model);
                        baseUrls.add(endpoint.baseUrl());
                        acquired.add(endpoint);
                    } catch (Exception failure) {
                        failures.add(failure);
                    } finally {
                        done.countDown();
                    }
                }
            }).start();
        }
        assertTrue(done.await(15, TimeUnit.SECONDS), "all acquires must complete");
        assertTrue(failures.isEmpty(),
                "no acquire may fail: " + (failures.isEmpty() ? "" : failures.get(0)));
        assertEquals(threads, baseUrls.size());
        assertEquals(1, launcher.launchCount.get(),
                "concurrent loads of one model must launch exactly one llama-server process");
        assertEquals(1, baseUrls.stream().distinct().count(),
                "all handles must share the same endpoint");

        for (SharedEndpointRegistry.SharedEndpoint endpoint : acquired) {
            endpoint.release();
        }
        assertEquals(1, secondBasStops(launcher),
                "when every handle detaches, the shared process must stop exactly once");
        assertEquals(0, registry.liveEndpointCount());
    }

    @Test
    void releaseRacingTryRetainStopsExactlyOnceAndNeverOrphans() throws Exception {
        CountingLauncher launcher = new CountingLauncher();
        SharedEndpointRegistry registry = new SharedEndpointRegistry();
        Path model = artifact("race.gguf");
        String key = model.toAbsolutePath().toString();
        int rounds = 200;
        final int retainers = 3;

        for (int round = 0; round < rounds; round++) {
            final SharedEndpointRegistry.SharedEndpoint endpoint =
                    registry.acquire(key, launcher, model);
            final CyclicBarrier barrier = new CyclicBarrier(retainers + 1);
            final AtomicInteger retained = new AtomicInteger();
            final java.util.List<Thread> racers = new java.util.ArrayList<Thread>();

            Thread releaser = new Thread(new Runnable() {
                @Override
                public void run() {
                    awaitQuietly(barrier);
                    endpoint.release();
                }
            }, "releaser-" + round);
            racers.add(releaser);
            for (int i = 0; i < retainers; i++) {
                final String name = "retainer-" + round + "-" + i;
                Thread retainer = new Thread(new Runnable() {
                    @Override
                    public void run() {
                        awaitQuietly(barrier);
                        if (endpoint.tryRetain()) {
                            retained.incrementAndGet();
                        }
                    }
                }, name);
                racers.add(retainer);
            }
            for (Thread racer : racers) {
                racer.start();
            }
            for (Thread racer : racers) {
                racer.join(10_000);
            }
            for (Thread racer : racers) {
                assertTrue(!racer.isAlive(),
                        "racer thread " + racer.getName() + " must finish");
            }

            if (retained.get() > 0) {
                assertEquals(retainers, retained.get(),
                        "once one retain beats the release the endpoint stays registered, "
                                + "so every remaining retain must succeed too");
                for (int i = 0; i < retained.get(); i++) {
                    endpoint.release();
                }
            }
            assertEquals(round + 1, secondBasStops(launcher),
                    "round " + round + ": stop must fire exactly once - a lost stop "
                            + "orphans the multi-GB llama-server process");
            assertEquals(0, registry.liveEndpointCount(),
                    "round " + round + ": no endpoint may be left in the registry");
            assertEquals(0, registry.liveKeyLockCount(),
                    "round " + round + ": stale per-key locks must be dropped at full stop");
            assertEquals(round + 1, launcher.launchCount.get(),
                    "round " + round + ": each round must start from a fully stopped endpoint");
        }
    }

    @Test
    void releaseWithLiveHolderKeepsRegistryEntryAndAllowsReuse() throws Exception {
        CountingLauncher launcher = new CountingLauncher();
        SharedEndpointRegistry registry = new SharedEndpointRegistry();
        Path model = artifact("holder.gguf");
        String key = model.toAbsolutePath().toString();

        SharedEndpointRegistry.SharedEndpoint first = registry.acquire(key, launcher, model);
        SharedEndpointRegistry.SharedEndpoint second = registry.acquire(key, launcher, model);
        assertEquals(1, launcher.launchCount.get(),
                "two loads must share one process");

        first.release();
        assertEquals(1, registry.liveEndpointCount(),
                "the registry entry must remain while another handle still holds");
        assertEquals(0, secondBasStops(launcher),
                "no stop may fire while a real holder remains");
        assertEquals(1, registry.liveKeyLockCount(),
                "the per-key lock must remain while the endpoint lives");

        SharedEndpointRegistry.SharedEndpoint third = registry.acquire(key, launcher, model);
        assertEquals(1, launcher.launchCount.get(),
                "an acquire after a partial release must reuse the same endpoint");
        assertEquals(first.baseUrl(), third.baseUrl(),
                "the reused endpoint must be the same process");

        second.release();
        third.release();
        assertEquals(0, registry.liveEndpointCount(),
                "the entry must be removed only when the refcount reaches zero");
        assertEquals(1, secondBasStops(launcher),
                "the last holder's release must stop the process exactly once");
        assertEquals(0, registry.liveKeyLockCount(),
                "stale per-key locks must be dropped when the endpoint is fully stopped");
    }

    private static void awaitQuietly(CyclicBarrier barrier) {
        try {
            barrier.await(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
