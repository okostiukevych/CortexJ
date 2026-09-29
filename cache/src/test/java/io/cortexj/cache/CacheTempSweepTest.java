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
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheTempSweepTest {

    @TempDir
    Path tempDir;

    private static InputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(String value) {
        return Checksums.sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }

    private Path orphanedTemp(String repository, String revision, String name) throws IOException {
        Path orphan = tempDir.resolve(repository).resolve(revision).resolve(name);
        Files.createDirectories(orphan.getParent());
        byte[] payload = new byte[3000];
        java.util.Arrays.fill(payload, (byte) 'o');
        Files.write(orphan, payload);
        return orphan;
    }

    private static boolean anyTempLeft(CacheStorage storage) throws IOException {
        for (CacheEntry entry : storage.list()) {
            if (entry.path().getFileName().toString().contains(".part-")) {
                return true;
            }
        }
        return false;
    }

    @Test
    void listSeesOrphanedTempsAndClearRemovesThem() throws IOException {
        CacheStorage storage = new CacheStorage(tempDir);
        Path orphan = orphanedTemp("hf", "rev1", "model.gguf.part-16ac");
        assertTrue(anyTempLeft(storage), "list() must expose orphaned .part- temps");
        boolean seenInList = false;
        for (CacheEntry entry : storage.list()) {
            if (entry.path().equals(orphan)) {
                seenInList = true;
            }
        }
        assertTrue(seenInList, "the orphaned temp must be a first-class list() entry");

        long freed = storage.clear();
        assertFalse(Files.exists(orphan), "cache clean must remove orphaned temps");
        assertTrue(freed > 0, "the freed accounting must include the orphaned temp");
    }

    @Test
    void totalSizeBytesAccountsForTemps() throws IOException {
        CacheStorage storage = new CacheStorage(tempDir);
        Path orphan = orphanedTemp("hf", "rev1", "model.gguf.part-16ac");
        long orphanSize = Files.size(orphan);
        storage.put("hf", "rev1", "model.gguf", Checksum.sha256(sha256Hex("real")), bytes("real"));
        long total = storage.totalSizeBytes();
        assertTrue(total >= orphanSize,
                "totalSizeBytes must account for orphaned temps (real disk usage)");
    }

    @Test
    void evictOlderThanSweepsOldTempsButKeepsYoungOnes() throws IOException {
        CacheStorage storage = new CacheStorage(tempDir);
        Path oldOrphan = orphanedTemp("hf", "rev1", "old.gguf.part-aaa");
        Files.setLastModifiedTime(oldOrphan,
                FileTime.fromMillis(System.currentTimeMillis() - 3_600_000L));
        Path youngTemp = orphanedTemp("hf", "rev1", "new.gguf.part-bbb");

        long cutoff = System.currentTimeMillis() - 60_000L;
        storage.evictOlderThan(cutoff);

        assertFalse(Files.exists(oldOrphan), "temps older than the cutoff must be swept");
        assertTrue(Files.exists(youngTemp),
                "a young in-flight temp must never be swept by the age cutoff");
    }

    @Test
    void evictToFitRemovesStaleOrphanTempsBeforeArtifacts() throws IOException {
        CacheStorage storage = new CacheStorage(tempDir, 4096L);
        Path orphan = orphanedTemp("hf", "rev1", "model.gguf.part-16ac");
        Files.setLastModifiedTime(orphan,
                FileTime.fromMillis(System.currentTimeMillis() - 3_600_000L));

        byte[] payload = new byte[3000];
        java.util.Arrays.fill(payload, (byte) 'x');
        storage.put("hf", "rev1", "model.gguf",
                Checksum.sha256(Checksums.sha256Hex(payload)), new ByteArrayInputStream(payload));

        assertFalse(Files.exists(orphan),
                "a stale orphan temp must be evicted before live artifacts when space is needed");
        assertTrue(storage.contains("hf", "rev1", "model.gguf"),
                "the incoming artifact must be stored");
    }

    @Test
    void putFailurePathsLeaveNoTempBehind() throws IOException {
        CacheStorage storage = new CacheStorage(tempDir);

        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("boom mid-copy");
            }
        };
        assertThrows(IOException.class, () -> storage.put("hf", "rev1", "m1.gguf", null, broken));

        storage.put("hf", "rev1", "m2.gguf", Checksum.sha256(sha256Hex("good")), bytes("good"));
        assertThrows(ArtifactIntegrityException.class, () -> storage.put("hf", "rev1", "m3.gguf",
                Checksum.sha256(sha256Hex("expected")), bytes("tampered")));

        assertFalse(anyTempLeft(storage),
                "no .part- temp may survive any put() failure path");
        assertTrue(storage.contains("hf", "rev1", "m2.gguf"),
                "the valid artifact must be untouched by the failed puts");
    }

    @Test
    void successfulPutLeavesNoTempAndArtifactIsComplete() throws IOException {
        CacheStorage storage = new CacheStorage(tempDir);
        storage.put("hf", "rev1", "model.gguf", Checksum.sha256(sha256Hex("complete")), bytes("complete"));
        assertFalse(anyTempLeft(storage), "a completed put must leave no temp behind");
        assertEquals("complete", new String(
                Files.readAllBytes(storage.get("hf", "rev1", "model.gguf",
                        Checksum.sha256(sha256Hex("complete")))), StandardCharsets.UTF_8));
    }
}
