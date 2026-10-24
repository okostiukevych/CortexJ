package io.cortexj.cache;

import io.cortexj.model.Checksum;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("concurrency")
class CacheIndexReconciliationStressTest {

    @TempDir
    Path tempDir;

    private static InputStream bytes(int size, char fill) {
        byte[] data = new byte[size];
        Arrays.fill(data, (byte) fill);
        return new ByteArrayInputStream(data);
    }

    private static String sha256Hex(String value) {
        return Checksums.sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void heavyConcurrentClearPurgePutKeepsIndexAccurate() throws Exception {
        // 64KB cap: every put then triggers an evictToFit() tree walk, which is
        // the traffic that exposed the phantom-credit race in CI (macOS JDK 8)
        final CacheStorage storage = new CacheStorage(tempDir, 64L * 1024L);
        final Checksum pinned = Checksum.sha256(sha256Hex("pinned-payload"));
        storage.put("hf", "rev1", "pinned.gguf", pinned,
                new ByteArrayInputStream("pinned-payload".getBytes(StandardCharsets.UTF_8)));
        storage.pin("hf", "rev1", "pinned.gguf");

        final int writers = 8;
        final int clearers = 4;
        final int rounds = 300;
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicInteger puts = new AtomicInteger();
        final AtomicInteger clears = new AtomicInteger();
        Thread[] threads = new Thread[writers + clearers];
        for (int i = 0; i < threads.length; i++) {
            final boolean clearer = i >= writers;
            final int writerIndex = i;
            threads[i] = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int round = 0; round < rounds; round++) {
                        try {
                            if (clearer) {
                                if (round % 2 == 0) {
                                    storage.clear();
                                } else {
                                    storage.purge();
                                }
                                clears.incrementAndGet();
                            } else {
                                storage.put("hf", "rev" + round, "w-" + writerIndex
                                        + "-artifact-" + round + ".gguf", null, bytes(2048, 'f'));
                                puts.incrementAndGet();
                            }
                        } catch (IOException tolerated) {
                            // a sweep may remove an in-flight temp: no accounting impact
                        }
                    }
                }
            });
            threads[i].start();
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join(120_000);
        }

        assertTrue(puts.get() > 0 && clears.get() > 0, "both sides must have run");
        assertTrue(storage.isPinned("hf", "rev1", "pinned.gguf"), "pinned survived");
        assertTrue(Files.isRegularFile(storage.artifactPath("hf", "rev1", "pinned.gguf")),
                "pinned file survived");
        long tree = storage.totalSizeBytes();
        long index = storage.trackedBytesForTesting();
        assertEquals(tree, index, "index must match the real tree");
        long listed = 0L;
        for (CacheEntry entry : storage.list()) {
            listed += entry.sizeBytes();
        }
        assertEquals(listed, index, "index must match the walk (no temp/artifact drift)");
    }
}
