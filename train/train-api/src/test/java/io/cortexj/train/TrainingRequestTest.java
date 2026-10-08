package io.cortexj.train;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TrainingRequestTest {

    @Test
    void builderKeepsIntent() {
        TrainingRequest request = TrainingRequest.builder()
                .model("Qwen/Qwen3-8B")
                .dataset("sha256:train")
                .strategy(TrainingStrategy.LORA)
                .epochs(3)
                .learningRate(2e-4)
                .device(Device.AUTO)
                .build();
        assertEquals("Qwen/Qwen3-8B", request.model());
        assertEquals("sha256:train", request.dataset());
        assertEquals(TrainingStrategy.LORA, request.strategy());
        assertEquals(3, request.epochs());
        assertEquals(2e-4, request.learningRate(), 1e-12);
        assertEquals(Device.AUTO, request.device());
    }

    @Test
    void modelAndDatasetAreRequired() {
        assertThrows(IllegalArgumentException.class,
                () -> TrainingRequest.builder().build());
        assertThrows(IllegalArgumentException.class,
                () -> TrainingRequest.builder().model("m").build());
        assertThrows(IllegalArgumentException.class,
                () -> TrainingRequest.builder().dataset("d").build());
    }

    @Test
    void strategiesCoverPeftFirst() {
        List<TrainingStrategy> all = Arrays.asList(TrainingStrategy.values());
        assertTrue(all.contains(TrainingStrategy.LORA));
        assertTrue(all.contains(TrainingStrategy.QLORA));
        assertTrue(all.contains(TrainingStrategy.FULL));
    }
}
