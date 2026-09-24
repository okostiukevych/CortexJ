package io.cortexj.diagnostics;

import io.cortexj.planner.ExecutionCandidate;
import io.cortexj.planner.PlanDownload;
import io.cortexj.planner.PlanReason;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class SupportReport {

    public static final class RejectedCandidate {

        private final ExecutionCandidate candidate;
        private final PlanReason reason;

        public RejectedCandidate(ExecutionCandidate candidate, PlanReason reason) {
            this.candidate = candidate;
            this.reason = reason;
        }

        public ExecutionCandidate candidate() {
            return candidate;
        }

        public PlanReason reason() {
            return reason;
        }
    }

    private final String modelId;
    private final String resolvedRevision;
    private final List<ExecutionCandidate> viableCandidates;
    private final List<RejectedCandidate> rejectedCandidates;
    private final List<PlanDownload> requiredDownloads;
    private final List<Recommendation> recommendations;
    private final List<PlanReason> warnings;
    private final String explanation;

    public SupportReport(String modelId,
                        String resolvedRevision,
                        List<ExecutionCandidate> viableCandidates,
                        List<RejectedCandidate> rejectedCandidates,
                        List<PlanDownload> requiredDownloads,
                        List<Recommendation> recommendations,
                        List<PlanReason> warnings,
                        String explanation) {
        this.modelId = modelId;
        this.resolvedRevision = resolvedRevision;
        this.viableCandidates = Collections.unmodifiableList(new ArrayList<ExecutionCandidate>(viableCandidates));
        this.rejectedCandidates = Collections.unmodifiableList(new ArrayList<RejectedCandidate>(rejectedCandidates));
        this.requiredDownloads = Collections.unmodifiableList(new ArrayList<PlanDownload>(requiredDownloads));
        this.recommendations = Collections.unmodifiableList(new ArrayList<Recommendation>(recommendations));
        this.warnings = Collections.unmodifiableList(new ArrayList<PlanReason>(warnings));
        this.explanation = explanation == null ? "" : explanation;
    }

    public String modelId() {
        return modelId;
    }

    public String resolvedRevision() {
        return resolvedRevision;
    }

    public boolean isExecutable() {
        return !viableCandidates.isEmpty();
    }

    public List<ExecutionCandidate> viableCandidates() {
        return viableCandidates;
    }

    public List<RejectedCandidate> rejectedCandidates() {
        return rejectedCandidates;
    }

    public List<PlanDownload> requiredDownloads() {
        return requiredDownloads;
    }

    public List<Recommendation> recommendations() {
        return recommendations;
    }

    public List<PlanReason> warnings() {
        return warnings;
    }

    public String explanation() {
        return explanation;
    }

    @Override
    public String toString() {
        return explanation;
    }
}
