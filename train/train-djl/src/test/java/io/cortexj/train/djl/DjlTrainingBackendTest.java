package io.cortexj.train.djl;

import io.cortexj.train.TrainingBackend;
import io.cortexj.train.TrainingBackend.Support;
import io.cortexj.train.TrainingRequest;
import io.cortexj.train.TrainingStrategy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DjlTrainingBackendTest {

    private final DjlTrainingBackend backend = new DjlTrainingBackend();

    @Test
    void evaluateIsHonestAboutThisMachine() {
        TrainingRequest request = TrainingRequest.builder()
                .model("m").dataset("d").strategy(TrainingStrategy.LORA).build();
        Support support = backend.evaluate(request);
        assertNotNull(support);
        assertTrue(!support.reason().isEmpty());
        // on a machine with only OnnxRuntime DJL engines, training is unsupported
        if (!support.isSupported()) {
            assertTrue(support.reason().contains("engine"), support.reason());
        }
    }

    @Test
    void idIsStable() {
        assertEquals("djl", backend.id());
    }
}
