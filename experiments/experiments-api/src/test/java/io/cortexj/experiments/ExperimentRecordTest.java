package io.cortexj.experiments;

import io.cortexj.meta.ModelIdentity;
import io.cortexj.provenance.ProvenanceManifest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class ExperimentRecordTest {

    @Test
    void recordKeepsEverythingItCaptured() {
        ProvenanceManifest provenance = ProvenanceManifest.builder()
                .model(ModelIdentity.of("hf:Qwen/Qwen3-8B", "7f62"))
                .datasetFingerprint("sha256:abc")
                .backend("jinfer/0.3.1")
                .build();
        ExperimentRecord record = new ExperimentRecord(
                "run-1", "qwen-lora",
                Map.of("learningRate", "2e-4"),
                Map.of("validation.loss", "0.42"),
                List.of("adapter.gguf"),
                "sha256:abc",
                "Temurin 25", "macos-aarch64",
                "jinfer/0.3.1", "cpu-only",
                provenance);
        assertEquals("run-1", record.id());
        assertEquals("qwen-lora", record.name());
        assertEquals("2e-4", record.parameters().get("learningRate"));
        assertEquals("0.42", record.metrics().get("validation.loss"));
        assertEquals(List.of("adapter.gguf"), record.artifacts());
        assertEquals("sha256:abc", record.datasetFingerprint());
        assertEquals("Temurin 25", record.jdk());
        assertEquals("macos-aarch64", record.osArch());
        assertEquals("jinfer/0.3.1", record.backend());
        assertEquals("cpu-only", record.hardware());
        assertNotNull(record.provenance());
        assertEquals("sha256:abc", record.provenance().datasetFingerprint());
    }
}
