package io.cortexj.train.remote;

import io.cortexj.train.TrainingBackend;
import io.cortexj.train.TrainingBackend.Support;
import io.cortexj.train.TrainingRequest;
import io.cortexj.train.TrainingStrategy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteTrainingBackendTest {

    @Test
    void unconfiguredEndpointIsHonest() {
        RemoteTrainingBackend backend = new RemoteTrainingBackend();
        TrainingRequest request = TrainingRequest.builder()
                .model("m").dataset("d").strategy(TrainingStrategy.LORA).build();
        Support support = backend.evaluate(request);
        assertNotNull(support);
        assertTrue(!support.isSupported());
        assertTrue(support.reason().contains("CORTEXJ_REMOTE_TRAINER_URL"));
    }

    @Test
    void configuredEndpointReportsPartial() {
        RemoteTrainingBackend backend = new RemoteTrainingBackend("http://localhost:18090");
        TrainingRequest request = TrainingRequest.builder()
                .model("m").dataset("d").strategy(TrainingStrategy.LORA).build();
        Support support = backend.evaluate(request);
        assertTrue(support.isSupported());
        assertEquals(TrainingBackend.Level.PARTIAL, support.level());
        assertTrue(support.reason().contains("localhost:18090"), support.reason());
    }

    @Test
    void idIsStable() {
        assertEquals("remote-trainer", new RemoteTrainingBackend().id());
    }
}
