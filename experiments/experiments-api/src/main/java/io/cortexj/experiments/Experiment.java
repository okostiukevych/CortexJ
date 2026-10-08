package io.cortexj.experiments;

import io.cortexj.meta.ModelIdentity;

/**
 * One managed experiment run. Parameters, metrics and artifacts captured
 * through close; provenance (model revision, dataset fingerprint, seeds,
 * environment) is captured at start by the store.
 */
public interface Experiment extends AutoCloseable {

    String id();

    String name();

    void parameter(String key, String value);

    void metric(String key, String value);

    /**
     * Copies the artifact into the store. The source file stays in place.
     */
    void artifact(java.nio.file.Path artifact);

    /**
     * The model identity (id + resolved revision) captured at start.
     */
    ModelIdentity model();

    /**
     * The dataset fingerprint captured at start (empty while unknown).
     */
    String datasetFingerprint();
}
