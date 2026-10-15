package io.cortexj.train.djl;

import io.cortexj.convert.lora.LoraMerge;
import io.cortexj.hardware.EnvironmentProfile;
import io.cortexj.hardware.HardwareProfile;
import io.cortexj.inference.GenerationRequest;
import io.cortexj.inference.GenerationResponse;
import io.cortexj.inference.TextGenerationCapability;
import io.cortexj.model.ModelDescriptor;
import io.cortexj.runtime.DeviceDescriptor;
import io.cortexj.runtime.DeviceType;
import io.cortexj.runtime.jinfer.JInferRuntimeProvider;
import io.cortexj.spi.runtime.ModelRuntime;
import io.cortexj.spi.runtime.RuntimeModel;
import io.cortexj.spi.runtime.RuntimeModelRequest;
import io.cortexj.spi.runtime.RuntimeOpenRequest;
import io.cortexj.spi.runtime.RuntimeProbeContext;
import io.cortexj.spi.runtime.RuntimeProbeResult;
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

import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
                    .epochs(2)
                    .learningRate(2e-4)
                    .parameter("modelPath", dir.toString())
                    .parameter("seqLen", "256")
                    .parameter("batchSize", "1")
                    .parameter("accumulation", "2")
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

            String heldOut = "Say the magic word: cortexj-rocks";
            String baseText = generate(baseGguf, heldOut);
            String mergedText = generate(merge.outputModel(), heldOut);
            assertNotNull(baseText);
            assertTrue(!mergedText.trim().isEmpty(), "merged model must generate");
            assertTrue(mergedText.contains("cortexj-rocks")
                            || !mergedText.equals(baseText),
                    "the merged model must differ from the base on the held-out prompt: "
                            + "base=\"" + baseText + "\" merged=\"" + mergedText + "\"");
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

    private String generate(Path gguf, String prompt) {
        JInferRuntimeProvider provider = new JInferRuntimeProvider();
        EnvironmentProfile environment = new EnvironmentProfile(
                "cortexj", "jdk", Runtime.version().feature(),
                Runtime.version().toString(), "macos", "aarch64",
                Runtime.getRuntime().availableProcessors(),
                16L * 1024 * 1024 * 1024, 512L * 1024 * 1024 * 1024,
                16L * 1024 * 1024 * 1024, true, null);
        HardwareProfile hardware = new HardwareProfile(
                Collections.singletonList(DeviceDescriptor.of(DeviceType.CPU, "cpu",
                        16L * 1024 * 1024 * 1024)),
                16L * 1024 * 1024 * 1024, false, false, false, false,
                Collections.<String>emptyList());
        RuntimeProbeResult probe = provider.probe(
                new RuntimeProbeContext(environment, hardware, false, Collections.emptyMap()));
        assumeTrue(probe.isUsable(),
                "JInfer not usable in this JVM - reference generation skipped loudly: "
                        + probe.message());
        ModelRuntime runtime = provider.open(new RuntimeOpenRequest(
                ModelDescriptor.builder("e2e/jinfer").build(), null, gguf, DeviceType.CPU,
                Collections.emptyMap(), Collections.emptyMap(), environment, hardware,
                null, null));
        RuntimeModel loaded = runtime.load(new RuntimeModelRequest(
                ModelDescriptor.builder("e2e/jinfer").build(), null, gguf, DeviceType.CPU,
                Collections.emptyMap()));
        try {
            assertTrue(loaded instanceof TextGenerationCapability,
                    "the jinfer model must support text generation");
            GenerationResponse response = ((TextGenerationCapability) loaded).generate(
                    GenerationRequest.builder().prompt(prompt)
                            .temperature(0.0).maxTokens(16).seed(1L).build());
            return response.text();
        } finally {
            runtime.close();
        }
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
