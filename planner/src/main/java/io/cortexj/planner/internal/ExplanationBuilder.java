package io.cortexj.planner.internal;

import io.cortexj.diagnostics.SupportReport;
import io.cortexj.planner.ExecutionCandidate;
import io.cortexj.planner.MemoryEstimate;
import io.cortexj.planner.PlanReason;
import io.cortexj.spi.repository.ResolvedModel;

import java.util.List;

public final class ExplanationBuilder {

    private ExplanationBuilder() {
    }

    public static String build(ResolvedModel resolved,
                               ExecutionCandidate selected,
                               List<ExecutionCandidate> fallbacks,
                               List<PlanReason> reasons,
                               List<PlanReason> warnings,
                               List<String> runtimeHealthLines,
                               PlannerInputs inputs) {
        StringBuilder sb = new StringBuilder();
        sb.append("Model: ").append(resolved.descriptor().canonicalId()).append('\n');
        if (resolved.hasRevision()) {
            sb.append("Resolved revision: ").append(resolved.revision()).append('\n');
        }
        sb.append('\n');
        sb.append("Environment:\n");
        sb.append("  ").append(inputs.environment.toString()).append('\n');
        sb.append('\n');
        sb.append("Available runtimes:\n");
        for (String line : runtimeHealthLines) {
            sb.append("  - ").append(line).append('\n');
        }
        sb.append('\n');
        sb.append("Selected:\n");
        sb.append("  runtime: ").append(selected.runtimeId()).append('\n');
        if (selected.artifact() != null) {
            sb.append("  artifact: ").append(selected.artifact().id())
                    .append(" (").append(selected.artifact().format())
                    .append(", ").append(mebi(selected.artifact().sizeBytes())).append(" MiB)\n");
        }
        sb.append("  device: ").append(selected.device()).append('\n');
        if (selected.quantization() != null) {
            sb.append("  quantization: ").append(selected.quantization()).append('\n');
        }
        sb.append("  estimated memory: ").append(selected.memoryEstimate()).append('\n');
        sb.append("  score: ").append(String.format(java.util.Locale.ROOT, "%.2f", Double.valueOf(selected.score()))).append('\n');
        sb.append('\n');
        sb.append("Reasons:\n");
        for (PlanReason reason : reasons) {
            sb.append("  ").append(reason).append('\n');
        }
        for (PlanReason warning : warnings) {
            sb.append("  ").append(warning).append('\n');
        }
        if (!fallbacks.isEmpty()) {
            sb.append('\n').append("Fallback:\n");
            int index = 1;
            for (ExecutionCandidate fallback : fallbacks) {
                sb.append("  ").append(index++).append(". ")
                        .append(fallback.runtimeId())
                        .append(" / ").append(fallback.artifact() == null ? "-" : fallback.artifact().id())
                        .append(" / ").append(fallback.device()).append('\n');
            }
        }
        return sb.toString();
    }

    public static String buildSupportExplanation(OutcomeAdapter outcome, PlannerInputs inputs) {
        StringBuilder sb = new StringBuilder();
        sb.append("Model: ").append(outcome.modelId()).append('\n');
        if (outcome.revision() != null) {
            sb.append("Resolved revision: ").append(outcome.revision()).append('\n');
        }
        sb.append('\n');
        if (outcome.viable().isEmpty()) {
            sb.append("This model cannot be executed in the current environment.\n");
            sb.append('\n').append("Rejected candidates:\n");
            for (SupportReport.RejectedCandidate rejected : outcome.rejected()) {
                sb.append("  - ").append(rejected.candidate().runtimeId())
                        .append(" / ").append(rejected.candidate().artifact() == null ? "-" : rejected.candidate().artifact().id())
                        .append(": ").append(rejected.reason()).append('\n');
            }
        } else {
            sb.append("Viable candidates:\n");
            for (ExecutionCandidate candidate : outcome.viable()) {
                sb.append("  - ").append(candidate.runtimeId())
                        .append(" / ").append(candidate.artifact() == null ? "-" : candidate.artifact().id())
                        .append(" / ").append(candidate.device())
                        .append(" (score ").append(String.format(java.util.Locale.ROOT, "%.2f", Double.valueOf(candidate.score())))
                        .append(", est. ").append(candidate.memoryEstimate()).append(")\n");
            }
            if (!outcome.rejected().isEmpty()) {
                sb.append('\n').append("Rejected candidates:\n");
                for (SupportReport.RejectedCandidate rejected : outcome.rejected()) {
                    sb.append("  - ").append(rejected.candidate().runtimeId())
                            .append(" / ").append(rejected.candidate().artifact() == null ? "-" : rejected.candidate().artifact().id())
                            .append(": ").append(rejected.reason()).append('\n');
                }
            }
        }
        return sb.toString();
    }

    public interface OutcomeAdapter {
        String modelId();

        String revision();

        List<ExecutionCandidate> viable();

        List<SupportReport.RejectedCandidate> rejected();
    }

    public static OutcomeAdapter adapt(final String modelId,
                                       final String revision,
                                       final List<ExecutionCandidate> viable,
                                       final List<SupportReport.RejectedCandidate> rejected) {
        return new OutcomeAdapter() {
            @Override
            public String modelId() {
                return modelId;
            }

            @Override
            public String revision() {
                return revision;
            }

            @Override
            public List<ExecutionCandidate> viable() {
                return viable;
            }

            @Override
            public List<SupportReport.RejectedCandidate> rejected() {
                return rejected;
            }
        };
    }

    private static String mebi(long bytes) {
        return String.valueOf(bytes / (1024L * 1024L));
    }
}
