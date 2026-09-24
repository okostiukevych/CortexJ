package io.cortexj.planner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ExecutionPlan {

    private final String modelId;
    private final String resolvedRevision;
    private final ExecutionCandidate selected;
    private final List<ExecutionCandidate> fallbacks;
    private final List<PlanReason> reasons;
    private final List<PlanReason> warnings;
    private final MemoryEstimate memoryEstimate;
    private final List<PlanDownload> downloads;
    private final List<String> nativeRequirements;
    private final String explanation;
    private final String platformVersion;
    private final String plannerVersion;
    private final String inputsDigest;

    public ExecutionPlan(String modelId,
                        String resolvedRevision,
                        ExecutionCandidate selected,
                        List<ExecutionCandidate> fallbacks,
                        List<PlanReason> reasons,
                        List<PlanReason> warnings,
                        MemoryEstimate memoryEstimate,
                        List<PlanDownload> downloads,
                        List<String> nativeRequirements,
                        String explanation,
                        String platformVersion,
                        String plannerVersion,
                        String inputsDigest) {
        this.modelId = modelId;
        this.resolvedRevision = resolvedRevision;
        this.selected = selected;
        this.fallbacks = Collections.unmodifiableList(new ArrayList<ExecutionCandidate>(fallbacks));
        this.reasons = Collections.unmodifiableList(new ArrayList<PlanReason>(reasons));
        this.warnings = Collections.unmodifiableList(new ArrayList<PlanReason>(warnings));
        this.memoryEstimate = memoryEstimate == null ? MemoryEstimate.unknown() : memoryEstimate;
        this.downloads = Collections.unmodifiableList(new ArrayList<PlanDownload>(downloads));
        this.nativeRequirements = Collections.unmodifiableList(new ArrayList<String>(nativeRequirements));
        this.explanation = explanation == null ? "" : explanation;
        this.platformVersion = platformVersion;
        this.plannerVersion = plannerVersion;
        this.inputsDigest = inputsDigest;
    }

    public String modelId() {
        return modelId;
    }

    public String resolvedRevision() {
        return resolvedRevision;
    }

    public ExecutionCandidate selected() {
        return selected;
    }

    public List<ExecutionCandidate> fallbacks() {
        return fallbacks;
    }

    public List<PlanReason> reasons() {
        return reasons;
    }

    public List<PlanReason> warnings() {
        return warnings;
    }

    public MemoryEstimate memoryEstimate() {
        return memoryEstimate;
    }

    public List<PlanDownload> downloads() {
        return downloads;
    }

    public List<String> nativeRequirements() {
        return nativeRequirements;
    }

    public String explanation() {
        return explanation;
    }

    public String platformVersion() {
        return platformVersion;
    }

    public String plannerVersion() {
        return plannerVersion;
    }

    public String inputsDigest() {
        return inputsDigest;
    }

    @Override
    public String toString() {
        return explanation;
    }
}
