package io.cortexj.train.djl;

import io.cortexj.train.LmDataset;
import io.cortexj.train.LmExample;
import io.cortexj.train.LoraSettings;
import io.cortexj.train.TrainingBackend;
import io.cortexj.train.TrainingRequest;
import io.cortexj.train.TrainingStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BackendLlmGatesTest {

    @TempDir
    Path temp;

    private final LmDataset dataset = new LmDataset() {
        @Override
        public List<LmExample> examples() {
            return Arrays.asList(LmExample.of(new int[]{1, 2, 3}, new int[]{1, 1, 1}));
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

    private TrainingRequest request(Path modelPath, String... parameters) {
        TrainingRequest.Builder builder = TrainingRequest.builder()
                .model("local/mini")
                .dataset("sha256:test")
                .strategy(TrainingStrategy.LORA)
                .lora(LoraSettings.of(4, 8, Collections.singletonList("q_proj")))
                .lmData(dataset);
        for (int i = 0; i + 1 < parameters.length; i += 2) {
            builder.parameter(parameters[i], parameters[i + 1]);
        }
        if (modelPath != null) {
            builder.parameter("modelPath", modelPath.toString());
        }
        return builder.build();
    }

    @Test
    void missingModelPathIsUnsupportedWithTheReason() {
        TrainingBackend.Support support = new DjlTrainingBackend().evaluate(request(null));
        assertEquals(TrainingBackend.Level.UNSUPPORTED, support.level());
        assertTrue(support.reason().contains("modelPath"));
    }

    @Test
    void unsupportedFamilyDeclinesWithItsCode() throws Exception {
        Path dir = Files.createDirectories(temp.resolve("fancy"));
        Files.write(dir.resolve("config.json"),
                "{\"architectures\":[\"FancyForCausalLM\"]}".getBytes());
        TrainingBackend.Support support = new DjlTrainingBackend().evaluate(request(dir));
        assertEquals(TrainingBackend.Level.UNSUPPORTED, support.level());
        assertTrue(support.reason().contains("CORTEXJ-TRAIN-0101"));
    }

    @Test
    void baseAboveFiveGbDeclinesWithItsCode() throws Exception {
        Path dir = Files.createDirectories(temp.resolve("huge"));
        Files.write(dir.resolve("config.json"), ("{"
                + "\"architectures\":[\"Qwen3ForCausalLM\"],"
                + "\"hidden_size\":8192,\"num_hidden_layers\":80,\"num_attention_heads\":64,"
                + "\"intermediate_size\":24576,\"vocab_size\":151936}").getBytes());
        TrainingBackend.Support support = new DjlTrainingBackend().evaluate(request(dir));
        assertEquals(TrainingBackend.Level.UNSUPPORTED, support.level());
        assertTrue(support.reason().contains("CORTEXJ-TRAIN-0107"));
    }

    @Test
    void memoryEstimateExceedingTheHeapDeclinesWithItsCode() throws Exception {
        Path dir = Files.createDirectories(temp.resolve("mini"));
        Files.write(dir.resolve("config.json"), ("{"
                + "\"architectures\":[\"Qwen3ForCausalLM\"],"
                + "\"hidden_size\":32,\"num_hidden_layers\":2,\"num_attention_heads\":4,"
                + "\"num_key_value_heads\":2,\"intermediate_size\":64,\"vocab_size\":64}")
                .getBytes());
        // an absurd seq length blows the activation estimate past any heap
        TrainingBackend.Support support = new DjlTrainingBackend().evaluate(
                request(dir, "seqLen", String.valueOf(Integer.MAX_VALUE / 4)));
        assertEquals(TrainingBackend.Level.UNSUPPORTED, support.level());
        assertTrue(support.reason().contains("CORTEXJ-TRAIN-0108"));
    }
}
