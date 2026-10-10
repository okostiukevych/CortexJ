package io.cortexj.experiments.local;

import io.cortexj.experiments.Experiment;
import io.cortexj.experiments.ExperimentRecord;
import io.cortexj.experiments.ExperimentStore;
import io.cortexj.meta.ModelIdentity;
import io.cortexj.provenance.ProvenanceManifest;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Filesystem experiment store: one directory per run with
 * {@code manifest.properties}, {@code metrics.properties} and an
 * {@code artifacts/} folder. Artifacts are copied, never moved.
 * Properties files only - no Jackson.
 */
public final class LocalExperimentStore implements ExperimentStore {

    private final Path root;

    public LocalExperimentStore(Path root) {
        if (root == null) {
            throw new IllegalArgumentException("store root must not be null");
        }
        this.root = root;
    }

    public Path root() {
        return root;
    }

    @Override
    public Experiment start(String name, Long seed, String datasetFingerprint,
                            ModelIdentity model, String backend) {
        String id = "run-" + System.nanoTime();
        Path runDirectory = runDirectory(id);
        try {
            Files.createDirectories(runDirectory.resolve("artifacts"));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create run directory: " + runDirectory, e);
        }
        ProvenanceManifest manifest = ProvenanceManifest.builder()
                .producer("cortexj-experiments")
                .model(model)
                .datasetFingerprint(datasetFingerprint)
                .jdk(java.lang.System.getProperty("java.vm.name") + " "
                        + java.lang.System.getProperty("java.version"))
                .osArch(osArch())
                .backend(backend)
                .platformVersion(platformVersion())
                .seed("seed", seed == null ? 0L : seed)
                .createdAtMillis(System.currentTimeMillis())
                .build();
        return new LocalExperiment(id, name, runDirectory, manifest);
    }

    @Override
    public List<ExperimentRecord> list() {
        List<ExperimentRecord> records = new ArrayList<ExperimentRecord>();
        try (var stream = Files.list(root)) {
            for (Path child : stream.filter(Files::isDirectory).sorted().toList()) {
                // audit 2026-10-10 (SpotBugs NP): getFileName() is null for
                // root paths - skip rather than dereference
                java.nio.file.Path name = child.getFileName();
                if (name == null) {
                    continue;
                }
                ExperimentRecord record = read(name.toString());
                if (record != null) {
                    records.add(record);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list experiments under " + root, e);
        }
        return Collections.unmodifiableList(records);
    }

    @Override
    public ExperimentRecord get(String id) {
        return read(id);
    }

    private ExperimentRecord read(String id) {
        Path runDirectory = runDirectory(id);
        if (!Files.isDirectory(runDirectory)) {
            return null;
        }
        Properties manifest = load(runDirectory.resolve("manifest.properties"));
        Properties metrics = load(runDirectory.resolve("metrics.properties"));
        List<String> artifacts = new ArrayList<String>();
        Path artifactsDirectory = runDirectory.resolve("artifacts");
        try (var stream = Files.list(artifactsDirectory)) {
            for (Path artifact : stream.sorted().toList()) {
                artifacts.add(artifact.toAbsolutePath().toString());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list artifacts under " + artifactsDirectory, e);
        }
        return new ExperimentRecord(
                id,
                manifest.getProperty("name", ""),
                stringMap(manifest, "parameter."),
                stringMap(metrics, null),
                artifacts,
                manifest.getProperty("datasetFingerprint", ""),
                manifest.getProperty("jdk", ""),
                manifest.getProperty("osArch", ""),
                manifest.getProperty("backend", ""),
                manifest.getProperty("hardware", ""),
                ProvenanceManifest.builder()
                        .model(ModelIdentity.of(manifest.getProperty("model.id", ""),
                                manifest.getProperty("model.revision")))
                        .datasetFingerprint(manifest.getProperty("datasetFingerprint", ""))
                        .producer(manifest.getProperty("producer", ""))
                        .platformVersion(manifest.getProperty("platformVersion", ""))
                        .jdk(manifest.getProperty("jdk", ""))
                        .osArch(manifest.getProperty("osArch", ""))
                        .backend(manifest.getProperty("backend", ""))
                        .seed("seed", Long.parseLong(manifest.getProperty("seed.seed", "0")))
                        .createdAtMillis(Long.parseLong(manifest.getProperty("createdAtMillis", "0")))
                        .build());
    }

    private static Map<String, String> stringMap(Properties properties, String prefix) {
        Map<String, String> map = new LinkedHashMap<String, String>();
        for (String key : properties.stringPropertyNames()) {
            if (prefix == null) {
                map.put(key, properties.getProperty(key));
            } else if (key.startsWith(prefix)) {
                map.put(key.substring(prefix.length()), properties.getProperty(key));
            }
        }
        return map;
    }

    private static Properties load(Path file) {
        Properties properties = new Properties();
        if (!Files.isRegularFile(file)) {
            return properties;
        }
        try (InputStream input = Files.newInputStream(file)) {
            properties.load(input);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read " + file, e);
        }
        return properties;
    }

    private static String osArch() {
        return java.lang.System.getProperty("os.name", "unknown").toLowerCase()
                + "-" + java.lang.System.getProperty("os.arch", "unknown");
    }

    private static String platformVersion() {
        String version = LocalExperimentStore.class.getPackage() == null
                ? null : LocalExperimentStore.class.getPackage().getImplementationVersion();
        return version == null ? "unknown" : version;
    }

    private Path runDirectory(String id) {
        return root.resolve(id);
    }

    private static final class LocalExperiment implements Experiment {

        private final String id;
        private final String name;
        private final Path runDirectory;
        private final ProvenanceManifest manifest;
        private final Map<String, String> parameters = new LinkedHashMap<String, String>();
        private final Map<String, String> metrics = new LinkedHashMap<String, String>();
        private boolean closed;

        LocalExperiment(String id, String name, Path runDirectory, ProvenanceManifest manifest) {
            this.id = id;
            this.name = name;
            this.runDirectory = runDirectory;
            this.manifest = manifest;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void parameter(String key, String value) {
            requireOpen();
            if (key != null && value != null) {
                parameters.put(key, value);
            }
        }

        @Override
        public void metric(String key, String value) {
            requireOpen();
            if (key != null && value != null) {
                metrics.put(key, value);
            }
        }

        @Override
        public void artifact(Path artifact) {
            requireOpen();
            if (artifact == null || !Files.isRegularFile(artifact)) {
                throw new IllegalArgumentException("artifact must be an existing file: " + artifact);
            }
            java.nio.file.Path artifactName = artifact.getFileName();
            if (artifactName == null) {
                throw new IllegalArgumentException("artifact path has no file name: " + artifact);
            }
            Path target = runDirectory.resolve("artifacts").resolve(artifactName.toString());
            try {
                Files.copy(artifact, target, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot copy artifact into " + target, e);
            }
        }

        @Override
        public ModelIdentity model() {
            return manifest.model();
        }

        @Override
        public String datasetFingerprint() {
            return manifest.datasetFingerprint();
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            Properties manifestProperties = new Properties();
            manifestProperties.setProperty("name", name);
            manifestProperties.setProperty("producer", manifest.producer());
            manifestProperties.setProperty("model.id", manifest.model().id());
            if (manifest.model().revision() != null) {
                manifestProperties.setProperty("model.revision", manifest.model().revision());
            }
            manifestProperties.setProperty("datasetFingerprint", manifest.datasetFingerprint());
            manifestProperties.setProperty("platformVersion", manifest.platformVersion());
            manifestProperties.setProperty("jdk", manifest.jdk());
            manifestProperties.setProperty("osArch", manifest.osArch());
            manifestProperties.setProperty("backend", manifest.backend());
            manifestProperties.setProperty("hardware", manifest.hardware());
            for (Map.Entry<String, Long> seed : manifest.seeds().entrySet()) {
                manifestProperties.setProperty("seed." + seed.getKey(),
                        String.valueOf(seed.getValue()));
            }
            manifestProperties.setProperty("createdAtMillis",
                    String.valueOf(manifest.createdAtMillis()));
            try (OutputStream output = Files.newOutputStream(
                    runDirectory.resolve("manifest.properties"))) {
                manifestProperties.store(output, "cortexj experiment manifest");
            } catch (IOException e) {
                throw new UncheckedIOException("cannot write manifest", e);
            }
            Properties metricProperties = new Properties();
            for (Map.Entry<String, String> metric : metrics.entrySet()) {
                metricProperties.setProperty(metric.getKey(), metric.getValue());
            }
            try (OutputStream output = Files.newOutputStream(
                    runDirectory.resolve("metrics.properties"))) {
                metricProperties.store(output, "cortexj experiment metrics");
            } catch (IOException e) {
                throw new UncheckedIOException("cannot write metrics", e);
            }
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException("experiment is closed: " + id);
            }
        }
    }
}
