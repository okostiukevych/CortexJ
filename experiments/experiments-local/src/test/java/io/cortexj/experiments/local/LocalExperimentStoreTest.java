package io.cortexj.experiments.local;

import io.cortexj.experiments.Experiment;
import io.cortexj.experiments.ExperimentRecord;
import io.cortexj.experiments.ExperimentStore;
import io.cortexj.meta.ModelIdentity;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalExperimentStoreTest {

    @Test
    void doubleRunCapturesIdenticalProvenance() throws Exception {
        Path root = Files.createTempDirectory("cortexj-experiments");
        try {
            ExperimentStore store = new LocalExperimentStore(root);
            Long seed = 42L;
            String datasetHash = "sha256:dataset";
            String revision = "7f62";

            String firstId;
            String secondId;
            try (Experiment first = store.start("qwen-lora", seed, datasetHash,
                    ModelIdentity.of("hf:Qwen/Qwen3-8B", revision), "jinfer/0.3.1")) {
                first.parameter("learningRate", "2e-4");
                first.parameter("epochs", "3");
                first.metric("validation.loss", "0.42");
                firstId = first.id();
            }
            try (Experiment second = store.start("qwen-lora", seed, datasetHash,
                    ModelIdentity.of("hf:Qwen/Qwen3-8B", revision), "jinfer/0.3.1")) {
                second.parameter("learningRate", "2e-4");
                second.parameter("epochs", "3");
                second.metric("validation.loss", "0.42");
                secondId = second.id();
            }

            assertTrue(!firstId.equals(secondId), "runs get distinct ids");
            ExperimentRecord first = store.get(firstId);
            ExperimentRecord second = store.get(secondId);
            assertNotNull(first);
            assertNotNull(second);
            assertEquals(first.parameters(), second.parameters());
            assertEquals(first.metrics(), second.metrics());
            assertEquals(first.datasetFingerprint(), second.datasetFingerprint());
            assertEquals(first.provenance().model().revision(),
                    second.provenance().model().revision());
            assertEquals(first.jdk(), second.jdk());
            assertEquals(first.osArch(), second.osArch());
            assertEquals(first.provenance().backend(), second.provenance().backend());
            assertTrue(first.provenance().seeds().containsKey("seed"));
            assertEquals(Long.valueOf(42L), first.provenance().seeds().get("seed"));

            List<ExperimentRecord> all = store.list();
            assertEquals(2, all.size());
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    void artifactsAreCopiedNeverMoved() throws Exception {
        Path root = Files.createTempDirectory("cortexj-experiments");
        try {
            ExperimentStore store = new LocalExperimentStore(root);
            Path artifact = Files.createTempFile("adapter", ".gguf");
            Files.write(artifact, new byte[]{1, 2, 3});
            try (Experiment experiment = store.start("copy-test", null, "",
                    ModelIdentity.of("a", null), "")) {
                experiment.artifact(artifact);
            }
            ExperimentRecord record = store.list().get(0);
            assertEquals(1, record.artifacts().size());
            Path stored = Path.of(record.artifacts().get(0));
            assertTrue(!stored.equals(artifact), "stored copy must not be the source path");
            assertTrue(Files.exists(artifact), "source artifact must stay in place");
            assertEquals(3, Files.readAllBytes(stored).length);
        } finally {
            deleteRecursively(root);
        }
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (Files.isDirectory(path)) {
            try (var stream = Files.list(path)) {
                for (Path child : stream.toList()) {
                    deleteRecursively(child);
                }
            }
        }
        Files.deleteIfExists(path);
    }
}
