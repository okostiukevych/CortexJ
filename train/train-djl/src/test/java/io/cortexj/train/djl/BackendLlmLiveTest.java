package io.cortexj.train.djl;

import io.cortexj.train.LmDataset;
import io.cortexj.train.LmExample;
import io.cortexj.train.LoraSettings;
import io.cortexj.train.TrainingBackend;
import io.cortexj.train.TrainingRequest;
import io.cortexj.train.TrainingResult;
import io.cortexj.train.TrainingStrategy;
import io.cortexj.train.djl.models.MiniModelFixture;
import io.cortexj.train.peft.LoRAAdapter;
import io.cortexj.train.peft.LoRASafetensors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BackendLlmLiveTest {

    @TempDir
    Path temp;

    @Test
    void trainRunsEndToEndOnTheMiniModelAndResumes() throws IOException {
        assumeTrue(MaskedCausalCrossEntropyTest.enginePresent(),
                "no DJL PyTorch engine - backend LLM contract skipped loudly");
        MiniModelFixture fixture = new MiniModelFixture(temp);
        Path out = temp.resolve("adapter-out");

        TrainingRequest request = TrainingRequest.builder()
                .model("local/mini")
                .dataset("sha256:test")
                .strategy(TrainingStrategy.LORA)
                .lora(LoraSettings.of(4, 8, Collections.singletonList("q_proj")))
                .lmData(toyDataset())
                .epochs(2)
                .learningRate(0.05)
                .parameter("modelPath", fixture.directory().toString())
                .parameter("seqLen", "16")
                .parameter("batchSize", "2")
                .parameter("accumulation", "2")
                .parameter("seed", "42")
                .parameter("evalSplit", "0.25")
                .parameter("outputDir", out.toString())
                .build();

        DjlTrainingBackend backend = new DjlTrainingBackend();
        TrainingBackend.Support support = backend.evaluate(request);
        assertEquals(TrainingBackend.Level.FULL, support.level(), support.reason());

        TrainingResult result = backend.train(request, support);
        assertTrue(Files.isDirectory(Path.of(result.artifactPath())));
        assertTrue(result.provenance().checksums().size() >= 2,
                "one checksum per trained wrapper file");
        assertTrue(result.metrics().containsKey("train.loss.last"));
        assertTrue(result.metrics().get("train.loss.last")
                < result.metrics().get("train.loss.first"), "loss must decrease");

        LoRAAdapter adapter = LoRASafetensors.read(
                Path.of(result.artifactPath()).resolve("adapter_layers_0_self_attn_q_proj.safetensors"));
        assertEquals("q_proj", adapter.moduleName());

        Path checkpoint = Path.of(result.artifactPath()).resolve("checkpoint-0");
        assertTrue(Files.isRegularFile(checkpoint.resolve("manifest.properties")));

        TrainingRequest resumed = TrainingRequest.builder()
                .model("local/mini")
                .dataset("sha256:test")
                .strategy(TrainingStrategy.LORA)
                .lora(LoraSettings.of(4, 8, Collections.singletonList("q_proj")))
                .lmData(toyDataset())
                .epochs(1)
                .learningRate(0.05)
                .parameter("modelPath", fixture.directory().toString())
                .parameter("seqLen", "16")
                .parameter("batchSize", "2")
                .parameter("accumulation", "2")
                .parameter("resumeFrom", checkpoint.toString())
                .parameter("outputDir", temp.resolve("adapter-resumed").toString())
                .build();
        TrainingResult resumedResult = backend.train(resumed, support);
        assertTrue(Files.isDirectory(Path.of(resumedResult.artifactPath())));

        deleteRecursively(Path.of(result.artifactPath()));
        deleteRecursively(Path.of(resumedResult.artifactPath()));
    }

    private LmDataset toyDataset() {
        return new LmDataset() {
            @Override
            public List<LmExample> examples() {
                List<LmExample> examples = new ArrayList<LmExample>();
                for (int i = 0; i < 8; i++) {
                    examples.add(LmExample.of(
                            new int[]{1, 2, 3, 4, 5, 6, 7, 8},
                            new int[]{1, 1, 1, 1, 1, 1, 1, 1}));
                }
                return examples;
            }

            @Override
            public int vocabSize() {
                return 64;
            }

            @Override
            public String fingerprint() {
                return "sha256:test";
            }
        };
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
