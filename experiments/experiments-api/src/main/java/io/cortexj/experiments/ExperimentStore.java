package io.cortexj.experiments;

import io.cortexj.meta.ModelIdentity;

import java.util.List;

/**
 * Storage for experiment runs. The local filesystem implementation is
 * bundled; external trackers arrive as separate adapters.
 */
public interface ExperimentStore {

    Experiment start(String name, Long seed, String datasetFingerprint,
                     ModelIdentity model, String backend);

    List<ExperimentRecord> list();

    ExperimentRecord get(String id);
}
