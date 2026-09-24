package io.cortexj.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheStorageTest {

    @TempDir
    Path tempDir;

    private static InputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void putStoresAndGetValidates() throws IOException {
        CacheStorage storage = new CacheStorage(tempDir);
        String checksum = Checksums.sha256Hex("hello".getBytes(StandardCharsets.UTF_8));
        storage.put("hf", "rev1", "model.gguf", io.cortexj.model.Checksum.sha256(checksum), bytes("hello"));

        Path cached = storage.get("hf", "rev1", "model.gguf", io.cortexj.model.Checksum.sha256(checksum));
        assertNotNull(cached);
        assertEquals("hello", new String(Files.readAllBytes(cached), StandardCharsets.UTF_8));
        assertTrue(storage.contains("hf", "rev1", "model.gguf"));
    }

    @Test
    void corruptedEntryIsRejectedAndRemoved() throws IOException {
        CacheStorage storage = new CacheStorage(tempDir);
        String correct = Checksums.sha256Hex("hello".getBytes(StandardCharsets.UTF_8));
        storage.put("hf", "rev1", "model.gguf", io.cortexj.model.Checksum.sha256(correct), bytes("hello"));

        String wrong = Checksums.sha256Hex("tampered".getBytes(StandardCharsets.UTF_8));
        try {
            storage.get("hf", "rev1", "model.gguf", io.cortexj.model.Checksum.sha256(wrong));
            org.junit.jupiter.api.Assertions.fail("expected ArtifactIntegrityException");
        } catch (io.cortexj.error.ArtifactIntegrityException expected) {
            assertTrue(expected.code().startsWith("CORTEXJ-ARTIFACT"));
        }
        assertNull(storage.get("hf", "rev1", "model.gguf", io.cortexj.model.Checksum.sha256(wrong)));
    }

    @Test
    void pathSanitizationBlocksTraversal() throws IOException {
        CacheStorage storage = new CacheStorage(tempDir);
        Path path = storage.artifactPath("../etc", "../../passwd", "model.gguf");
        Path normalized = path.toAbsolutePath().normalize();
        assertTrue(normalized.startsWith(tempDir.toAbsolutePath().normalize()));
        for (Path part : normalized) {
            String name = part.toString();
            assertTrue(!name.equals("..") && !name.equals("."), "dangerous segment: " + name);
        }
        Path stored = storage.put("../etc", "../../passwd", "model.gguf", null, bytes("x"));
        assertTrue(stored.toAbsolutePath().normalize().startsWith(tempDir.toAbsolutePath().normalize()));
    }

    @Test
    void lruEvictionRespectsSizeCap() throws IOException, InterruptedException {
        CacheStorage storage = new CacheStorage(tempDir, 1024L);
        for (int i = 0; i < 6; i++) {
            StringBuilder payload = new StringBuilder();
            for (int j = 0; j < 200; j++) {
                payload.append('x');
            }
            storage.put("hf", "rev", "artifact-" + i + ".bin", null, bytes(payload.toString()));
            Thread.sleep(5);
        }
        long total = storage.totalSizeBytes();
        assertTrue(total <= 1024L + 400L, "total " + total + " should be near the cap");
    }

    @Test
    void checksumUtilityMatchesKnownVector() {
        assertEquals(
                "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                Checksums.sha256Hex("hello".getBytes(StandardCharsets.UTF_8)));
    }
}
