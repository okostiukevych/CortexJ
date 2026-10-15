package io.cortexj.train.djl;

import io.cortexj.train.LmExample;
import io.cortexj.train.data.LmBatcher;
import io.cortexj.train.djl.models.ArchitectureConfig;
import io.cortexj.train.djl.models.ArchitectureConfigs;
import io.cortexj.train.djl.models.GenericDecoderBlock;
import io.cortexj.train.djl.models.HfWeightLoader;
import io.cortexj.train.djl.models.MiniModelFixture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class LmTrainingLoopTest {

    @TempDir
    Path temp;

    private final io.cortexj.train.data.Tokenizer vocab64 = new io.cortexj.train.data.Tokenizer() {
        @Override
        public int[] encode(String text) {
            int[] ids = new int[text.length()];
            for (int i = 0; i < text.length(); i++) {
                ids[i] = (text.charAt(i) - '0' + 1) % 64;
            }
            return ids;
        }

        @Override
        public int vocabSize() {
            return 64;
        }
    };

    @Test
    void miniModelLearnsItsToyDataAndCheckpointsRoundTrip() throws IOException {
        assumeTrue(MaskedCausalCrossEntropyTest.enginePresent(),
                "no DJL PyTorch engine - training contract skipped loudly");
        MiniModelFixture fixture = new MiniModelFixture(temp);
        ArchitectureConfig config = ArchitectureConfigs.parse(
                fixture.directory().resolve("config.json"));

        List<LmExample> examples = new ArrayList<LmExample>();
        for (int i = 0; i < 8; i++) {
            examples.add(LmExample.of(new int[]{1, 2, 3, 4, 5, 6, 7, 8},
                    new int[]{1, 1, 1, 1, 1, 1, 1, 1}));
        }
        List<LmBatcher.LmBatch> train = LmBatcher.batch(examples, 2, 16, 42L, false);
        List<LmBatcher.LmBatch> eval = LmBatcher.batch(
                examples.subList(0, 2), 2, 16, 42L, false);

        GenericDecoderBlock block = GenericDecoderBlock.build(config,
                Collections.singleton("q_proj"), 4, 2.0f);
        LmTrainingLoop loop = LmTrainingLoop.create(block, 0.05f, 1, 0.01f);
        loop.initialize(16);
        block.loadWeights(HfWeightLoader.load(fixture.directory()), loop.modelManager());
        block.initLora(42L);
        loop.restoreFromCheckpoint(null); // no-op resume path stays honest

        Map<String, Double> metrics = loop.run(train, eval, 5, 1);
        assertTrue(metrics.get("train.loss.last") < metrics.get("train.loss.first"),
                "loss must decrease: " + metrics);
        assertTrue(metrics.containsKey("eval.loss"));

        Path checkpoint = temp.resolve("checkpoint-0");
        loop.saveCheckpoint(checkpoint);
        assertTrue(Files.isRegularFile(checkpoint.resolve("manifest.properties")),
                "checkpoint manifest must exist");

        // resume: a fresh block + loop loads the checkpoint and continues
        GenericDecoderBlock resumed = GenericDecoderBlock.build(config,
                Collections.singleton("q_proj"), 4, 2.0f);
        LmTrainingLoop resumedLoop = LmTrainingLoop.create(resumed, 0.05f, 1, 0.01f);
        resumedLoop.initialize(16);
        resumed.loadWeights(HfWeightLoader.load(fixture.directory()), resumedLoop.modelManager());
        resumed.initLora(42L);
        resumedLoop.restoreFromCheckpoint(checkpoint);
        Map<String, Double> continued = resumedLoop.run(train, eval, 1, 1);
        assertTrue(continued.get("train.loss.last") < metrics.get("train.loss.first"),
                "a resumed run must continue from the saved state");
    }

    @Test
    void adapterExportWritesStandardContainers() throws IOException {
        assumeTrue(MaskedCausalCrossEntropyTest.enginePresent(),
                "no DJL PyTorch engine - export contract skipped loudly");
        MiniModelFixture fixture = new MiniModelFixture(temp);
        ArchitectureConfig config = ArchitectureConfigs.parse(
                fixture.directory().resolve("config.json"));
        GenericDecoderBlock block = GenericDecoderBlock.build(config,
                new HashSet<String>(Arrays.asList("q_proj", "v_proj")), 4, 2.0f);
        LmTrainingLoop loop = LmTrainingLoop.create(block, 0.01f, 4, 0.01f);
        loop.initialize(16);
        block.loadWeights(HfWeightLoader.load(fixture.directory()), loop.modelManager());
        block.initLora(42L);
        Path out = temp.resolve("adapter");
        Map<String, String> checksums = AdapterExport.writeAll(block, 4, 16,
                Arrays.asList("q_proj", "v_proj"), out);
        assertEquals(4, checksums.size(), "2 modules x 2 layers");
        io.cortexj.train.peft.LoRAAdapter adapter =
                io.cortexj.train.peft.LoRASafetensors.read(
                        out.resolve("adapter_layers_0_self_attn_q_proj.safetensors"));
        assertEquals("q_proj", adapter.moduleName());
        assertEquals(4, adapter.config().rank());
        assertEquals(16, adapter.config().alpha());
    }
}
