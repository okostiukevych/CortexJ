package io.cortexj.train.experiments;

import io.cortexj.experiments.Experiment;
import io.cortexj.experiments.ExperimentRecord;
import io.cortexj.experiments.ExperimentStore;
import io.cortexj.experiments.local.LocalExperimentStore;
import io.cortexj.meta.ModelIdentity;
import io.cortexj.train.TrainingBackend;
import io.cortexj.train.TrainingJobs;
import io.cortexj.train.TrainingRequest;
import io.cortexj.train.TrainingResult;
import io.cortexj.train.TrainingStrategy;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ExperimentTrainingListenerTest {

    @Test
    void metricsStreamIntoTheExperiment() throws Exception {
        Path root = Files.createTempDirectory("train-experiments");
        try {
            ExperimentStore store = new LocalExperimentStore(root);
            TrainingBackend backend = new TrainingBackend() {
                @Override
                public String id() {
                    return "synthetic";
                }

                @Override
                public Support evaluate(TrainingRequest request) {
                    return new Support(Level.FULL, "test", 0);
                }

                @Override
                public TrainingResult train(TrainingRequest request, Support support) {
                    return new TrainingResult("job-9",
                            ModelIdentity.of("m", "r"), "/tmp/a.safetensors",
                            Map.of("final.loss", 0.5),
                            new TrainingResult.ProvenanceHolder("d", null, "synthetic",
                                    "cpu", Map.of()));
                }
            };
            TrainingJobs jobs = new TrainingJobs(Arrays.asList(backend));
            TrainingRequest request = TrainingRequest.builder()
                    .model("m").dataset("d").strategy(TrainingStrategy.LORA).build();
            ExperimentRecord record;
            String id;
            try (Experiment experiment = store.start("stream-test", 1L, "d",
                    ModelIdentity.of("m", "r"), "synthetic")) {
                jobs.start(request, new ExperimentTrainingListener(experiment));
                id = experiment.id();
            }
            // metrics are persisted on close; the reread carries them
            record = store.get(id);
            assertEquals("0.5", record.metrics().get("final.loss"));
            assertEquals("/tmp/a.safetensors", record.metrics().get("artifact"));
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
