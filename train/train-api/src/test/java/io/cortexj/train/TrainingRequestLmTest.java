package io.cortexj.train;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TrainingRequestLmTest {

    @Test
    void requestCarriesLoraSettingsAndLmData() {
        LoraSettings lora = LoraSettings.of(8, 16, Arrays.asList("q_proj", "v_proj"));
        LmDataset dataset = new LmDataset() {
            @Override
            public List<LmExample> examples() {
                return Arrays.asList(LmExample.of(new int[]{1, 2, 3}, new int[]{0, 0, 1}));
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
        TrainingRequest request = TrainingRequest.builder()
                .model("Qwen/Qwen3-0.6B")
                .dataset("sha256:test")
                .strategy(TrainingStrategy.LORA)
                .lora(lora)
                .lmData(dataset)
                .build();
        assertEquals(8, request.lora().rank());
        assertEquals(2.0f, request.lora().scaling(), 1e-6f);
        assertEquals(64, request.lmData().vocabSize());
        assertEquals(1, request.lmData().examples().size());
    }

    @Test
    void loraAndLmDataDefaultToNull() {
        TrainingRequest request = TrainingRequest.builder()
                .model("m").dataset("d").strategy(TrainingStrategy.LORA).build();
        assertNull(request.lora());
        assertNull(request.lmData());
    }

    @Test
    void loraSettingsValidateAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> LoraSettings.of(0, 16, Arrays.asList("q_proj")));
        assertThrows(IllegalArgumentException.class,
                () -> LoraSettings.of(8, 0, Arrays.asList("q_proj")));
        assertThrows(IllegalArgumentException.class,
                () -> LoraSettings.of(8, 16, java.util.Collections.<String>emptyList()));
    }

    @Test
    void lmExampleValidatesParallelArrays() {
        assertThrows(IllegalArgumentException.class,
                () -> LmExample.of(new int[]{1}, new int[]{0, 1}));
        assertNotNull(LmExample.of(new int[]{1}, new int[]{0}));
    }
}
