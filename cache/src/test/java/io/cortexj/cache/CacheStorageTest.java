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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheStorageTest {

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

    private static boolean anyTempLeft(CacheStorage storage) throws IOException {
        for (CacheEntry entry : storage.list()) {
            if (entry.path().getFileName().toString().contains(".part-")) {
                return true;
            }
        }
        return false;
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
    void adoptMovesFileIntoCacheWithoutCopying() throws IOException {
        // audit 2026-10-10: peak disk must stay ~1x the artifact, not ~2x
        CacheStorage storage = new CacheStorage(tempDir);
        String checksum = Checksums.sha256Hex("hello".getBytes(StandardCharsets.UTF_8));
        Path source = tempDir.resolve("download.part");
        Files.write(source, "hello".getBytes(StandardCharsets.UTF_8));

        Path cached = storage.adopt(source, "hf", "rev1", "model.gguf",
                io.cortexj.model.Checksum.sha256(checksum));
        assertFalse(Files.exists(source), "adopt must consume (move) the source file");
        assertEquals("hello", new String(Files.readAllBytes(cached), StandardCharsets.UTF_8));
        assertNotNull(storage.get("hf", "rev1", "model.gguf",
                io.cortexj.model.Checksum.sha256(checksum)));
    }

    @Test
    void adoptRejectsChecksumMismatchAndDeletesSource() throws IOException {
        CacheStorage storage = new CacheStorage(tempDir);
        Path source = tempDir.resolve("tampered.part");
        Files.write(source, "tampered".getBytes(StandardCharsets.UTF_8));

        try {
            storage.adopt(source, "hf", "rev1", "model.gguf",
                    io.cortexj.model.Checksum.sha256(
                            "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef"));
            assertTrue(false, "checksum mismatch must fail the adopt");
        } catch (io.cortexj.error.ArtifactIntegrityException expected) {
            assertTrue(expected.getMessage().contains("checksum validation"));
        }
        assertFalse(Files.exists(source), "a corrupt source must not be kept for resume");
        assertFalse(storage.contains("hf", "rev1", "model.gguf"));
    }

    @Test
    void artifactsDifferingOnlyInCaseCoexist() throws IOException {
        // audit #123: sanitize lowercases, so Model/model used to map to one
        // file and evict each other through verify-delete; the path now carries
        // a hash of the original id
        CacheStorage storage = new CacheStorage(tempDir);
        String upperChecksum = Checksums.sha256Hex("UPPER".getBytes(StandardCharsets.UTF_8));
        String lowerChecksum = Checksums.sha256Hex("lower".getBytes(StandardCharsets.UTF_8));
        storage.put("repo", "rev1", "Model.gguf", io.cortexj.model.Checksum.sha256(upperChecksum),
                bytes("UPPER"));
        storage.put("repo", "rev1", "model.gguf", io.cortexj.model.Checksum.sha256(lowerChecksum),
                bytes("lower"));

        Path upper = storage.get("repo", "rev1", "Model.gguf",
                io.cortexj.model.Checksum.sha256(upperChecksum));
        Path lower = storage.get("repo", "rev1", "model.gguf",
                io.cortexj.model.Checksum.sha256(lowerChecksum));
        assertNotNull(upper, "the Model.gguf entry must survive alongside model.gguf");
        assertNotNull(lower, "the model.gguf entry must survive alongside Model.gguf");
        assertFalse(upper.equals(lower), "case variants must live at distinct paths");
        assertEquals("UPPER", new String(Files.readAllBytes(upper), StandardCharsets.UTF_8));
        assertEquals("lower", new String(Files.readAllBytes(lower), StandardCharsets.UTF_8));
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

    @Test
    void oversizedArtifactIsRejectedWithoutEviction() throws IOException {
        CacheStorage storage = new CacheStorage(tempDir, 4096L);
        for (int i = 0; i < 4; i++) {
            storage.put("hf", "rev1", "artifact-" + i + ".gguf", null, bytes(900, 'a'));
        }
        long trackedBefore = storage.trackedBytesForTesting();

        try {
            storage.put("hf", "rev1", "huge.gguf", null, bytes(8192, 'h'));
            org.junit.jupiter.api.Assertions.fail("expected ArtifactResolutionException");
        } catch (io.cortexj.error.ArtifactResolutionException expected) {
            assertEquals(io.cortexj.ErrorCode.ARTIFACT_TOO_LARGE, expected.code(),
                    "an artifact larger than the cache cap must surface as ARTIFACT_TOO_LARGE");
        }

        for (int i = 0; i < 4; i++) {
            assertTrue(storage.contains("hf", "rev1", "artifact-" + i + ".gguf"),
                    "the oversized put must not evict existing entries");
        }
        assertTrue(!storage.contains("hf", "rev1", "huge.gguf"),
                "the oversized artifact must not be stored");
        assertFalse(anyTempLeft(storage), "the rejected put must leave no temp behind");
        assertEquals(trackedBefore, storage.trackedBytesForTesting(),
                "the rejected put must not change the size index");
    }

    @Test
    void putRejectsLargeWriteWhenUsableSpaceBelowArtifactPlusMargin() throws IOException {
        CacheStorage storage = new CacheStorage(tempDir);
        storage.setUsableSpaceProbeForTesting(() -> 1L);

        try {
            storage.put("hf", "rev1", "large.gguf", null, bytes(68157440, 'l'));
            org.junit.jupiter.api.Assertions.fail("expected ArtifactResolutionException");
        } catch (io.cortexj.error.ArtifactResolutionException expected) {
            assertEquals(io.cortexj.ErrorCode.ARTIFACT_TOO_LARGE, expected.code(),
                    "a large write without usable disk space must surface as ARTIFACT_TOO_LARGE");
        }

        assertEquals(0L, storage.trackedBytesForTesting(),
                "the rejected large write must not credit the size index");
        assertFalse(anyTempLeft(storage), "the rejected large write must leave no temp behind");

        storage.put("hf", "rev1", "small.gguf", null, bytes(64, 's'));
        assertTrue(storage.contains("hf", "rev1", "small.gguf"),
                "small writes bypass the usable-space pre-check and must still land");

        storage.setUsableSpaceProbeForTesting(() -> Long.MAX_VALUE);
        storage.put("hf", "rev1", "large-ok.gguf", null, bytes(68157440, 'l'));
        assertTrue(storage.contains("hf", "rev1", "large-ok.gguf"),
                "with enough usable space the large write must land");
        assertEquals(storage.totalSizeBytes(), storage.trackedBytesForTesting(),
                "the size index must match the real tree after successful puts");
    }
}
