package io.cortexj.cache;

import io.cortexj.error.ArtifactIntegrityException;
import io.cortexj.model.Checksum;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifyOnceThenTrustTest {

    @TempDir
    Path cacheRoot;

    private static InputStream content(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static Checksum checksum(String text) {
        return Checksum.sha256(Checksums.sha256Hex(text.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void secondGetOfAVerifiedArtifactSkipsTheFullFileHash() throws Exception {
        CacheStorage cache = new CacheStorage(cacheRoot);
        String body = "model-bytes-verified-at-put";
        cache.put("repo", "rev", "model.gguf", checksum(body), content(body));

        // first get: manifest matches (put recorded checksum+size) -> no re-hash
        assertNotNull(cache.get("repo", "rev", "model.gguf", checksum(body)));
        // second get: same cheap path, still no re-hash
        assertNotNull(cache.get("repo", "rev", "model.gguf", checksum(body)));
        assertTrue(cache.contains("repo", "rev", "model.gguf"));
    }

    @Test
    void sizeDriftFallsBackToFullVerificationAndCatchesCorruption() throws Exception {
        CacheStorage cache = new CacheStorage(cacheRoot);
        String body = "model-bytes-original";
        cache.put("repo", "rev", "model.gguf", checksum(body), content(body));

        // corrupt the artifact WITHOUT the manifest knowing: size drifts
        Files.write(cache.artifactPath("repo", "rev", "model.gguf"),
                "tampered-with-different-length".getBytes(StandardCharsets.UTF_8));

        assertThrows(ArtifactIntegrityException.class,
                () -> cache.get("repo", "rev", "model.gguf", checksum(body)),
                "size drift must fall back to full verification, catch the tamper "
                        + "and remove the artifact");
        assertTrue(!cache.contains("repo", "rev", "model.gguf"),
                "a corrupted cache entry must be removed");
    }

    @Test
    void missingManifestFallsBackToFullVerification() throws Exception {
        CacheStorage cache = new CacheStorage(cacheRoot);
        String body = "model-bytes";
        cache.put("repo", "rev", "model.gguf", checksum(body), content(body));
        Files.deleteIfExists(cache.artifactPath("repo", "rev", "model.gguf")
                .resolveSibling("model.gguf.manifest"));

        // no manifest -> full verify on get (still passes, artifact intact)
        assertNotNull(cache.get("repo", "rev", "model.gguf", checksum(body)));
        // and the manifest is re-recorded so the next get is cheap again
        assertNotNull(cache.get("repo", "rev", "model.gguf", checksum(body)));
    }

    @Test
    void paranoidVerifyIntegrityAlwaysHashesAndCatchesSameSizeTampering() throws Exception {
        CacheStorage cache = new CacheStorage(cacheRoot);
        String body = "aaaaaaaaaabbbbbbbbbb"; // 20 bytes
        cache.put("repo", "rev", "model.gguf", checksum(body), content(body));

        // same-size tamper: the trusted-manifest get path does not re-hash,
        // which is the documented verify-once-then-trust trade-off
        Files.write(cache.artifactPath("repo", "rev", "model.gguf"),
                "bbbbbbbbbbcccccccccc".getBytes(StandardCharsets.UTF_8));
        assertNotNull(cache.get("repo", "rev", "model.gguf", checksum(body)));

        // the explicit paranoid API re-hashes and catches it
        assertThrows(ArtifactIntegrityException.class,
                () -> cache.verifyIntegrity("repo", "rev", "model.gguf", checksum(body)),
                "verifyIntegrity must always hash the full file");
        assertTrue(!cache.contains("repo", "rev", "model.gguf"));
    }

    @Test
    void expectedChecksumMismatchAgainstManifestFallsBackToFullVerification() throws Exception {
        CacheStorage cache = new CacheStorage(cacheRoot);
        String body = "model-bytes";
        cache.put("repo", "rev", "model.gguf", checksum(body), content(body));

        // a DIFFERENT expected checksum: manifest mismatch -> full verify -> fail
        Checksum wrong = Checksum.sha256(Checksums.sha256Hex(
                "different".getBytes(StandardCharsets.UTF_8)));
        assertThrows(ArtifactIntegrityException.class,
                () -> cache.get("repo", "rev", "model.gguf", wrong));
        assertTrue(!cache.contains("repo", "rev", "model.gguf"),
                "the artifact must be removed after a failed full verification");
    }
}
