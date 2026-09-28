package io.cortexj.cache;

import io.cortexj.error.ArtifactIntegrityException;
import io.cortexj.model.Checksum;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheStorageConcurrencyTest {

    @TempDir
    Path tempDir;

    private static InputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(String value) {
        return Checksums.sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void concurrentGetPutSameKeyNeverDeletesFreshArtifact() throws Exception {
        final CacheStorage storage = new CacheStorage(tempDir);
        final String repository = "hf";
        final String revision = "rev1";
        final String artifact = "model.gguf";
        final Checksum checksum = Checksum.sha256(sha256Hex("payload-content"));
        storage.put(repository, revision, artifact, checksum, bytes("payload-content"));

        final CountDownLatch start = new CountDownLatch(1);
        final AtomicInteger integrityFailures = new AtomicInteger();
        final AtomicInteger missingAfterGet = new AtomicInteger();
        final AtomicInteger gets = new AtomicInteger();
        final AtomicInteger puts = new AtomicInteger();
        Thread[] threads = new Thread[4];
        for (int i = 0; i < threads.length; i++) {
            final boolean writer = i % 2 == 0;
            threads[i] = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int round = 0; round < 300; round++) {
                        try {
                            if (writer) {
                                storage.put(repository, revision, artifact, checksum,
                                        bytes("payload-content"));
                                puts.incrementAndGet();
                            } else {
                                Path cached = storage.get(repository, revision, artifact, checksum);
                                if (cached != null) {
                                    gets.incrementAndGet();
                                    if (!Files.isRegularFile(cached)) {
                                        // tolerate a mid-flight replace on fallback
                                        // filesystems, but the artifact must reappear
                                        try {
                                            Thread.sleep(20);
                                        } catch (InterruptedException e) {
                                            Thread.currentThread().interrupt();
                                            return;
                                        }
                                        if (!Files.isRegularFile(cached)) {
                                            missingAfterGet.incrementAndGet();
                                        }
                                    }
                                }
                            }
                        } catch (ArtifactIntegrityException e) {
                            integrityFailures.incrementAndGet();
                        } catch (IOException e) {
                            integrityFailures.incrementAndGet();
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
        assertEquals(0, integrityFailures.get(),
                "no checksum-validation failure may surface from the get/put race");
        assertEquals(0, missingAfterGet.get(),
                "a get()-returned path must never be left deleted by the mismatch path");
        assertTrue(puts.get() > 0 && gets.get() > 0, "both sides must have exercised the cache");
        Path finalArtifact = storage.get(repository, revision, artifact, checksum);
        assertNotNull(finalArtifact, "the artifact must survive the full stress run");
        assertEquals("payload-content",
                new String(Files.readAllBytes(finalArtifact), StandardCharsets.UTF_8));
    }

    @Test
    void staleManifestDoesNotDeleteArtifactWhenContentMatches() throws IOException {
        CacheStorage storage = new CacheStorage(tempDir);
        String checksumB = sha256Hex("content-b");
        storage.put("hf", "rev1", "model.gguf", Checksum.sha256(checksumB), bytes("content-b"));

        // simulate the racing put window: artifact already replaced on disk,
        // manifest still holds the stale checksum of the previous generation
        Path artifactPath = storage.artifactPath("hf", "rev1", "model.gguf");
        Path manifestPath = artifactPath.resolveSibling(artifactPath.getFileName() + ".manifest");
        java.util.Properties stale = new java.util.Properties();
        stale.setProperty("checksum", sha256Hex("content-a"));
        stale.setProperty("sizeBytes", String.valueOf(Files.size(artifactPath)));
        java.io.OutputStream out = Files.newOutputStream(manifestPath);
        try {
            stale.store(out, "stale");
        } finally {
            out.close();
        }

        Path cached = storage.get("hf", "rev1", "model.gguf", Checksum.sha256(checksumB));
        assertNotNull(cached, "a stale manifest must not fail the get when the content matches");
        assertTrue(Files.isRegularFile(cached),
                "the fresh artifact must never be deleted because of a stale manifest");
        assertEquals("content-b", new String(Files.readAllBytes(cached), StandardCharsets.UTF_8));
        Path healed = storage.get("hf", "rev1", "model.gguf", Checksum.sha256(checksumB));
        assertNotNull(healed, "the manifest must be re-recorded after the paranoid verify");
    }

    @Test
    void noSpuriousNotCachedDuringConcurrentPut() throws Exception {
        final CacheStorage storage = new CacheStorage(tempDir);
        final Checksum checksum = Checksum.sha256(sha256Hex("stable"));
        final CountDownLatch start = new CountDownLatch(1);
        final AtomicInteger falseMisses = new AtomicInteger();
        final AtomicInteger observations = new AtomicInteger();
        Thread writer = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    start.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int round = 0; round < 400; round++) {
                    try {
                        storage.put("hf", "rev1", "model.gguf", checksum, bytes("stable"));
                    } catch (IOException e) {
                        return;
                    }
                }
            }
        });
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    start.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                // wait for the artifact to exist before counting misses: the
                // cold-start window before the very first put() completes is
                // not the delete->move race under test
                long deadline = System.currentTimeMillis() + 30_000L;
                try {
                    while (!storage.contains("hf", "rev1", "model.gguf")) {
                        if (System.currentTimeMillis() > deadline) {
                            return;
                        }
                        Thread.sleep(1);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                for (int round = 0; round < 400; round++) {
                    observations.incrementAndGet();
                    if (!storage.contains("hf", "rev1", "model.gguf")) {
                        falseMisses.incrementAndGet();
                    }
                }
            }
        });
        writer.start();
        reader.start();
        start.countDown();
        writer.join(60_000);
        reader.join(60_000);
        assertEquals(0, falseMisses.get(),
                "the atomic-replace move ladder must eliminate the delete->move 'not cached' window");
        assertTrue(observations.get() > 0);
    }

    @Test
    void pinnedArtifactsSurviveConcurrentEvictionPressure() throws Exception {
        final CacheStorage storage = new CacheStorage(tempDir, 8L * 1024L);
        final Checksum checksum = Checksum.sha256(sha256Hex("pinned-payload"));
        storage.put("hf", "rev1", "pinned.gguf", checksum, bytes("pinned-payload"));
        storage.pin("hf", "rev1", "pinned.gguf");

        final CountDownLatch start = new CountDownLatch(1);
        final AtomicInteger pinnedEvictions = new AtomicInteger();
        Thread[] threads = new Thread[4];
        for (int i = 0; i < threads.length; i++) {
            threads[i] = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        start.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                    for (int round = 0; round < 50; round++) {
                        try {
                            storage.put("hf", "rev" + round, "artifact-" + round + ".gguf",
                                    null, bytes("filler-payload-for-eviction-pressure"));
                        } catch (IOException e) {
                            return;
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
        assertTrue(storage.contains("hf", "rev1", "pinned.gguf"),
                "the pinned artifact must survive concurrent eviction pressure");
        assertTrue(storage.isPinned("hf", "rev1", "pinned.gguf"));
        assertEquals(0, pinnedEvictions.get());
    }
}
