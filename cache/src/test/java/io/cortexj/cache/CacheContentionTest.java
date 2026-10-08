package io.cortexj.cache;

import io.cortexj.model.Checksum;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Audit 2026-10-08: contention proof - mixed get/put/evict traffic must
 * complete without deadlock and without pathological slowdown after the
 * touch-throttle change.
 */
@Tag("perf")
class CacheContentionTest {

    @TempDir
    Path tempDir;

    private static InputStream bytes(byte[] value) {
        return new ByteArrayInputStream(value);
    }

    @Test
    void mixedTrafficStaysBounded() throws Exception {
        final CacheStorage storage = new CacheStorage(tempDir.resolve("cache"));
        final int keys = 64;
        final byte[] payload = new byte[64 * 1024];
        new java.util.Random(42).nextBytes(payload);
        final String checksum = Checksums.sha256Hex(payload);
        for (int k = 0; k < keys; k++) {
            storage.put("repo", "rev", "a-" + k + ".gguf",
                    Checksum.sha256(checksum), bytes(payload));
        }
        final int writers = 4;
        final int readers = 8;
        final int rounds = 100;
        final CountDownLatch start = new CountDownLatch(1);
        final CountDownLatch done = new CountDownLatch(writers + readers);
        final AtomicInteger errors = new AtomicInteger();
        final AtomicLong gets = new AtomicLong();
        final AtomicLong puts = new AtomicLong();
        for (int w = 0; w < writers; w++) {
            final int slot = w;
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await(10, TimeUnit.SECONDS);
                        for (int round = 0; round < rounds; round++) {
                            try {
                                storage.put("repo", "rev", "w-" + slot + "-" + round + ".gguf",
                                        Checksum.sha256(checksum), bytes(payload));
                                puts.incrementAndGet();
                            } catch (Exception e) {
                                errors.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                }
            });
            thread.setDaemon(true);
            thread.start();
        }
        for (int r = 0; r < readers; r++) {
            Thread thread = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await(10, TimeUnit.SECONDS);
                        java.util.Random random = new java.util.Random();
                        for (int round = 0; round < rounds * 4; round++) {
                            try {
                                storage.get("repo", "rev",
                                        "a-" + random.nextInt(keys) + ".gguf", null);
                                gets.incrementAndGet();
                            } catch (Exception e) {
                                errors.incrementAndGet();
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        done.countDown();
                    }
                }
            });
            thread.setDaemon(true);
            thread.start();
        }
        start.countDown();
        long started = System.nanoTime();
        assertTrue(done.await(120, TimeUnit.SECONDS), "mixed traffic must finish without deadlock");
        long millis = (System.nanoTime() - started) / 1_000_000L;
        assertEquals(0, errors.get(), "no operation may fail under contention");
        System.out.println("cache contention: " + gets + " gets + " + puts + " puts in "
                + millis + " ms");
        assertTrue(millis < 100_000L, "contention must stay bounded, took " + millis + " ms");
    }
}
