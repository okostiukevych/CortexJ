package io.cortexj.examples.northstar;

import io.cortexj.data.Fingerprint;
import io.cortexj.data.Frame;
import io.cortexj.data.FrameDataset;
import io.cortexj.data.csv.CsvFrame;
import io.cortexj.experiments.Experiment;
import io.cortexj.experiments.ExperimentRecord;
import io.cortexj.experiments.ExperimentStore;
import io.cortexj.experiments.local.LocalExperimentStore;
import io.cortexj.meta.ModelIdentity;
import io.cortexj.provenance.ProvenanceManifest;
import io.cortexj.train.peft.LoRAAdapter;
import io.cortexj.train.peft.LoRAConfig;
import io.cortexj.train.peft.LoRASafetensors;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

/**
 * The long-term reference flow, runnable on CPU-only CI (ADR-028):
 * dataset → fingerprint → reproducible experiment → LoRA adapter →
 * merge → checksums → provenance → lockfile. The Runtime-deploy step
 * needs a real model artifact and is exercised by the provider tests
 * (live-verified) and skipped loudly here.
 */
public final class NorthStarDemo {

    public static void main(String[] args) throws Exception {
        Path work = Files.createTempDirectory("cortexj-north-star");
        try {
            stepData(work);
            stepExperiment(work);
            stepAdapter(work);
            stepLock(work);
            System.out.println("NORTH-STAR-OK");
        } finally {
            deleteRecursively(work);
        }
    }

    private static Path datasetPath(Path work) throws Exception {
        URL resource = NorthStarDemo.class.getResource("/northstar/reviews.csv");
        return Path.of(resource.toURI());
    }

    private static void stepData(Path work) throws Exception {
        Frame frame = CsvFrame.read(datasetPath(work));
        FrameDataset dataset = new FrameDataset(frame);
        String fingerprint = dataset.fingerprint();
        System.out.println("data: rows=" + dataset.count() + " fingerprint=" + fingerprint);
        for (io.cortexj.data.Dataset<java.util.List<String>> batch : dataset.batches(2)) {
            System.out.println("  batch rows=" + batch.count());
        }
        var split = dataset.split(0.5, 42L);
        System.out.println("  split train=" + split.train().count()
                + " test=" + split.test().count());
    }

    private static void stepExperiment(Path work) throws Exception {
        ExperimentStore store = new LocalExperimentStore(work.resolve("experiments"));
        Frame frame = CsvFrame.read(datasetPath(work));
        String datasetFingerprint = Fingerprint.ofFrame(frame);
        try (Experiment experiment = store.start("lora-demo", 42L, datasetFingerprint,
                ModelIdentity.of("company/review-model", "rev-1"), "reference/0.1")) {
            experiment.parameter("learningRate", "2e-4");
            experiment.parameter("epochs", "3");
            experiment.metric("validation.loss", "0.42");
        }
        ExperimentRecord record = store.list().get(0);
        System.out.println("experiment: id=" + record.id()
                + " revision=" + record.provenance().model().revision()
                + " dataset=" + record.datasetFingerprint()
                + " seeds=" + record.provenance().seeds());
    }

    private static void stepAdapter(Path work) throws Exception {
        LoRAConfig config = new LoRAConfig(2, 4, 0.0, Arrays.asList("q_proj"));
        // deterministic trained-matrices stand-in (the live fine-tuning path
        // is exercised by the training backend tests)
        float[][] a = {{0.5f, -0.25f}, {1.0f, 0.0f}};
        float[][] b = {{1.0f, 0.0f}, {0.25f, 0.5f}, {0.0f, 2.0f}};
        LoRAAdapter adapter = new LoRAAdapter("q_proj", 2, 3, config, a, b);

        float[][] base = {{1.0f, 2.0f}, {3.0f, 4.0f}, {5.0f, 6.0f}};
        float[][] merged = adapter.merge(base);
        System.out.println("adapter: merged[0][0]=" + merged[0][0] + " (base untouched: "
                + base[0][0] + ")");

        Path adapterFile = work.resolve("adapter_model.safetensors");
        LoRASafetensors.write(adapter, adapterFile, Map.of("demo", "north-star"));
        LoRAAdapter read = LoRASafetensors.read(adapterFile);
        System.out.println("adapter round-trip: rank=" + read.config().rank()
                + " alpha=" + read.config().alpha());
    }

    private static void stepLock(Path work) throws Exception {
        Frame frame = CsvFrame.read(datasetPath(work));
        ProvenanceManifest manifest = ProvenanceManifest.builder()
                .producer("north-star-demo")
                .model(ModelIdentity.of("company/review-model", "rev-1"))
                .datasetFingerprint(Fingerprint.ofFrame(frame))
                .platformVersion("0.1.0")
                .parameter("learningRate", "2e-4")
                .seed("shuffle", 42L)
                .checksum("adapter_model.safetensors",
                        Fingerprint.sha256Hex("adapter-model-content"))
                .build();
        Path lock = work.resolve("model.lockfile");
        Files.writeString(lock, "model: company/review-model\n"
                + "revision: rev-1\n"
                + "datasetFingerprint: " + manifest.datasetFingerprint() + "\n"
                + "seed: " + manifest.seeds().get("shuffle") + "\n", StandardCharsets.UTF_8);
        System.out.println("lockfile written, checksum present: " + manifest.checksumPresent());
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
