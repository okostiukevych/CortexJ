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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("concurrency")
class CacheClearProtectionTest {

    @TempDir
    Path tempDir;

    private static InputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private static InputStream bytes(int size, char fill) {
        byte[] data = new byte[size];
        java.util.Arrays.fill(data, (byte) fill);
        return new ByteArrayInputStream(data);
    }

    private static String sha256Hex(String value) {
        return Checksums.sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }

    private Path registerPartial(String name, String content) throws IOException {
        Path partial = tempDir.resolve("downloads-partial").resolve(name);
        Files.createDirectories(partial.getParent());
        Files.write(partial, content.getBytes(StandardCharsets.UTF_8));
        return partial;
    }

    @Test
    void clearKeepsPinnedEntriesAndActivePartials() throws IOException {
        CacheStorage storage = new CacheStorage(tempDir);
        Checksum pinnedChecksum = Checksum.sha256(sha256Hex("pinned-content"));
        storage.put("hf", "rev1", "pinned.gguf", pinnedChecksum, bytes("pinned-content"));
        storage.pin("hf", "rev1", "pinned.gguf");
        storage.put("hf", "rev1", "doomed-a.gguf", null, bytes("doomed-a"));
        storage.put("hf", "rev1", "doomed-b.gguf", null, bytes("doomed-b"));
        long pinnedBytes = Files.size(storage.artifactPath("hf", "rev1", "pinned.gguf"));
        long doomedBytes = storage.totalSizeBytes() - pinnedBytes;

        Path partial = registerPartial("busy.bin.part", "partial-resume-bytes");
        storage.registerActivePartial(partial);

        long freed = storage.clear();

        assertEquals(doomedBytes, freed,
                "freed must count only the entries actually deleted");
        assertTrue(storage.contains("hf", "rev1", "pinned.gguf"),
                "a pinned artifact must survive clear()");
        assertTrue(storage.isPinned("hf", "rev1", "pinned.gguf"),
                "clear() must not destroy the pinned marker");
        assertEquals("pinned-content",
                new String(Files.readAllBytes(storage.artifactPath("hf", "rev1", "pinned.gguf")),
                        StandardCharsets.UTF_8),
                "the pinned artifact content must be intact after clear()");
        assertTrue(Files.isRegularFile(partial),
                "an active download partial must survive clear()");
        assertFalse(storage.contains("hf", "rev1", "doomed-a.gguf"),
                "unpinned artifacts must be removed by clear()");
        assertFalse(storage.contains("hf", "rev1", "doomed-b.gguf"),
                "unpinned artifacts must be removed by clear()");
        assertEquals(pinnedBytes, storage.trackedBytesForTesting(),
                "the size index must stay accurate: pinned bytes kept, "
                        + "deleted bytes dropped, partial bytes never tracked");
    }

    @Test
    void purgeKeepsPinnedEntriesAndActivePartials() throws IOException {
        CacheStorage storage = new CacheStorage(tempDir);
        Checksum pinnedChecksum = Checksum.sha256(sha256Hex("pinned-content"));
        storage.put("hf", "rev1", "pinned.gguf", pinnedChecksum, bytes("pinned-content"));
        storage.pin("hf", "rev1", "pinned.gguf");
        storage.put("hf", "rev1", "doomed-a.gguf", null, bytes("doomed-a"));
        storage.put("hf", "rev1", "doomed-b.gguf", null, bytes("doomed-b"));
        long pinnedBytes = Files.size(storage.artifactPath("hf", "rev1", "pinned.gguf"));
        long doomedBytes = storage.totalSizeBytes() - pinnedBytes;

        Path partial = registerPartial("busy.bin.part", "partial-resume-bytes");
        storage.registerActivePartial(partial);

        long freed = storage.purge();

        assertEquals(doomedBytes, freed,
                "purge must count freed bytes only for entries actually deleted");
        assertTrue(storage.contains("hf", "rev1", "pinned.gguf"),
                "a pinned artifact must survive purge()");
        assertTrue(storage.isPinned("hf", "rev1", "pinned.gguf"),
                "purge() must not destroy the pinned marker");
        assertTrue(Files.isRegularFile(partial),
                "an active download partial must survive purge()");
        assertFalse(storage.contains("hf", "rev1", "doomed-a.gguf"));
        assertFalse(storage.contains("hf", "rev1", "doomed-b.gguf"));
        assertEquals(pinnedBytes, storage.trackedBytesForTesting(),
                "the size index must stay accurate after purge()");
    }

    @Test
    void clearAndPurgeReturnZeroFreedWhenNothingEvictable() throws IOException {
        CacheStorage storage = new CacheStorage(tempDir);
        Checksum checksum = Checksum.sha256(sha256Hex("pinned-only"));
        storage.put("hf", "rev1", "pinned.gguf", checksum, bytes("pinned-only"));
        storage.pin("hf", "rev1", "pinned.gguf");
        long pinnedBytes = Files.size(storage.artifactPath("hf", "rev1", "pinned.gguf"));

        assertEquals(0L, storage.clear(), "nothing evictable - nothing freed");
        assertEquals(pinnedBytes, storage.trackedBytesForTesting(),
                "the index must keep the pinned bytes when clear deletes nothing");
        assertEquals(0L, storage.purge(), "nothing evictable - nothing freed by purge");
        assertEquals(pinnedBytes, storage.trackedBytesForTesting(),
                "the index must keep the pinned bytes when purge deletes nothing");
        assertTrue(storage.contains("hf", "rev1", "pinned.gguf"));
    }

    @Test
    void concurrentClearAndPutKeepsTrackedBytesAccurate() throws Exception {
        final CacheStorage storage = new CacheStorage(tempDir, 64L * 1024L);
        final Checksum pinnedChecksum = Checksum.sha256(sha256Hex("pinned-payload"));
        storage.put("hf", "rev1", "pinned.gguf", pinnedChecksum, bytes("pinned-payload"));
        storage.pin("hf", "rev1", "pinned.gguf");

        final CountDownLatch start = new CountDownLatch(1);
        final AtomicInteger successfulPuts = new AtomicInteger();
        final AtomicInteger abortedPuts = new AtomicInteger();
        final AtomicInteger clears = new AtomicInteger();
        Thread[] threads = new Thread[6];
        for (int i = 0; i < threads.length; i++) {
            final boolean clearer = i >= 4;
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
                    for (int round = 0; round < 100; round++) {
                        try {
                            if (clearer) {
                                if (round % 2 == 0) {
                                    storage.clear();
                                } else {
                                    storage.purge();
                                }
                                clears.incrementAndGet();
                            } else {
                                storage.put("hf", "rev" + round,
                                        "writer-" + writerIndex + "-artifact-" + round + ".gguf",
                                        null, bytes(2048, 'f'));
                                successfulPuts.incrementAndGet();
                            }
                        } catch (IOException tolerated) {
                            // a clear()/purge() may sweep this put's in-flight
                            // temp - the put aborts with no accounting impact
                            abortedPuts.incrementAndGet();
                        }
                    }
                }
            });
            threads[i].start();
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join(60_000);
        }

        assertTrue(successfulPuts.get() > 0, "the put side must have landed artifacts");
        assertTrue(clears.get() > 0, "the clear side must have run");
        assertTrue(storage.contains("hf", "rev1", "pinned.gguf"),
                "the pinned artifact must survive the clear/put stress");
        assertTrue(storage.isPinned("hf", "rev1", "pinned.gguf"));
        assertEquals(storage.totalSizeBytes(), storage.trackedBytesForTesting(),
                "no lost-update accounting: the size index must match the real "
                        + "tree after concurrent clear()/purge() and put() rounds");
    }
}
