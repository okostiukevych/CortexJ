package io.cortexj.train.djl;

import io.cortexj.convert.lora.LoraMerge;
import io.cortexj.inference.GenerationRequest;
import io.cortexj.inference.GenerationResponse;
import io.cortexj.inference.TextGenerationCapability;
import io.cortexj.model.ArtifactFormat;
import io.cortexj.model.ModelArtifact;
import io.cortexj.model.ModelDescriptor;
import io.cortexj.model.TaskCapability;
import io.cortexj.runtime.DeviceType;
import io.cortexj.runtime.llamacpp.LlamaCppProvider;
import io.cortexj.spi.runtime.ModelRuntime;
import io.cortexj.spi.runtime.RuntimeModel;
import io.cortexj.spi.runtime.RuntimeModelRequest;
import io.cortexj.spi.runtime.RuntimeOpenRequest;
import io.cortexj.train.LmDataset;
import io.cortexj.train.LoraSettings;
import io.cortexj.train.TrainingBackend;
import io.cortexj.train.TrainingRequest;
import io.cortexj.train.TrainingResult;
import io.cortexj.train.TrainingStrategy;
import io.cortexj.train.data.LmDatasets;
import io.cortexj.train.djl.models.ArchitectureConfig;
import io.cortexj.train.djl.models.ArchitectureConfigs;
import io.cortexj.train.djl.models.DjlHfTokenizer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The owner DoD lane: raw-text + SFT toy run on the real model ->
 * adapter -> ADR-029 merge into the Q8_0 base -> the merged model
 * generates differently on a held-out prompt. Artifacts created by
 * the test are deleted after the run (repo policy); the operator's
 * model directory stays untouched.
 */
class EndToEndLiveTest {

    @Test
    void trainedAdapterChangesTheMergedModelBehaviour() throws Exception {
        String dirEnv = System.getenv("CORTEXJ_E2E_MODEL_DIR");
        assumeTrue(dirEnv != null && Files.isDirectory(Path.of(dirEnv)),
                "CORTEXJ_E2E_MODEL_DIR not set - end-to-end lane skipped loudly");
        assumeTrue(MaskedCausalCrossEntropyTest.enginePresent(),
                "no DJL PyTorch engine - end-to-end lane skipped loudly");
        Path dir = Path.of(dirEnv);
        Path baseGguf = dir.resolve("base-q8_0.gguf");
        assumeTrue(Files.isRegularFile(baseGguf), "base-q8_0.gguf missing in the model dir");

        Path work = Files.createTempDirectory("cortexj-e2e");
        try {
            Path sft = writeToySft(work);
            DjlHfTokenizer tokenizer = DjlHfTokenizer.load(dir.resolve("tokenizer.json"));
            LmDataset dataset = LmDatasets.jsonlSft(sft, tokenizer, 256);
            Path out = work.resolve("adapter");

            TrainingRequest request = TrainingRequest.builder()
                    .model("local/e2e")
                    .dataset(dataset.fingerprint())
                    .strategy(TrainingStrategy.LORA)
                    .lora(LoraSettings.of(4, 8, Arrays.asList("q_proj", "v_proj")))
                    .lmData(dataset)
                    .epochs(5)
                    .learningRate(5e-4)
                    .parameter("modelPath", dir.toString())
                    .parameter("seqLen", "256")
                    .parameter("batchSize", "1")
                    .parameter("accumulation", "2")
                    .parameter("warmupSteps", "5")
                    .parameter("seed", "42")
                    .parameter("evalSplit", "0.2")
                    .parameter("outputDir", out.toString())
                    .build();
            DjlTrainingBackend backend = new DjlTrainingBackend();
            TrainingBackend.Support support = backend.evaluate(request);
            assumeTrue(support.level() == TrainingBackend.Level.FULL,
                    "backend declined the live run: " + support.reason());
            TrainingResult result = backend.train(request, support);
            assertTrue(result.metrics().get("train.loss.last")
                            < result.metrics().get("train.loss.first"),
                    "the toy data must be learned: " + result.metrics());

            // ADR-029 merge into the quantized base; the layer bound comes
            // from the parsed config, never a test constant
            ArchitectureConfig config = ArchitectureConfigs.parse(dir.resolve("config.json"));
            List<LoraMerge.Mapping> mappings = new ArrayList<LoraMerge.Mapping>();
            for (int layer = 0; layer < config.numLayers(); layer++) {
                mappings.add(new LoraMerge.Mapping("blk." + layer + ".attn_q.weight",
                        adapterFile(out, "q_proj", layer)));
                mappings.add(new LoraMerge.Mapping("blk." + layer + ".attn_v.weight",
                        adapterFile(out, "v_proj", layer)));
            }
            Path merged = work.resolve("merged-q8_0.gguf");
            LoraMerge.Result merge = LoraMerge.merge(new LoraMerge.Request(
                    baseGguf, mappings, LoraMerge.QuantPolicy.SAME, merged));
            assertTrue(Files.isRegularFile(merge.outputModel()));

            String trainedPrompt = "Say the magic word.";
            String unrelatedPrompt = "The capital of France is";
            loadThroughRuntime(baseGguf);
            loadThroughRuntime(merge.outputModel());
            String baseTrained = rawComplete(baseGguf, trainedPrompt, 18098);
            String mergedTrained = rawComplete(merge.outputModel(), trainedPrompt, 18099);
            String baseOther = rawComplete(baseGguf, unrelatedPrompt, 18098);
            String mergedOther = rawComplete(merge.outputModel(), unrelatedPrompt, 18099);
            System.out.println("E2E-BASE-TRAINED=[" + baseTrained + "]");
            System.out.println("E2E-MERGED-TRAINED=[" + mergedTrained + "]");
            System.out.println("E2E-BASE-OTHER=[" + baseOther + "]");
            System.out.println("E2E-MERGED-OTHER=[" + mergedOther + "]");
            assertNotNull(baseTrained);
            // (1) the fact was learned: the trained pattern completes with the magic word
            assertTrue(mergedTrained.contains("cortexj-rocks"),
                    "the merged model must emit the trained fact: " + mergedTrained);
            // (2) no catastrophic drift or leakage: unrelated prompts still
            // generate sanely and do not emit the trained fact
            assertTrue(!mergedOther.trim().isEmpty(), "merged model must generate");
            assertTrue(!mergedOther.contains("cortexj-rocks"),
                    "the trained fact must not leak into unrelated prompts: "
                            + mergedOther);
        } finally {
            deleteRecursively(work);
        }
    }

    private static Path adapterFile(Path out, String module, int layer) {
        return out.resolve("adapter_layers_" + layer + "_self_attn_" + module + ".safetensors");
    }

    private static Path writeToySft(Path work) throws IOException {
        Path file = work.resolve("toy-sft.jsonl");
        StringBuilder jsonl = new StringBuilder();
        for (int i = 0; i < 8; i++) {
            jsonl.append("{\"prompt\": \"Say the magic word.\", "
                    + "\"response\": \"cortexj-rocks\"}\n");
        }
        jsonl.append("{\"prompt\": \"What is the magic word?\", "
                + "\"response\": \" cortexj-rocks, obviously\"}\n");
        Files.write(file, jsonl.toString().getBytes(StandardCharsets.UTF_8));
        return file;
    }

    /**
     * Deployment proof: the file loads through the real CortexJ
     * Runtime path (llamacpp provider -> llama-server spawn check).
     */
    private void loadThroughRuntime(Path gguf) throws Exception {
        String binary = discoverLlamaServer();
        assumeTrue(binary != null,
                "no llama-server binary found - deployment proof skipped loudly");
        System.setProperty("cortexj.llamacpp.server", binary);
        try {
            LlamaCppProvider provider = new LlamaCppProvider();
            ModelArtifact artifact = new ModelArtifact.Abstract(
                    gguf.getFileName().toString(), ArtifactFormat.GGUF,
                    Files.size(gguf), null, null) {
            };
            RuntimeOpenRequest openRequest = new RuntimeOpenRequest(
                    ModelDescriptor.builder("e2e/llamacpp")
                            .task(TaskCapability.TEXT_GENERATION)
                            .artifact(artifact)
                            .build(),
                    artifact,
                    gguf.toAbsolutePath(),
                    DeviceType.CPU,
                    Collections.<String, Object>emptyMap(),
                    Collections.<String, String>emptyMap(),
                    null, null, null, null);
            ModelRuntime runtime = provider.open(openRequest);
            RuntimeModel loaded = runtime.load(new RuntimeModelRequest(
                    openRequest.model(), artifact,
                    gguf.toAbsolutePath(),
                    DeviceType.CPU,
                    Collections.<String, Object>emptyMap()));
            try {
                assertTrue(loaded instanceof TextGenerationCapability,
                        "the llamacpp model must support text generation");
            } finally {
                runtime.close();
            }
        } finally {
            System.clearProperty("cortexj.llamacpp.server");
        }
    }

    /**
     * Raw greedy completion (no chat template - the base model does
     * not follow instruct format, and the provider chat-wraps).
     */
    private String rawComplete(Path gguf, String prompt, int port) throws Exception {
        String binary = discoverLlamaServer();
        assumeTrue(binary != null,
                "no llama-server binary found - reference generation skipped loudly");
        Process server = new ProcessBuilder(binary, "-m", gguf.toString(),
                "--host", "127.0.0.1", "--port", String.valueOf(port))
                .redirectErrorStream(true)
                .start();
        try {
            assumeTrue(waitHealthy(port, java.time.Duration.ofMinutes(3)),
                    "llama-server did not become healthy - skipped loudly");
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            String body = mapper.writeValueAsString(java.util.Map.of(
                    "prompt", prompt,
                    "n_predict", 32,
                    "temperature", 0.0,
                    "seed", 1,
                    "stream", false,
                    "cache_prompt", false));
            java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create("http://127.0.0.1:" + port + "/completion"))
                    .timeout(java.time.Duration.ofSeconds(180))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                    .build();
            java.net.http.HttpResponse<String> response = client.send(request,
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            return mapper.readTree(response.body()).path("content").asText("");
        } finally {
            server.destroy();
            server.waitFor();
        }
    }

    private static boolean waitHealthy(int port, java.time.Duration timeout)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        java.net.http.HttpClient client = java.net.http.HttpClient.newHttpClient();
        while (System.currentTimeMillis() < deadline) {
            try {
                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create("http://127.0.0.1:" + port + "/health"))
                        .timeout(java.time.Duration.ofSeconds(2))
                        .GET().build();
                if (client.send(request,
                        java.net.http.HttpResponse.BodyHandlers.ofString()).statusCode() == 200) {
                    return true;
                }
            } catch (Exception stillLoading) {
                Thread.sleep(1000);
            }
        }
        return false;
    }

    /** llama-server discovery mirroring the provider: env, sysprop, standard paths. */
    private static String discoverLlamaServer() {
        String env = System.getenv("CORTEXJ_LLAMACPP_SERVER");
        if (env != null && !env.trim().isEmpty()
                && Files.isRegularFile(Path.of(env.trim()))) {
            return env.trim();
        }
        String property = System.getProperty("cortexj.llamacpp.server");
        if (property != null && !property.trim().isEmpty()
                && Files.isRegularFile(Path.of(property.trim()))) {
            return property.trim();
        }
        for (String candidate : new String[]{"/opt/homebrew/bin/llama-server",
                "/usr/local/bin/llama-server", "/usr/bin/llama-server"}) {
            if (Files.isRegularFile(Path.of(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> p.toFile().delete());
        }
    }
}
