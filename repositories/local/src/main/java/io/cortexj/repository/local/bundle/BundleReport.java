package io.cortexj.repository.local.bundle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class BundleReport {

    private final String modelId;
    private final String revision;
    private final int verifiedArtifacts;
    private final List<String> issues;

    public BundleReport(String modelId, String revision, int verifiedArtifacts, List<String> issues) {
        this.modelId = modelId;
        this.revision = revision;
        this.verifiedArtifacts = verifiedArtifacts;
        this.issues = Collections.unmodifiableList(new ArrayList<String>(issues));
    }

    public String modelId() {
        return modelId;
    }

    public String revision() {
        return revision;
    }

    public int verifiedArtifacts() {
        return verifiedArtifacts;
    }

    public List<String> issues() {
        return issues;
    }

    public boolean isOk() {
        return issues.isEmpty();
    }
}
