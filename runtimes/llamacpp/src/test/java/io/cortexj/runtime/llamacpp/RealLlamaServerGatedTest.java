package io.cortexj.runtime.llamacpp;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RealLlamaServerGatedTest {

    @TempDir
    Path tempDir;

    @Test
    void realBinarySpawnAndKillWhenAvailable() throws Exception {
        String configured = System.getenv("CORTEXJ_LLAMACPP_SERVER");
        assumeTrue(configured != null && !configured.trim().isEmpty(),
                "CORTEXJ_LLAMACPP_SERVER is not set - the real-binary integration lane "
                        + "is skipped explicitly (never silently)");
        Path binary = Paths.get(configured.trim());
        assumeTrue(Files.isRegularFile(binary),
                "CORTEXJ_LLAMACPP_SERVER points to a missing file: " + binary
                        + " - the real-binary integration lane is skipped explicitly");

        ProcessLlamaServerLauncher launcher = new ProcessLlamaServerLauncher(
                binary.toAbsolutePath().toString(), 15_000, null);
        Path bogusModel = tempDir.resolve("bogus.gguf");
        Files.write(bogusModel, "not-a-real-gguf-container".getBytes("UTF-8"));
        try {
            launcher.launch(bogusModel);
            org.junit.jupiter.api.Assertions.fail(
                    "launching a real llama-server with a bogus model must fail");
        } catch (IOException failure) {
            assertTrue(failure.getMessage() != null
                            && (failure.getMessage().contains("exited during startup")
                            || failure.getMessage().contains("health")),
                    "the launcher must report the real startup failure (process exit or "
                            + "health timeout), got: " + failure.getMessage());
        }
    }
}
