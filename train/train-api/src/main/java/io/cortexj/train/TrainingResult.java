package io.cortexj.train;

import io.cortexj.meta.ModelIdentity;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Result of one completed training run: the produced adapter/model
 * artifact, captured metrics and the embedded provenance manifest.
 */
public final class TrainingResult {

    private final String jobId;
    private final ModelIdentity model;
    private final String artifactPath;
    private final Map<String, Double> metrics;
    private final ProvenanceHolder provenance;

    public TrainingResult(String jobId, ModelIdentity model, String artifactPath,
                          Map<String, Double> metrics, ProvenanceHolder provenance) {
        if (jobId == null || artifactPath == null) {
            throw new IllegalArgumentException("job id and artifact path must not be null");
        }
        this.jobId = jobId;
        this.model = model;
        this.artifactPath = artifactPath;
        this.metrics = metrics == null
                ? Collections.<String, Double>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, Double>(metrics));
        this.provenance = provenance;
    }

    public String jobId() {
        return jobId;
    }

    public ModelIdentity model() {
        return model;
    }

    public String artifactPath() {
        return artifactPath;
    }

    public Map<String, Double> metrics() {
        // audit 2026-10-10 (SpotBugs EI_EXPOSE_REP): owned copy per call
        return new LinkedHashMap<String, Double>(metrics);
    }

    public ProvenanceHolder provenance() {
        return provenance;
    }

    /**
     * Provenance summary of the run: dataset fingerprint, seeds, backend,
     * hardware and output checksums (the shared manifest is filled by the
     * backend through the experiments integration).
     */
    public static final class ProvenanceHolder {

        private final String datasetFingerprint;
        private final List<Long> seeds;
        private final String backend;
        private final String hardware;
        private final Map<String, String> checksums;

        public ProvenanceHolder(String datasetFingerprint, List<Long> seeds,
                                String backend, String hardware, Map<String, String> checksums) {
            this.datasetFingerprint = datasetFingerprint == null ? "" : datasetFingerprint;
            this.seeds = seeds == null
                    ? Collections.<Long>emptyList()
                    : Collections.unmodifiableList(new java.util.ArrayList<Long>(seeds));
            this.backend = backend == null ? "" : backend;
            this.hardware = hardware == null ? "" : hardware;
            this.checksums = checksums == null
                    ? Collections.<String, String>emptyMap()
                    : Collections.unmodifiableMap(new LinkedHashMap<String, String>(checksums));
        }

        public String datasetFingerprint() {
            return datasetFingerprint;
        }

        public List<Long> seeds() {
            // audit 2026-10-10 (SpotBugs EI_EXPOSE_REP): owned copy per call
            return new java.util.ArrayList<Long>(seeds);
        }

        public String backend() {
            return backend;
        }

        public String hardware() {
            return hardware;
        }

        public Map<String, String> checksums() {
            // audit 2026-10-10 (SpotBugs EI_EXPOSE_REP): owned copy per call
            return new LinkedHashMap<String, String>(checksums);
        }
    }
}
