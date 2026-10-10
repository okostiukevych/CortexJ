package io.cortexj.runtime.llamacpp;

import io.cortexj.inference.GenerationRequest;
import io.cortexj.inference.GenerationResponse;
import io.cortexj.inference.TextGenerationCapability;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelArtifact;
import io.cortexj.model.ModelDescriptor;
import io.cortexj.runtime.DeviceType;
import io.cortexj.spi.runtime.ModelRuntime;
import io.cortexj.spi.runtime.RuntimeModel;
import io.cortexj.spi.runtime.RuntimeModelRequest;
import io.cortexj.spi.runtime.RuntimeOpenRequest;
import io.cortexj.stream.Token;
import io.cortexj.stream.TokenConsumer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    /**
     * audit #126 (2026-10-05): the SUCCESS path (real llama-server: launch ->
     * blocking generate -> SSE stream -> close -> process stopped) is now
     * gated-executable, not manual-only. Both env gates must be set:
     * CORTEXJ_LLAMACPP_SERVER (the binary) and CORTEXJ_LLAMACPP_TEST_MODEL
     * (a tiny real GGUF the binary can load). Skips stay loud.
     */
    @Test
    @Tag("gated")
    void realBinarySuccessRoundTripWhenAvailable() throws Exception {
        String binary = System.getenv("CORTEXJ_LLAMACPP_SERVER");
        String model = System.getenv("CORTEXJ_LLAMACPP_TEST_MODEL");
        assumeTrue(binary != null && !binary.trim().isEmpty(),
                "CORTEXJ_LLAMACPP_SERVER is not set - the success round-trip lane "
                        + "is skipped explicitly (never silently)");
        assumeTrue(model != null && !model.trim().isEmpty(),
                "CORTEXJ_LLAMACPP_TEST_MODEL is not set - the success round-trip lane "
                        + "is skipped explicitly (never silently)");
        assumeTrue(Files.isRegularFile(Paths.get(model.trim())),
                "CORTEXJ_LLAMACPP_TEST_MODEL points to a missing file: " + model);

        System.setProperty("cortexj.llamacpp.server", binary.trim());
        try {
            LlamaCppProvider provider = new LlamaCppProvider();
            ModelArtifact artifact = new ModelArtifact.Abstract(
                    Paths.get(model.trim()).getFileName().toString(), ArtifactFormat.GGUF,
                    Files.size(Paths.get(model.trim())), null, null) {
            };
            RuntimeOpenRequest openRequest = new RuntimeOpenRequest(
                    ModelDescriptor.builder("gated/round-trip")
                            .task(io.cortexj.model.TaskCapability.TEXT_GENERATION)
                            .artifact(artifact)
                            .build(),
                    artifact,
                    Paths.get(model.trim()).toAbsolutePath(),
                    DeviceType.CPU,
                    Collections.<String, Object>emptyMap(),
                    Collections.<String, String>emptyMap(),
                    null, null, null, null);
            ModelRuntime runtime = provider.open(openRequest);
            RuntimeModel loaded = runtime.load(new RuntimeModelRequest(
                    openRequest.model(), artifact,
                    Paths.get(model.trim()).toAbsolutePath(),
                    DeviceType.CPU,
                    Collections.<String, Object>emptyMap()));
            try {
                TextGenerationCapability generator = (TextGenerationCapability) loaded;
                GenerationResponse response = generator.generate(GenerationRequest.prompt("Say OK"));
                assertTrue(response.text() != null && !response.text().isEmpty(),
                        "a real llama-server must produce non-empty output");

                final CountDownLatch done = new CountDownLatch(1);
                final StringBuilder streamed = new StringBuilder();
                generator.stream(GenerationRequest.prompt("Say OK")).subscribe(new TokenConsumer() {
                    @Override
                    public void onToken(Token token) {
                        streamed.append(token.text());
                    }

                    @Override
                    public void onComplete(io.cortexj.stream.StreamCompletion completion) {
                        done.countDown();
                    }

                    @Override
                    public void onError(io.cortexj.error.CortexJException error) {
                        done.countDown();
                    }
                });
                assertTrue(done.await(180, TimeUnit.SECONDS),
                        "the real SSE stream must terminate");
                assertFalse(streamed.length() == 0,
                        "the real SSE stream must deliver tokens");
            } finally {
                loaded.close();
            }
        } finally {
            System.clearProperty("cortexj.llamacpp.server");
        }
    }

    /**
     * Metal/CUDA offload through the provider: the same artifact with
     * {@code gpuLayers} loads a GPU-backed server and generates. Gated on
     * the real binary + model AND on Apple Silicon (the only GPU this lane
     * can prove); everywhere else it skips loudly.
     */
    @Test
    @Tag("gated")
    void gpuOffloadRoundTripWhenAvailable() throws Exception {
        String binary = System.getenv("CORTEXJ_LLAMACPP_SERVER");
        String model = System.getenv("CORTEXJ_LLAMACPP_TEST_MODEL");
        assumeTrue(binary != null && !binary.trim().isEmpty()
                        && model != null && !model.trim().isEmpty(),
                "CORTEXJ_LLAMACPP_SERVER/CORTEXJ_LLAMACPP_TEST_MODEL are not set - "
                        + "the GPU offload lane is skipped explicitly (never silently)");
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("mac")
                        && System.getProperty("os.arch", "").contains("aarch64"),
                "GPU offload is proven on Apple Silicon only - skipped explicitly elsewhere");
        assumeTrue(Files.isRegularFile(Paths.get(model.trim())),
                "CORTEXJ_LLAMACPP_TEST_MODEL points to a missing file: " + model);

        System.setProperty("cortexj.llamacpp.server", binary.trim());
        try {
            LlamaCppProvider provider = new LlamaCppProvider();
            ModelArtifact artifact = new ModelArtifact.Abstract(
                    Paths.get(model.trim()).getFileName().toString(), ArtifactFormat.GGUF,
                    Files.size(Paths.get(model.trim())), null, null) {
            };
            java.util.Map<String, Object> options =
                    java.util.Collections.<String, Object>singletonMap("gpuLayers", 99);
            RuntimeOpenRequest openRequest = new RuntimeOpenRequest(
                    ModelDescriptor.builder("gated/gpu-offload")
                            .task(io.cortexj.model.TaskCapability.TEXT_GENERATION)
                            .artifact(artifact)
                            .build(),
                    artifact,
                    Paths.get(model.trim()).toAbsolutePath(),
                    DeviceType.GPU,
                    options,
                    Collections.<String, String>emptyMap(),
                    null, null, null, null);
            ModelRuntime runtime = provider.open(openRequest);
            RuntimeModel loaded = runtime.load(new RuntimeModelRequest(
                    openRequest.model(), artifact,
                    Paths.get(model.trim()).toAbsolutePath(),
                    DeviceType.GPU,
                    options));
            try {
                TextGenerationCapability generator = (TextGenerationCapability) loaded;
                GenerationResponse response = generator.generate(
                        GenerationRequest.prompt("Say OK"));
                assertTrue(response.text() != null && !response.text().isEmpty(),
                        "the GPU-backed server must produce non-empty output");
            } finally {
                loaded.close();
            }
        } finally {
            System.clearProperty("cortexj.llamacpp.server");
        }
    }
}
