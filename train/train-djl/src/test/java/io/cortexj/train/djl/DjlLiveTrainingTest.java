package io.cortexj.train.djl;

import ai.djl.engine.Engine;
import io.cortexj.error.CortexJException;
import io.cortexj.train.TrainingBackend;
import io.cortexj.train.TrainingData;
import io.cortexj.train.TrainingRequest;
import io.cortexj.train.TrainingResult;
import io.cortexj.train.TrainingStrategy;
import io.cortexj.train.peft.LoRAAdapter;
import io.cortexj.train.peft.LoRASafetensors;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Live LoRA fine-tune through the DJL PyTorch engine (#176): a tiny
 * linear model learns y=2x; the trained weight delta becomes the LoRA
 * adapter artifact (safetensors, checksummed). Skips loudly when no
 * training engine is on the classpath (default build).
 */
class DjlLiveTrainingTest {

    @Test
    void liveLoraRunProducesDeployableAdapter() throws Exception {
        assumeTrue(enginePresent(),
                "no DJL PyTorch engine on the classpath - the live LoRA contract "
                        + "is skipped loudly (run with pytorch-engine + native)");
        DjlTrainingBackend backend = new DjlTrainingBackend();
        TrainingRequest request = TrainingRequest.builder()
                .model("company/tiny-linear")
                .dataset("sha256:train")
                .strategy(TrainingStrategy.LORA)
                .data(TrainingData.of(
                        new double[]{1, 2, 3, 4, 5, 6, 7, 8},
                        new double[]{2, 4, 6, 8, 10, 12, 14, 16}))
                .build();

        // evaluate reports FULL for the verified live path
        TrainingBackend.Support support = backend.evaluate(request);
        assertEquals(TrainingBackend.Level.FULL, support.level(), support.reason());

        TrainingResult result = backend.train(request, support);
        assertTrue(Files.exists(Path.of(result.artifactPath())), "adapter artifact exists");
        assertEquals("djl", result.provenance().backend());
        assertEquals("sha256:train", result.provenance().datasetFingerprint());
        assertTrue(result.provenance().checksums().containsKey("adapter_model.safetensors"));

        // the adapter artifact reads back through the standard PEFT path
        LoRAAdapter adapter = LoRASafetensors.read(Path.of(result.artifactPath()));
        assertEquals("0.weight", adapter.moduleName());
        assertTrue(adapter.config().rank() >= 1);

        // the artifact checksum matches the recorded provenance
        String checksum = sha256(Path.of(result.artifactPath()));
        assertEquals(result.provenance().checksums().get("adapter_model.safetensors"), checksum);

        Files.deleteIfExists(Path.of(result.artifactPath()));
    }

    @Test
    void noDataPathFailsTyped() {
        assumeTrue(enginePresent(),
                "no DJL PyTorch engine on the classpath - the live LoRA contract "
                        + "is skipped loudly");
        DjlTrainingBackend backend = new DjlTrainingBackend();
        TrainingRequest request = TrainingRequest.builder()
                .model("m").dataset("d").strategy(TrainingStrategy.LORA).build();
        CortexJException failure = org.junit.jupiter.api.Assertions.assertThrows(
                CortexJException.class, () -> backend.train(request, null));
        assertEquals("CORTEXJ-TRAIN-0001", failure.code());
    }

    private static boolean enginePresent() {
        try {
            java.util.Set<String> engines = Engine.getAllEngines();
            return engines != null && engines.contains("PyTorch");
        } catch (Throwable unavailable) {
            return false;
        }
    }

    private static String sha256(Path file) throws Exception {
        java.security.MessageDigest digest =
                java.security.MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(Files.readAllBytes(file));
        StringBuilder hex = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16));
            hex.append(Character.forDigit(b & 0xF, 16));
        }
        return "sha256:" + hex;
    }
}
