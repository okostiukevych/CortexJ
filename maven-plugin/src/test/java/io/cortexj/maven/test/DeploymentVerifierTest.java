package io.cortexj.maven.test;

import io.cortexj.maven.DeploymentVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeploymentVerifierTest {

    @TempDir
    Path tempDir;

    @Test
    void starterMatchesBytecodeTarget() {
        DeploymentVerifier.Result result = DeploymentVerifier.checkStarter(
                Arrays.asList("junit:junit", "io.cortexj:cortexj-starter-java17"), "17");
        assertTrue(result.ok, String.valueOf(result.messages));
    }

    @Test
    void missingStarterFailsWithRemediation() {
        DeploymentVerifier.Result result = DeploymentVerifier.checkStarter(
                Collections.singletonList("junit:junit"), "8");
        assertFalse(result.ok);
        assertTrue(result.messages.get(0).contains("cortexj-starter-java8"));
    }

    @Test
    void starterTargetMismatchFails() {
        DeploymentVerifier.Result result = DeploymentVerifier.checkStarter(
                Collections.singletonList("io.cortexj:cortexj-starter-java25"), "8");
        assertFalse(result.ok);
        assertTrue(result.messages.get(0).contains("mismatch"));
    }

    @Test
    void lockfileCheckValidatesStructure() throws Exception {
        Path lock = tempDir.resolve("release.lock");
        Files.write(lock, Arrays.asList(
                "model: Qwen/Qwen3-8B",
                "revision: 7c41481f",
                "artifact:",
                "  file: model.gguf",
                "runtime:",
                "  id: llamacpp"));
        assertTrue(DeploymentVerifier.checkLockfile(lock.toFile()).ok);

        Path bad = tempDir.resolve("bad.lock");
        Files.write(bad, Collections.singletonList("not-a-lockfile"));
        assertFalse(DeploymentVerifier.checkLockfile(bad.toFile()).ok);

        assertFalse(DeploymentVerifier.checkLockfile(new File("/nonexistent.lock")).ok);
    }

    @Test
    void bundleCheckDetectsTamperingAndMissingManifests() throws Exception {
        Path empty = tempDir.resolve("empty-bundle");
        Files.createDirectories(empty);
        DeploymentVerifier.Result missing = DeploymentVerifier.checkBundle(empty.toFile());
        assertFalse(missing.ok);
        assertTrue(missing.messages.get(0).contains("no cortexj-model.json"));

        Path bundle = tempDir.resolve("bundle");
        Files.createDirectories(bundle);
        Path artifact = bundle.resolve("model.gguf");
        Files.write(artifact, new byte[]{1, 2, 3});
        StringBuilder json = new StringBuilder();
        json.append("{")
                .append("\"id\":\"b/model\",")
                .append("\"revision\":\"r1\",")
                .append("\"artifacts\":[{")
                .append("\"file\":\"model.gguf\",")
                .append("\"format\":\"GGUF\",")
                .append("\"size\":3,")
                .append("\"sha256\":\"").append(sha(artifact)).append("\"")
                .append("}]")
                .append("}");
        Files.write(bundle.resolve("cortexj-model.json"),
                Collections.singletonList(json.toString()));
        DeploymentVerifier.Result ok = DeploymentVerifier.checkBundle(bundle.toFile());
        assertTrue(ok.ok, String.valueOf(ok.messages));

        Files.write(artifact, new byte[]{9, 9, 9});
        assertFalse(DeploymentVerifier.checkBundle(bundle.toFile()).ok);
    }

    private static String sha(Path file) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        try (java.io.InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) {
            hex.append(String.format("%02x", b));
        }
        return hex.toString();
    }
}
