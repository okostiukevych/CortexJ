package io.cortexj.experiments;

import io.cortexj.meta.ModelIdentity;
import io.cortexj.provenance.ProvenanceManifest;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of one captured run: parameters, metrics, artifacts
 * plus the embedded provenance manifest (ADR-020).
 */
public final class ExperimentRecord {

    private final String id;
    private final String name;
    private final Map<String, String> parameters;
    private final Map<String, String> metrics;
    private final List<String> artifacts;
    private final String datasetFingerprint;
    private final String jdk;
    private final String osArch;
    private final String backend;
    private final String hardware;
    private final ProvenanceManifest provenance;

    public ExperimentRecord(String id, String name,
                            Map<String, String> parameters,
                            Map<String, String> metrics,
                            List<String> artifacts,
                            String datasetFingerprint,
                            String jdk, String osArch,
                            String backend, String hardware,
                            ProvenanceManifest provenance) {
        if (id == null || name == null) {
            throw new IllegalArgumentException("experiment id and name must not be null");
        }
        this.id = id;
        this.name = name;
        this.parameters = parameters == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(parameters));
        this.metrics = metrics == null
                ? Collections.<String, String>emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<String, String>(metrics));
        this.artifacts = artifacts == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(new java.util.ArrayList<String>(artifacts));
        this.datasetFingerprint = datasetFingerprint == null ? "" : datasetFingerprint;
        this.jdk = jdk == null ? "" : jdk;
        this.osArch = osArch == null ? "" : osArch;
        this.backend = backend == null ? "" : backend;
        this.hardware = hardware == null ? "" : hardware;
        this.provenance = provenance;
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Map<String, String> parameters() {
        return parameters;
    }

    public Map<String, String> metrics() {
        return metrics;
    }

    public List<String> artifacts() {
        return artifacts;
    }

    public String datasetFingerprint() {
        return datasetFingerprint;
    }

    public String jdk() {
        return jdk;
    }

    public String osArch() {
        return osArch;
    }

    public String backend() {
        return backend;
    }

    public String hardware() {
        return hardware;
    }

    public ProvenanceManifest provenance() {
        return provenance;
    }
}
