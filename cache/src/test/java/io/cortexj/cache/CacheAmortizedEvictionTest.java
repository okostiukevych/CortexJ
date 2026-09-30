package io.cortexj.cache;

import io.cortexj.model.Checksum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheAmortizedEvictionTest {

    @TempDir
    Path tempDir;

    private static byte[] payload(int size, char fill) {
        byte[] data = new byte[size];
        java.util.Arrays.fill(data, (byte) fill);
        return data;
    }

    private static java.io.InputStream bytes(byte[] data) {
        return new ByteArrayInputStream(data);
    }

    private static String sha256Hex(byte[] data) {
        return Checksums.sha256Hex(data);
    }

    @Test
    void putIntoUnderLimitCacheDoesNotRescanTheTree() throws IOException {
        CacheStorage storage = new CacheStorage(tempDir);
        for (int i = 0; i < 200; i++) {
            storage.put("hf", "rev1", "artifact-" + i + ".gguf",
                    null, bytes(payload(64, (char) ('a' + i % 26))));
        }
        long before = storage.listCallsForTesting();
        storage.put("hf", "rev1", "artifact-final.gguf", null, bytes(payload(64, 'z')));
        long after = storage.listCallsForTesting();

        assertEquals(before, after,
                "a put into an under-limit cache must not rescan the whole tree "
                        + "(amortized eviction: the first put establishes the baseline walk, "
                        + "subsequent puts decide from the incremental index)");
        assertTrue(before >= 1, "the baseline walk must have happened once");
        assertTrue(storage.contains("hf", "rev1", "artifact-final.gguf"));
    }

    @Test
    void amortizedEvictionStillEnforcesTheAdvisoryLimit() throws IOException {
        CacheStorage storage = new CacheStorage(tempDir, 4096L);
        storage.put("hf", "rev1", "a1.gguf",
                Checksum.sha256(sha256Hex(payload(3000, 'a'))), bytes(payload(3000, 'a')));
        storage.put("hf", "rev1", "a2.gguf",
                Checksum.sha256(sha256Hex(payload(3000, 'b'))), bytes(payload(3000, 'b')));
        storage.put("hf", "rev1", "a3.gguf",
                Checksum.sha256(sha256Hex(payload(3000, 'c'))), bytes(payload(3000, 'c')));

        assertTrue(storage.contains("hf", "rev1", "a3.gguf"),
                "the most recent artifact must be stored");
        assertTrue(storage.totalSizeBytes() <= 4096L + 3000L,
                "the durable cache must stay within the advisory cap "
                        + "(plus at most the newest artifact)");
        assertTrue(storage.totalSizeBytes() > 0);
    }

    @Test
    void trackerRecoversAfterExternalMutations() throws IOException {
        CacheStorage storage = new CacheStorage(tempDir, 4096L);
        storage.put("hf", "rev1", "a1.gguf",
                Checksum.sha256(sha256Hex(payload(3000, 'a'))), bytes(payload(3000, 'a')));

        // external deletion of the artifact: the index over-counts -> the next
        // over-limit check walks and self-heals instead of evicting phantom bytes
        java.nio.file.Files.deleteIfExists(storage.artifactPath("hf", "rev1", "a1.gguf"));
        storage.put("hf", "rev1", "a2.gguf",
                Checksum.sha256(sha256Hex(payload(3000, 'b'))), bytes(payload(3000, 'b')));

        assertTrue(storage.contains("hf", "rev1", "a2.gguf"),
                "the put must still land after external mutations");
        assertTrue(storage.totalSizeBytes() <= 4096L + 3000L);
        assertEquals(3000L, storage.totalSizeBytes(),
                "the index must re-baseline from the real tree on the walk");
    }
}
