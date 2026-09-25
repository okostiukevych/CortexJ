package io.cortexj.cache.test;

import io.cortexj.cache.CacheEntry;
import io.cortexj.cache.CacheStorage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheEvictionTest {

    @TempDir
    Path tempDir;

    private CacheStorage storage() throws Exception {
        return storage(CacheStorage.DEFAULT_MAX_SIZE_BYTES);
    }

    private CacheStorage storage(long maxSize) throws Exception {
        CacheStorage cache = new CacheStorage(tempDir.resolve("cache"), maxSize);
        return cache;
    }

    private void store(CacheStorage cache, String artifactId, int size) throws Exception {
        byte[] payload = new byte[size];
        cache.put("repo", "rev1", artifactId, null, new ByteArrayInputStream(payload));
    }

    @Test
    void pinnedArtifactsSurviveSizeCapEviction() throws Exception {
        CacheStorage cache = storage(2048);
        store(cache, "small-a", 1000);
        store(cache, "small-b", 1000);
        cache.pin("repo", "rev1", "small-a");

        store(cache, "small-c", 1000);

        assertTrue(cache.contains("repo", "rev1", "small-a"), "pinned artifact must survive eviction");
        assertTrue(cache.contains("repo", "rev1", "small-c"), "incoming artifact must be stored");
        assertTrue(cache.get("repo", "rev1", "small-a", null) != null);
        cache.unpin("repo", "rev1", "small-a");
        assertFalse(Files.exists(cache.artifactPath("repo", "rev1", "small-a")
                .resolveSibling("small-a.pinned")));
    }

    @Test
    void evictOlderThanRemovesOnlyAgedUnpinnedArtifacts() throws Exception {
        CacheStorage cache = storage();
        store(cache, "old-a", 100);
        store(cache, "old-b", 100);
        cache.pin("repo", "rev1", "old-a");
        Path fresh = cache.artifactPath("repo", "rev1", "fresh-c");
        store(cache, "fresh-c", 100);
        Files.setLastModifiedTime(fresh,
                java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 3_600_000));

        long cutoff = System.currentTimeMillis() + 1_800_000;
        long freed = cache.evictOlderThan(cutoff);
        assertTrue(freed > 0);
        assertTrue(cache.contains("repo", "rev1", "old-a"), "pinned artifact must survive age eviction");
        assertTrue(cache.contains("repo", "rev1", "fresh-c"), "fresh artifact must survive age eviction");
        assertFalse(cache.contains("repo", "rev1", "old-b"), "aged unpinned artifact must be evicted");
    }

    @Test
    void clearRemovesEverything() throws Exception {
        CacheStorage cache = storage();
        store(cache, "a", 100);
        store(cache, "b", 100);
        cache.pin("repo", "rev1", "a");

        long freed = cache.clear();
        assertEquals(200L, freed);
        List<CacheEntry> entries = cache.list();
        assertTrue(entries.isEmpty(), "cache must be empty after clear");
    }

    @Test
    void listExposesEntriesWithKeysAndSizes() throws Exception {
        CacheStorage cache = storage();
        store(cache, "listed", 512);
        List<CacheEntry> entries = cache.list();
        assertEquals(1, entries.size());
        assertTrue(entries.get(0).key().endsWith("listed"));
        assertEquals(512L, entries.get(0).sizeBytes());
    }
}
